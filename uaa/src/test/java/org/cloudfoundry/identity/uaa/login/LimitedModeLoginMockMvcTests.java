package org.cloudfoundry.identity.uaa.login;

import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.web.LimitedModeUaaFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;

import static org.junit.Assert.assertTrue;

class LimitedModeLoginMockMvcTests extends LoginMockMvcTests {
    private File originalLimitedModeStatusFile;

    @BeforeEach
    void setUpLimitedModeLoginMockMvcTests(
            @Autowired WebApplicationContext webApplicationContext,
            @Autowired LimitedModeUaaFilter limitedModeUaaFilter
    ) throws Exception {
        originalLimitedModeStatusFile = MockMvcUtils.getLimitedModeStatusFile(webApplicationContext);
        MockMvcUtils.setLimitedModeStatusFile(webApplicationContext);

        assertTrue(limitedModeUaaFilter.isEnabled());
    }

    @AfterEach
    void tearDownLimitedModeLoginMockMvcTests(
            @Autowired WebApplicationContext webApplicationContext
    ) throws Exception {
        MockMvcUtils.resetLimitedModeStatusFile(webApplicationContext, originalLimitedModeStatusFile);
    }

}
