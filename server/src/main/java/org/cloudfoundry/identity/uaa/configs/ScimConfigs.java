/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cloudfoundry.identity.uaa.configs;

import java.util.HashMap;
import java.util.Map;

import org.cloudfoundry.identity.uaa.resources.SimpleAttributeNameMapper;
import org.cloudfoundry.identity.uaa.resources.jdbc.SimpleSearchQueryConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Haytham Mohamed
 **/

@Configuration
public class ScimConfigs {

	@Bean
	public SimpleAttributeNameMapper simpleAttributeNameMapper() {
		Map<String, String> map = new HashMap<>();
		map.put("emails.value", "email");
		map.put("groups.display", "authorities");
		map.put("phoneNumbers.value", "phoneNumber");
		return new SimpleAttributeNameMapper(map);
	}

	@Bean
	public SimpleSearchQueryConverter scimUserQueryConverter(SimpleAttributeNameMapper mapper,
			Boolean useCaseInsensitiveQueries) {
		SimpleSearchQueryConverter simpleSearchQueryConverter = new SimpleSearchQueryConverter();
		simpleSearchQueryConverter.setAttributeNameMapper(mapper);
		simpleSearchQueryConverter.setDbCaseInsensitive(useCaseInsensitiveQueries);
		return null;
	}
}
