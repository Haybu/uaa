package org.cloudfoundry.identity.uaa.scim.endpoints;

import com.unboundid.scim.sdk.AttributePath;
import com.unboundid.scim.sdk.SCIMFilter;
import org.cloudfoundry.identity.uaa.account.UserAccountStatus;
import org.cloudfoundry.identity.uaa.annotations.WithSpring;
import org.cloudfoundry.identity.uaa.approval.Approval;
import org.cloudfoundry.identity.uaa.approval.ApprovalStore;
import org.cloudfoundry.identity.uaa.approval.JdbcApprovalStore;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.mfa.JdbcUserGoogleMfaCredentialsProvisioning;
import org.cloudfoundry.identity.uaa.provider.*;
import org.cloudfoundry.identity.uaa.resources.SearchResults;
import org.cloudfoundry.identity.uaa.resources.SimpleAttributeNameMapper;
import org.cloudfoundry.identity.uaa.resources.jdbc.SimpleSearchQueryConverter;
import org.cloudfoundry.identity.uaa.scim.*;
import org.cloudfoundry.identity.uaa.scim.exception.*;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.validate.PasswordValidator;
import org.cloudfoundry.identity.uaa.security.IsSelfCheck;
import org.cloudfoundry.identity.uaa.security.PollutionPreventionExtension;
import org.cloudfoundry.identity.uaa.test.ZoneSeeder;
import org.cloudfoundry.identity.uaa.test.ZoneSeederExtension;
import org.cloudfoundry.identity.uaa.web.ConvertingExceptionView;
import org.cloudfoundry.identity.uaa.web.ExceptionReportHttpMessageConverter;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.MfaConfig;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.servlet.View;

import java.util.*;

import static java.util.Arrays.asList;
import static org.cloudfoundry.identity.uaa.util.AssertThrowsWithMessage.assertThrowsWithMessageThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@WithSpring
@ExtendWith(PollutionPreventionExtension.class)
@ExtendWith(ZoneSeederExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// TODO: Stop using @WithSpring. It's messing up UaaTokenServicesTests.
class ScimUserEndpointsTests {

    private static final String JDSA_VMWARE_COM = "jd'sa@vmware.com";

    @Autowired
    private ScimUserEndpoints scimUserEndpoints;

    @Autowired
    private ScimGroupEndpoints scimGroupEndpoints;

    @Autowired
    private JdbcScimGroupProvisioning jdbcScimGroupProvisioning;

    @Autowired
    private JdbcScimUserProvisioning jdbcScimUserProvisioning;

    @Autowired
    private JdbcScimGroupMembershipManager jdbcScimGroupMembershipManager;

    @Autowired
    private JdbcApprovalStore jdbcApprovalStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("nonCachingPasswordEncoder")
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IdentityZoneManager identityZoneManager;

    private ScimUser joel;
    private ScimUser dale;

    private PasswordValidator mockPasswordValidator;
    private JdbcUserGoogleMfaCredentialsProvisioning mockJdbcUserGoogleMfaCredentialsProvisioning;
    private JdbcIdentityProviderProvisioning mockJdbcIdentityProviderProvisioning;

    private final RandomValueStringGenerator generator;
    private final SimpleSearchQueryConverter filterConverter;
    private Map<Class<? extends Exception>, HttpStatus> exceptionToStatusMap;

    {
        generator = new RandomValueStringGenerator();

        filterConverter = new SimpleSearchQueryConverter();
        Map<String, String> replaceWith = new HashMap<>();
        replaceWith.put("emails\\.value", "email");
        replaceWith.put("groups\\.display", "authorities");
        replaceWith.put("phoneNumbers\\.value", "phoneNumber");
        filterConverter.setAttributeNameMapper(new SimpleAttributeNameMapper(replaceWith));

        joel = new ScimUser(null, "jdsa", "Joel", "D'sa");
        joel.addEmail(JDSA_VMWARE_COM);
        dale = new ScimUser(null, "olds", "Dale", "Olds");
        dale.addEmail("olds@vmware.com");

        exceptionToStatusMap = new HashMap<>();
        exceptionToStatusMap.put(IllegalArgumentException.class, HttpStatus.BAD_REQUEST);
        exceptionToStatusMap.put(UnsupportedOperationException.class, HttpStatus.BAD_REQUEST);
        exceptionToStatusMap.put(BadSqlGrammarException.class, HttpStatus.BAD_REQUEST);
        exceptionToStatusMap.put(DataIntegrityViolationException.class, HttpStatus.BAD_REQUEST);
        exceptionToStatusMap.put(HttpMessageConversionException.class, HttpStatus.BAD_REQUEST);
        exceptionToStatusMap.put(HttpMediaTypeException.class, HttpStatus.BAD_REQUEST);
    }

    private IdentityZone identityZone;

    @BeforeEach
    void setUp(final ZoneSeeder zoneSeeder) {
        zoneSeeder.withDefaults().afterSeeding(zs -> setUpAfterSeeding(zs.getIdentityZone()));
    }

    void setUpAfterSeeding(final IdentityZone identityZone) {
        this.identityZone = identityZone;
        identityZoneManager.setCurrentIdentityZone(this.identityZone);
        this.identityZone.getConfig().getUserConfig().setDefaultGroups(Collections.singletonList("uaa.user"));

        jdbcScimUserProvisioning.setQueryConverter(filterConverter);

        mockJdbcIdentityProviderProvisioning = mock(JdbcIdentityProviderProvisioning.class);
        mockJdbcUserGoogleMfaCredentialsProvisioning = mock(JdbcUserGoogleMfaCredentialsProvisioning.class);
        mockPasswordValidator = mock(PasswordValidator.class);

        doThrow(new InvalidPasswordException("Password must be at least 1 characters in length."))
                .when(mockPasswordValidator).validate(null);
        doThrow(new InvalidPasswordException("Password must be at least 1 characters in length."))
                .when(mockPasswordValidator).validate(eq(""));

        jdbcScimGroupProvisioning.createOrGet(new ScimGroup(null, "uaa.user", identityZone.getId()), identityZone.getId());
        scimGroupEndpoints.setGroupMaxCount(5);

        joel = jdbcScimUserProvisioning.createUser(joel, "password", identityZone.getId());
        dale = jdbcScimUserProvisioning.createUser(dale, "password", identityZone.getId());

        scimUserEndpoints.setUserMaxCount(5);
        scimUserEndpoints.setScimGroupMembershipManager(jdbcScimGroupMembershipManager);
        scimUserEndpoints.setMfaCredentialsProvisioning(mockJdbcUserGoogleMfaCredentialsProvisioning);
        scimUserEndpoints.setScimUserProvisioning(jdbcScimUserProvisioning);
        scimUserEndpoints.setIdentityProviderProvisioning(mockJdbcIdentityProviderProvisioning);
        scimUserEndpoints.setApplicationEventPublisher(null);
        scimUserEndpoints.setPasswordValidator(mockPasswordValidator);
        scimUserEndpoints.setStatuses(exceptionToStatusMap);
        scimUserEndpoints.setApprovalStore(jdbcApprovalStore);
        scimUserEndpoints.setIsSelfCheck(new IsSelfCheck(null));
    }

    @Test
    void validate_password_for_uaa_only() {
        validatePasswordForUaaOriginOnly(times(1), OriginKeys.UAA, "password");
    }

    @Test
    void validate_password_not_called_for_non_uaa() {
        validatePasswordForUaaOriginOnly(never(), OriginKeys.LOGIN_SERVER, "");
    }

    @Test
    void password_validation_defaults_to_uaa() {
        validatePasswordForUaaOriginOnly(times(1), "", "password");
    }

    @Test
    void groupsIsSyncedCorrectlyOnCreate() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dsyer@vmware.com");
        user.setGroups(Collections.singletonList(new ScimUser.Group(null, "test1")));
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        validateUserGroups(created, "uaa.user");
    }

    @Test
    void groupsIsSyncedCorrectlyOnUpdate() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.addEmail("dsyer@vmware.com");
        user.setPassword("password");
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        validateUserGroups(created, "uaa.user");

        created.setGroups(Collections.singletonList(new ScimUser.Group(null, "test1")));
        ScimUser updated = scimUserEndpoints.updateUser(created, created.getId(), "*", new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        validateUserGroups(updated, "uaa.user");
    }

    @Test
    void groupsIsSyncedCorrectlyOnGet() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dsyer@vmware.com");
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());

        validateUserGroups(created, "uaa.user");

        ScimGroup g = new ScimGroup(null, "test1", identityZone.getId());
        g.setMembers(Collections.singletonList(new ScimGroupMember(created.getId())));
        scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());

        validateUserGroups(scimUserEndpoints.getUser(created.getId(), new MockHttpServletResponse()), "test1");
    }

    @Test
    void approvalsIsSyncedCorrectlyOnCreate() {
        ScimUser user = new ScimUser(null, "vidya", "Vidya", "V");
        user.addEmail("vidya@vmware.com");
        user.setPassword("password");
        user.setApprovals(Collections.singleton(new Approval()
                .setUserId("vidya")
                .setClientId("c1")
                .setScope("s1")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.APPROVED)));
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertNotNull(created.getApprovals());
        assertEquals(1, created.getApprovals().size());
    }

    @Test
    void approvalsIsSyncedCorrectlyOnUpdate() {
        ScimUser user = new ScimUser(null, "vidya", "Vidya", "V");
        user.addEmail("vidya@vmware.com");
        user.setPassword("password");
        user.setApprovals(Collections.singleton(new Approval()
                .setUserId("vidya")
                .setClientId("c1")
                .setScope("s1")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.APPROVED)));
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        jdbcApprovalStore.addApproval(new Approval()
                .setUserId(created.getId())
                .setClientId("c1")
                .setScope("s1")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.APPROVED), identityZone.getId());
        jdbcApprovalStore.addApproval(new Approval()
                .setUserId(created.getId())
                .setClientId("c1")
                .setScope("s2")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.DENIED), identityZone.getId());

        created.setApprovals(Collections.singleton(new Approval()
                .setUserId("vidya")
                .setClientId("c1")
                .setScope("s1")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.APPROVED)));
        ScimUser updated = scimUserEndpoints.updateUser(created, created.getId(), "*", new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        assertEquals(2, updated.getApprovals().size());
    }

    @Test
    void approvalsIsSyncedCorrectlyOnGet() {
        assertEquals(0, scimUserEndpoints.getUser(joel.getId(), new MockHttpServletResponse()).getApprovals().size());

        jdbcApprovalStore.addApproval(new Approval()
                .setUserId(joel.getId())
                .setClientId("c1")
                .setScope("s1")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.APPROVED), identityZone.getId());
        jdbcApprovalStore.addApproval(new Approval()
                .setUserId(joel.getId())
                .setClientId("c1")
                .setScope("s2")
                .setExpiresAt(Approval.timeFromNow(6000))
                .setStatus(Approval.ApprovalStatus.DENIED), identityZone.getId());

        assertEquals(2, scimUserEndpoints.getUser(joel.getId(), new MockHttpServletResponse()).getApprovals().size());
    }

    @Test
    void createUser_whenPasswordIsInvalid_throwsException() {
        doThrow(new InvalidPasswordException("whaddup")).when(mockPasswordValidator).validate(anyString());
        ScimUserProvisioning mockScimUserProvisioning = mock(ScimUserProvisioning.class);
        scimUserEndpoints.setScimUserProvisioning(mockScimUserProvisioning);
        String zoneId = identityZone.getId();
        when(mockScimUserProvisioning.createUser(any(ScimUser.class), anyString(), eq(zoneId))).thenReturn(new ScimUser());

        String userName = "user@example.com";
        ScimUser user = new ScimUser("user1", userName, null, null);
        user.addEmail(userName);
        user.setOrigin(OriginKeys.UAA);
        user.setPassword("some bad password");

        try {
            scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        } catch (InvalidPasswordException e) {
            assertEquals(e.getStatus(), HttpStatus.BAD_REQUEST);
            assertEquals(e.getMessage(), "whaddup");
        }

        verify(mockPasswordValidator).validate("some bad password");
    }

    @Test
    void userWithNoEmailNotAllowed() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        try {
            scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
            fail("Expected InvalidScimResourceException");
        } catch (InvalidScimResourceException e) {
            // expected
            String message = e.getMessage();
            assertTrue("Wrong message: " + message, message.contains("email"));
        }
        int count = jdbcTemplate.queryForObject("select count(*) from users where userName=?", new Object[]{"dave"}, Integer.class);
        assertEquals(0, count);
    }

    @Test
    void create_uaa_user_when_internal_user_management_is_disabled() {
        assertThrows(InternalUserManagementDisabledException.class,
                () -> createUserWhenInternalUserManagementIsDisabled(OriginKeys.UAA));
    }

    @Test
    void create_ldap_user_when_internal_user_management_is_disabled() {
        createUserWhenInternalUserManagementIsDisabled(OriginKeys.LDAP);
    }

    @Test
    void create_with_non_uaa_origin_does_not_validate_password() {
        ScimUser user = spy(new ScimUser(null, "dave", "David", "Syer"));
        user.addEmail(new RandomValueStringGenerator().generate() + "@test.org");
        user.setOrigin("google");
        user.setPassword("bla bla");
        MockHttpServletRequest request = new MockHttpServletRequest();
        scimUserEndpoints.createUser(user, request, new MockHttpServletResponse());
        ArgumentCaptor<String> passwords = ArgumentCaptor.forClass(String.class);
        verify(user, atLeastOnce()).setPassword(passwords.capture());

        //1. this method, 2. user scimUserEndpoints, 3. user provisioning
        assertEquals(3, passwords.getAllValues().size());
        assertEquals("bla bla", passwords.getAllValues().get(0));
        assertEquals("", passwords.getAllValues().get(1));
    }

    @Test
    void handleExceptionWithConstraintViolation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        scimUserEndpoints.setMessageConverters(new HttpMessageConverter<?>[]{new ExceptionReportHttpMessageConverter()});
        View view = scimUserEndpoints.handleException(new DataIntegrityViolationException("foo"), request);
        ConvertingExceptionView converted = (ConvertingExceptionView) view;
        converted.render(Collections.emptyMap(), request, response);
        String body = response.getContentAsString();
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        // System.err.println(body);
        assertTrue("Wrong body: " + body, body.contains("message\":\"foo"));
    }

    @Test
    void handleExceptionWithBadFieldName() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        scimUserEndpoints.setMessageConverters(new HttpMessageConverter<?>[]{new ExceptionReportHttpMessageConverter()});
        View view = scimUserEndpoints.handleException(new HttpMessageConversionException("foo"), request);
        ConvertingExceptionView converted = (ConvertingExceptionView) view;
        converted.render(Collections.emptyMap(), request, response);
        String body = response.getContentAsString();
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        // System.err.println(body);
        assertTrue("Wrong body: " + body, body.contains("message\":\"foo"));
    }

    @Test
    void userCanInitializePassword() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.addEmail("dsyer@vmware.com");
        ReflectionTestUtils.setField(user, "password", "foo");
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        assertNull("A newly created user revealed its password", created.getPassword());
        String password = jdbcTemplate.queryForObject("select password from users where id=?", String.class,
                created.getId());
        assertTrue(passwordEncoder.matches("foo", password));
    }

    @Test
    void deleteIsAllowedWithCorrectVersionInEtag() {
        ScimUser exGuy = new ScimUser(null, "deleteme", "Expendable", "Guy");
        exGuy.addEmail("exguy@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        scimUserEndpoints.deleteUser(exGuy.getId(), Integer.toString(exGuy.getMeta().getVersion()),
                new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void deleteIsAllowedWithQuotedEtag() {
        ScimUser exGuy = new ScimUser(null, "deleteme", "Expendable", "Guy");
        exGuy.addEmail("exguy@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        scimUserEndpoints.deleteUser(exGuy.getId(), "\"*", new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void deleteIs_Not_Allowed_For_UAA_When_InternalUserManagement_Is_Disabled() {
        assertThrows(InternalUserManagementDisabledException.class,
                () -> deleteWhenInternalUserManagementIsDisabled(OriginKeys.UAA));
    }

    @Test
    void deleteIs_Allowed_For_LDAP_When_InternalUserManagement_Is_Disabled() {
        deleteWhenInternalUserManagementIsDisabled(OriginKeys.LDAP);
    }

    @Test
    void deleteIsNotAllowedWithWrongVersionInEtag() {
        ScimUser exGuy = new ScimUser(null, "deleteme2", "Expendable", "Guy");
        exGuy.addEmail("exguy2@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        final String exGuyId = exGuy.getId();
        final ScimMeta exGuyMeta = exGuy.getMeta();
        assertThrows(OptimisticLockingFailureException.class, () ->
                scimUserEndpoints.deleteUser(
                        exGuyId,
                        Integer.toString(exGuyMeta.getVersion() + 1),
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()));
    }

    @Test
    void deleteIsAllowedWithNullEtag() {
        ScimUser exGuy = new ScimUser(null, "deleteme3", "Expendable", "Guy");
        exGuy.addEmail("exguy3@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        scimUserEndpoints.deleteUser(exGuy.getId(), null, new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void deleteUserUpdatesGroupMembership() {
        ScimUser exGuy = new ScimUser(null, "deleteme3", "Expendable", "Guy");
        exGuy.addEmail("exguy3@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());

        ScimGroup g = new ScimGroup(null, "test1", identityZone.getId());
        g.setMembers(Collections.singletonList(new ScimGroupMember(exGuy.getId())));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateGroupMembers(g, exGuy.getId(), true);

        scimUserEndpoints.deleteUser(exGuy.getId(), "*", new MockHttpServletRequest(), new MockHttpServletResponse());
        validateGroupMembers(scimGroupEndpoints.getGroup(g.getId(), new MockHttpServletResponse()), exGuy.getId(), false);
    }

    @Test
    void deleteUserInZoneUpdatesGroupMembership() {
        identityZone.setId("not-uaa");

        ScimUser exGuy = new ScimUser(null, "deleteme3", "Expendable", "Guy");
        exGuy.addEmail("exguy3@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        assertEquals(identityZone.getId(), exGuy.getZoneId());

        ScimGroup g = new ScimGroup(null, "test1", identityZone.getId());
        g.setMembers(Collections.singletonList(new ScimGroupMember(exGuy.getId())));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateGroupMembers(g, exGuy.getId(), true);

        scimUserEndpoints.deleteUser(exGuy.getId(), "*", new MockHttpServletRequest(), new MockHttpServletResponse());
        validateGroupMembers(scimGroupEndpoints.getGroup(g.getId(), new MockHttpServletResponse()), exGuy.getId(), false);
    }

    @Test
    void findAllIds() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "id pr", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
    }

    @Test
    void findGroupsAndApprovals() {
        ScimGroupMembershipManager mockScimGroupMembershipManager = mock(ScimGroupMembershipManager.class);
        scimUserEndpoints.setScimGroupMembershipManager(mockScimGroupMembershipManager);

        ApprovalStore mockApprovalStore = mock(ApprovalStore.class);
        scimUserEndpoints.setApprovalStore(mockApprovalStore);

        String isJoelOrDaleFilter = SCIMFilter.createOrFilter(asList(
                SCIMFilter.createEqualityFilter(AttributePath.parse("id"), joel.getId()),
                SCIMFilter.createEqualityFilter(AttributePath.parse("id"), dale.getId()))).toString();

        SearchResults<?> results = scimUserEndpoints.findUsers("id,groups,approvals", isJoelOrDaleFilter, null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        verify(mockScimGroupMembershipManager).getGroupsWithMember(joel.getId(), false, identityZone.getId());
        verify(mockScimGroupMembershipManager).getGroupsWithMember(joel.getId(), true, identityZone.getId());
        verify(mockScimGroupMembershipManager).getGroupsWithMember(dale.getId(), false, identityZone.getId());
        verify(mockScimGroupMembershipManager).getGroupsWithMember(dale.getId(), true, identityZone.getId());

        verify(mockApprovalStore).getApprovalsForUser(joel.getId(), identityZone.getId());
        verify(mockApprovalStore).getApprovalsForUser(dale.getId(), identityZone.getId());
    }

    @Test
    void findPageOfIds() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "id pr", null, "ascending", 1, 1);
        assertEquals(2, results.getTotalResults());
        assertEquals(1, results.getResources().size());
    }

    @Test
    void findMultiplePagesOfIds() {
        jdbcScimUserProvisioning.setPageSize(1);
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "id pr", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertEquals(2, results.getResources().size());
    }

    @Test
    void findWhenStartGreaterThanTotal() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "id pr", null, "ascending", 3, 100);
        assertEquals(2, results.getTotalResults());
        assertEquals(0, results.getResources().size());
    }

    @Test
    void findAllNames() {
        SearchResults<?> results = scimUserEndpoints.findUsers("userName", "id pr", null, "ascending", 1, 100);
        Collection<Object> values = getSetFromMaps(results.getResources(), "userName");
        assertTrue(values.contains("olds"));
    }

    @Test
    void findAllNamesWithStartIndex() {
        SearchResults<?> results = scimUserEndpoints.findUsers("name", "id pr", null, "ascending", 1, 100);
        assertEquals(2, results.getResources().size());

        results = scimUserEndpoints.findUsers("name", "id pr", null, "ascending", 2, 100);
        assertEquals(1, results.getResources().size());

        results = scimUserEndpoints.findUsers("name", "id pr", null, "ascending", 3, 100);
        assertEquals(0, results.getResources().size());
    }

    @Test
    void findAllEmails() {
        SearchResults<?> results = scimUserEndpoints.findUsers("emails.value", "id pr", null, "ascending", 1, 100);
        Collection<Object> values = getSetFromMaps(results.getResources(), "emails.value");
        assertTrue(values.contains(Collections.singletonList("olds@vmware.com")));
    }

    @Test
    void findAllAttributes() {
        scimUserEndpoints.findUsers("id", "id pr", null, "ascending", 1, 100);
        SearchResults<Map<String, Object>> familyNames = (SearchResults<Map<String, Object>>) scimUserEndpoints.findUsers("familyName", "id pr", "familyName", "ascending", 1, 100);
        SearchResults<Map<String, Object>> givenNames = (SearchResults<Map<String, Object>>) scimUserEndpoints.findUsers("givenName", "id pr", "givenName", "ascending", 1, 100);
        scimUserEndpoints.findUsers("phoneNumbers", "id pr", null, "ascending", 1, 100);
        scimUserEndpoints.findUsers("externalId", "id pr", null, "ascending", 1, 100);
        scimUserEndpoints.findUsers("meta.version", "id pr", null, "ascending", 1, 100);
        scimUserEndpoints.findUsers("meta.created", "id pr", null, "ascending", 1, 100);
        scimUserEndpoints.findUsers("meta.lastModified", "id pr", null, "ascending", 1, 100);
        scimUserEndpoints.findUsers("zoneId", "id pr", null, "ascending", 1, 100);

        assertThat(familyNames.getResources(), hasSize(2));

        Map<String, Object> dSaMap = familyNames.getResources().get(0);
        assertEquals("D'sa", dSaMap.get("familyName"));

        Map<String, Object> oldsMap = familyNames.getResources().get(1);
        assertEquals("Olds", oldsMap.get("familyName"));

        assertThat(givenNames.getResources(), hasSize(2));

        Map<String, Object> daleMap = givenNames.getResources().get(0);
        assertEquals("Dale", daleMap.get("givenName"));

        Map<String, Object> joelMap = givenNames.getResources().get(1);
        assertEquals("Joel", joelMap.get("givenName"));
    }

    @Test
    void findNonExistingAttributes() {
        String nonExistingAttribute = "blabla";
        List<Map<String, Object>> resources = (List<Map<String, Object>>) scimUserEndpoints.findUsers(nonExistingAttribute, "id pr", null, "ascending", 1, 100).getResources();
        for (Map<String, Object> resource : resources) {
            assertNull(resource.get(nonExistingAttribute));
        }
    }

    @Test
    void findUsersGroupsSyncedByDefault() {
        ScimGroupMembershipManager mockgroupMembershipManager = mock(ScimGroupMembershipManager.class);
        scimUserEndpoints.setScimGroupMembershipManager(mockgroupMembershipManager);

        scimUserEndpoints.findUsers("", "id pr", null, "ascending", 1, 100);
        verify(mockgroupMembershipManager, atLeastOnce()).getGroupsWithMember(anyString(), anyBoolean(), eq(identityZone.getId()));
    }

    @Test
    void findUsersGroupsSyncedIfIncluded() {
        ScimGroupMembershipManager mockgroupMembershipManager = mock(ScimGroupMembershipManager.class);
        scimUserEndpoints.setScimGroupMembershipManager(mockgroupMembershipManager);

        scimUserEndpoints.findUsers("groups", "id pr", null, "ascending", 1, 100);
        verify(mockgroupMembershipManager, atLeastOnce()).getGroupsWithMember(anyString(), anyBoolean(), eq(identityZone.getId()));
    }

    @Test
    void findUsersGroupsNotSyncedIfNotIncluded() {
        ScimGroupMembershipManager mockgroupMembershipManager = mock(ScimGroupMembershipManager.class);
        scimUserEndpoints.setScimGroupMembershipManager(mockgroupMembershipManager);

        scimUserEndpoints.findUsers("emails.value", "id pr", null, "ascending", 1, 100);
        verifyZeroInteractions(mockgroupMembershipManager);
    }

    @Test
    void findUsersApprovalsSyncedByDefault() {
        ApprovalStore mockApprovalStore = mock(ApprovalStore.class);
        scimUserEndpoints.setApprovalStore(mockApprovalStore);

        scimUserEndpoints.findUsers("", "id pr", null, "ascending", 1, 100);
        verify(mockApprovalStore, atLeastOnce()).getApprovalsForUser(anyString(), eq(identityZone.getId()));
    }

    @Test
    void findUsersApprovalsSyncedIfIncluded() {
        ApprovalStore mockApprovalStore = mock(ApprovalStore.class);
        scimUserEndpoints.setApprovalStore(mockApprovalStore);

        scimUserEndpoints.findUsers("approvals", "id pr", null, "ascending", 1, 100);
        verify(mockApprovalStore, atLeastOnce()).getApprovalsForUser(anyString(), eq(identityZone.getId()));
    }

    @Test
    void findUsersApprovalsNotSyncedIfNotIncluded() {
        ApprovalStore mockApprovalStore = mock(ApprovalStore.class);
        scimUserEndpoints.setApprovalStore(mockApprovalStore);

        scimUserEndpoints.findUsers("emails.value", "id pr", null, "ascending", 1, 100);
        verifyZeroInteractions(mockApprovalStore);
    }

    @Test
    void whenSettingAnInvalidUserMaxCount_ScimUsersEndpointShouldThrowAnException() {
        assertThrowsWithMessageThat(IllegalArgumentException.class, () -> scimUserEndpoints.setUserMaxCount(0), containsString("Invalid \"userMaxCount\" value (got 0). Should be positive number."));
    }

    @Test
    void whenSettingANegativeValueUserMaxCount_ScimUsersEndpointShouldThrowAnException() {
        assertThrowsWithMessageThat(IllegalArgumentException.class, () -> scimUserEndpoints.setUserMaxCount(-1), containsString("Invalid \"userMaxCount\" value (got -1). Should be positive number."));
    }

    @Test
    void invalidFilterExpression() {
        assertThrowsWithMessageThat(ScimException.class, () -> scimUserEndpoints.findUsers("id", "userName qq 'd'", null, "ascending", 1, 100), containsString("Invalid filter"));
    }

    @Test
    void validFilterExpression() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName eq \"d\"", "created", "ascending", 1, 100);
        assertEquals(0, results.getTotalResults());
    }

    @Test
    void invalidOrderByExpression() {
        assertThrowsWithMessageThat(
                ScimException.class,
                () -> scimUserEndpoints.findUsers("id", "userName eq \"d\"", "created,unknown", "ascending", 1, 100),
                containsString("Invalid filter"));
    }

    @Test
    void cannotOrderBySalt() {
        assertThrowsWithMessageThat(
                ScimException.class,
                () -> scimUserEndpoints.findUsers("id", "", "salt", "ascending", 1, 100),
                containsString("Invalid filter"));
    }

    @Test
    void validOrderByExpression() {
        scimUserEndpoints.findUsers("id", "userName eq \"d\"", "1,created", "ascending", 1, 100);
        scimUserEndpoints.findUsers("id", "userName eq \"d\"", "1,2", "ascending", 1, 100);
        scimUserEndpoints.findUsers("id", "userName eq \"d\"", "username,created", "ascending", 1, 100);
    }

    @SuppressWarnings("unchecked")
    @Test
    void findIdsByUserName() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName eq \"jdsa\"", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertEquals(1, results.getSchemas().size()); // System.err.println(results.getValues());
        assertEquals(joel.getId(), ((Map<String, Object>) results.getResources().iterator().next()).get("id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findIdsByEmailApostrophe() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "emails.value eq \"" + JDSA_VMWARE_COM + "\"", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertEquals(1, results.getSchemas().size()); // System.err.println(results.getValues());
        assertEquals(joel.getId(), ((Map<String, Object>) results.getResources().iterator().next()).get("id"));
    }

    @Test
    void findIdsByUserNameContains() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName co \"d\"", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void findIdsByUserNameStartWith() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName sw \"j\"", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void findIdsByEmailContains() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "emails.value sw \"j\"", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void findIdsByEmailContainsWithEmptyResult() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "emails.value sw \"z\"", null, "ascending", 1, 100);
        assertEquals(0, results.getTotalResults());
    }

    @Test
    void findIdsWithBooleanExpression() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName co \"d\" and id pr", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void findIdsWithBooleanExpressionIvolvingEmails() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id",
                "userName co \"d\" and emails.value co \"vmware\"", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void createIncludesETagHeader() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dave@vmware.com");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimUserEndpoints.createUser(user, new MockHttpServletRequest(), httpServletResponse);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));
    }

    @Test
    void getIncludesETagHeader() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dave@vmware.com");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimUserEndpoints.getUser(joel.getId(), httpServletResponse);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));
    }

    @Test
    void updateIncludesETagHeader() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dave@vmware.com");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimUserEndpoints.updateUser(joel, joel.getId(), "*", new MockHttpServletRequest(), httpServletResponse, null);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));
    }

    @Test
    void updateWhenInternalUserManagementIsDisabledForUaa() {
        assertThrows(InternalUserManagementDisabledException.class,
                () -> updateWhenInternalUserManagementIsDisabled(OriginKeys.UAA));
    }

    @Test
    void updateWhenInternalUserManagementIsDisabledForLdap() {
        updateWhenInternalUserManagementIsDisabled(OriginKeys.LDAP);
    }

    @Test
    void verifyIncludesETagHeader() {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dave@vmware.com");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimUserEndpoints.verifyUser("" + joel.getId(), "*", httpServletResponse);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void legacyTestFindIdsByUserName() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName eq 'jdsa'", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertEquals(1, results.getSchemas().size()); // System.err.println(results.getValues());
        assertEquals(joel.getId(), ((Map<String, Object>) results.getResources().iterator().next()).get("id"));
    }

    @Test
    void legacyTestFindIdsByUserNameContains() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName co 'd'", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void legacyTestFindIdsByUserNameStartWith() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName sw 'j'", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void legacyTestFindIdsByEmailContains() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "emails.value sw 'j'", null, "ascending", 1, 100);
        assertEquals(1, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void legacyTestFindIdsByEmailContainsWithEmptyResult() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "emails.value sw 'z'", null, "ascending", 1, 100);
        assertEquals(0, results.getTotalResults());
    }

    @Test
    void legacyTestFindIdsWithBooleanExpression() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id", "userName co 'd' and id pr", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void legacyTestFindIdsWithBooleanExpressionIvolvingEmails() {
        SearchResults<?> results = scimUserEndpoints.findUsers("id",
                "userName co 'd' and emails.value co 'vmware'", null, "ascending", 1, 100);
        assertEquals(2, results.getTotalResults());
        assertTrue("Couldn't find id: " + results.getResources(), getSetFromMaps(results.getResources(), "id")
                .contains(joel.getId()));
    }

    @Test
    void zeroUsersInADifferentIdentityZone() {
        identityZone.setId("not-uaa");

        SearchResults<?> results = scimUserEndpoints.findUsers("id",
                "id pr", null, "ascending", 1, 100);
        assertEquals(0, results.getTotalResults());
    }

    @Test
    void patchUserNoChange() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        ScimUser patchedUser = scimUserEndpoints.patchUser(createdUser, createdUser.getId(), Integer.toString(user.getVersion()), new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        assertEquals(user.getUserName(), patchedUser.getUserName());
        assertEquals(user.getName().getGivenName(), patchedUser.getName().getGivenName());
        assertEquals(user.getName().getFamilyName(), patchedUser.getName().getFamilyName());
        assertEquals(user.getEmails().size(), patchedUser.getEmails().size());
        assertEquals(user.getPrimaryEmail(), patchedUser.getPrimaryEmail());
        assertEquals(createdUser.getVersion() + 1, patchedUser.getVersion());
    }

    @Test
    void patchUser() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        createdUser.setUserName(null);
        createdUser.getMeta().setAttributes(new String[]{"Name"});
        createdUser.setName(null);
        ScimUser.PhoneNumber number = new ScimUser.PhoneNumber("0123456789");
        createdUser.setPhoneNumbers(Collections.singletonList(number));
        ScimUser.Email email = new ScimUser.Email();
        email.setValue("example@example.org");
        email.setPrimary(true);
        createdUser.setEmails(Collections.singletonList(email));
        ScimUser patchedUser = scimUserEndpoints.patchUser(createdUser, createdUser.getId(), Integer.toString(createdUser.getVersion()), new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        assertEquals(createdUser.getId(), patchedUser.getId());
        assertEquals(user.getUserName(), patchedUser.getUserName());
        assertNull(patchedUser.getName().getFamilyName());
        assertNull(patchedUser.getName().getGivenName());
        assertEquals(1, patchedUser.getPhoneNumbers().size());
        assertEquals("0123456789", patchedUser.getPhoneNumbers().get(0).getValue());
        assertEquals("example@example.org", patchedUser.getPrimaryEmail());
        assertEquals(createdUser.getVersion() + 1, patchedUser.getVersion());
    }

    @Test
    void patchUnknownUserFails() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.addEmail("test@example.org");
        assertThrows(ScimResourceNotFoundException.class,
                () -> scimUserEndpoints.patchUser(
                        user,
                        UUID.randomUUID().toString(),
                        "0",
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        null));
    }

    @Test
    void patchEmpty() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        user = new ScimUser();
        ScimUser patchedUser = scimUserEndpoints.patchUser(user, createdUser.getId(), Integer.toString(createdUser.getVersion()), new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        assertEquals(createdUser.getUserName(), patchedUser.getUserName());
        assertEquals(createdUser.getName().getGivenName(), patchedUser.getName().getGivenName());
        assertEquals(createdUser.getName().getFamilyName(), patchedUser.getName().getFamilyName());
        assertEquals(createdUser.getEmails().size(), patchedUser.getEmails().size());
        assertEquals(createdUser.getPrimaryEmail(), patchedUser.getPrimaryEmail());
        assertEquals(createdUser.getVersion() + 1, patchedUser.getVersion());
    }

    @Test
    void patchDropUnknownAttributeFails() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        createdUser.getMeta().setAttributes(new String[]{"attributeName"});
        assertThrows(InvalidScimResourceException.class, () -> scimUserEndpoints.patchUser(createdUser, createdUser.getId(), Integer.toString(createdUser.getVersion()), new MockHttpServletRequest(), new MockHttpServletResponse(), null));
    }

    @Test
    void patchIncorrectVersionFails() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThrows(ScimResourceConflictException.class, () -> scimUserEndpoints.patchUser(createdUser, createdUser.getId(), Integer.toString(createdUser.getVersion() + 1), new MockHttpServletRequest(), new MockHttpServletResponse(), null));
    }

    @Test
    void patchUserStatus() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        UserAccountStatus userAccountStatus = new UserAccountStatus();
        userAccountStatus.setLocked(false);
        UserAccountStatus updatedStatus = scimUserEndpoints.updateAccountStatus(userAccountStatus, createdUser.getId());
        assertEquals(false, updatedStatus.getLocked());
    }

    @Test
    void patchUserInvalidStatus() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        UserAccountStatus userAccountStatus = new UserAccountStatus();
        userAccountStatus.setLocked(true);
        assertThrows(IllegalArgumentException.class, () -> scimUserEndpoints.updateAccountStatus(userAccountStatus, createdUser.getId()));
    }

    @Test
    void patchUserStatusWithPasswordExpiryFalse() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.setPassword("password");
        user.addEmail("test@example.org");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        UserAccountStatus userAccountStatus = new UserAccountStatus();
        userAccountStatus.setPasswordChangeRequired(false);
        assertThrows(IllegalArgumentException.class, () -> scimUserEndpoints.updateAccountStatus(userAccountStatus, createdUser.getId()));
    }

    @Test
    void patchUserStatusWithPasswordExpiryExternalUser() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.addEmail("test@example.org");
        user.setOrigin("NOT_UAA");
        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        UserAccountStatus userAccountStatus = new UserAccountStatus();
        userAccountStatus.setPasswordChangeRequired(true);
        assertThrows(IllegalArgumentException.class, () -> scimUserEndpoints.updateAccountStatus(userAccountStatus, createdUser.getId()));
    }

    @Test
    void createUserWithEmailDomainNotAllowedForOriginUaa() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.addEmail("test@example.org");
        user.setOrigin("uaa");
        IdentityProvider ldapProvider = new IdentityProvider().setActive(true).setType(OriginKeys.LDAP).setOriginKey(OriginKeys.LDAP).setConfig(new LdapIdentityProviderDefinition());
        ldapProvider.getConfig().setEmailDomain(Collections.singletonList("example.org"));
        IdentityProvider oidcProvider = new IdentityProvider().setActive(true).setType(OriginKeys.OIDC10).setOriginKey("oidc1").setConfig(new OIDCIdentityProviderDefinition());
        oidcProvider.getConfig().setEmailDomain(Collections.singletonList("example.org"));
        when(mockJdbcIdentityProviderProvisioning.retrieveActive(anyString())).thenReturn(asList(ldapProvider, oidcProvider));

        assertThrowsWithMessageThat(ScimException.class, () -> scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse()),
                containsString("The user account is set up for single sign-on. Please use one of these origin(s) : [ldap, oidc1]")
        );
        verify(mockJdbcIdentityProviderProvisioning).retrieveActive(anyString());
    }

    @Test
    void createUserWithEmailDomainAllowedForOriginNotUaa() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.addEmail("test@example.org");
        user.setOrigin("NOT_UAA");
        IdentityProvider ldapProvider = new IdentityProvider().setActive(true).setType(OriginKeys.LDAP).setOriginKey(OriginKeys.LDAP).setConfig(new LdapIdentityProviderDefinition());
        ldapProvider.getConfig().setEmailDomain(Collections.singletonList("example.org"));
        when(mockJdbcIdentityProviderProvisioning.retrieveActive(anyString())).thenReturn(Collections.singletonList(ldapProvider));

        scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        verify(mockJdbcIdentityProviderProvisioning, times(0)).retrieveActive(anyString());
    }

    @Test
    void whenEmailDomainConfiguredForUaaAllowsCreationOfUser() {
        ScimUser user = new ScimUser(null, "uname", "gname", "fname");
        user.addEmail("test@example.org");
        user.setPassword("password");
        user.setOrigin("uaa");
        IdentityProvider uaaProvider = new IdentityProvider().setActive(true).setType(OriginKeys.UAA).setOriginKey(OriginKeys.UAA).setConfig(new UaaIdentityProviderDefinition());
        uaaProvider.getConfig().setEmailDomain(Collections.singletonList("example.org"));
        when(mockJdbcIdentityProviderProvisioning.retrieveActive(anyString())).thenReturn(Collections.singletonList(uaaProvider));

        scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void userWithNoOriginGetsDefaultUaa() {
        ScimUser user = new ScimUser("user1", "joeseph", "Jo", "User");
        user.addEmail("jo@blah.com");
        user.setPassword("password");
        user.setOrigin("");

        ScimUser createdUser = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals(OriginKeys.UAA, createdUser.getOrigin());
    }

    @Test
    void deleteMfaRegistration() {
        identityZone.getConfig().setMfaConfig(new MfaConfig().setEnabled(true).setProviderName("mfaProvider"));
        scimUserEndpoints.deleteMfaRegistration(dale.getId());

        verify(mockJdbcUserGoogleMfaCredentialsProvisioning).delete(dale.getId());
    }

    @Test
    void deleteMfaRegistrationUserDoesNotExist() {
        assertThrows(ScimResourceNotFoundException.class, () -> scimUserEndpoints.deleteMfaRegistration("invalidUserId"));
    }

    @Test
    void deleteMfaRegistrationNoMfaConfigured() {
        identityZone.getConfig().setMfaConfig(new MfaConfig().setEnabled(true).setProviderName("mfaProvider"));
        scimUserEndpoints.deleteMfaRegistration(dale.getId());
    }

    @Test
    void deleteMfaRegistrationMfaNotEnabledInZone() {
        identityZone.getConfig().setMfaConfig(new MfaConfig().setEnabled(false));

        scimUserEndpoints.deleteMfaRegistration(dale.getId());
    }

    private void validatePasswordForUaaOriginOnly(VerificationMode verificationMode, String origin, String expectedPassword) {
        ScimUser user = new ScimUser(null, generator.generate(), "GivenName", "FamilyName");
        user.setOrigin(origin);
        user.setPassword("password");
        user.setPrimaryEmail(user.getUserName() + "@test.org");
        ScimUser created = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());
        assertNotNull(created);
        verify(mockPasswordValidator, verificationMode).validate("password");
        jdbcTemplate.query("select password from users where id=?",
                rs -> {
                    assertTrue(passwordEncoder.matches(expectedPassword, rs.getString(1)));
                },
                created.getId());
    }

    private void validateUserGroups(ScimUser user, String... gnm) {
        Set<String> expectedAuthorities = new HashSet<>(asList(gnm));
        expectedAuthorities.add("uaa.user");
        assertNotNull(user.getGroups());
        Logger logger = LoggerFactory.getLogger(getClass());
        logger.debug("user's groups: " + user.getGroups() + ", expecting: " + expectedAuthorities);
        assertEquals(expectedAuthorities.size(), user.getGroups().size());
        for (ScimUser.Group g : user.getGroups()) {
            assertTrue(expectedAuthorities.contains(g.getDisplay()));
        }
    }

    private void createUserWhenInternalUserManagementIsDisabled(String origin) {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.addEmail(new RandomValueStringGenerator().generate() + "@test.org");
        user.setOrigin(origin);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DisableInternalUserManagementFilter.DISABLE_INTERNAL_USER_MANAGEMENT, true);
        scimUserEndpoints.createUser(user, request, new MockHttpServletResponse());
    }

    private void deleteWhenInternalUserManagementIsDisabled(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DisableInternalUserManagementFilter.DISABLE_INTERNAL_USER_MANAGEMENT, true);
        ScimUser exGuy = new ScimUser(null, "deleteme", "Expendable", "Guy");
        exGuy.setOrigin(origin);
        exGuy.addEmail("exguy@imonlyheretobedeleted.com");
        exGuy = jdbcScimUserProvisioning.createUser(exGuy, "exguyspassword", identityZone.getId());
        scimUserEndpoints.deleteUser(exGuy.getId(), "\"*", request, new MockHttpServletResponse());
    }

    private void validateGroupMembers(ScimGroup g, String mId, boolean expected) {
        boolean isMember = false;
        for (ScimGroupMember m : g.getMembers()) {
            if (mId.equals(m.getMemberId())) {
                isMember = true;
                break;
            }
        }
        assertEquals(expected, isMember);
    }

    private void updateWhenInternalUserManagementIsDisabled(String origin) {
        ScimUser user = new ScimUser(null, "dave", "David", "Syer");
        user.setPassword("password");
        user.addEmail("dave@vmware.com");
        user.setOrigin(origin);

        user = scimUserEndpoints.createUser(user, new MockHttpServletRequest(), new MockHttpServletResponse());

        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DisableInternalUserManagementFilter.DISABLE_INTERNAL_USER_MANAGEMENT, true);
        scimUserEndpoints.updateUser(user, user.getId(), "*", request, httpServletResponse, null);
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> getSetFromMaps(Collection<?> resources, String key) {
        Collection<Object> result = new ArrayList<>();
        for (Object map : resources) {
            result.add(((Map<String, Object>) map).get(key));
        }
        return result;
    }

}
