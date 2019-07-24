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

import org.apache.tomcat.jdbc.pool.DataSource;
import org.cloudfoundry.identity.uaa.db.DataSourceAccessor;
import org.flywaydb.core.Flyway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * @author Haytham Mohamed
 **/

@Configuration
public class DataSourceConfig {


	@Bean(destroyMethod = "close")
	public DataSource dataSource(UAADataSourceProperties properties) {
		DataSource ds = new DataSource();
		ds.setDriverClassName(properties.getDriverClassName());
		ds.setUrl(properties.getUrl());
		ds.setUsername(properties.getUsername());
		ds.setPassword(properties.getPassword());
		ds.setJdbcInterceptors(properties.getJdbcInterceptors());
		ds.setValidationInterval(properties.getValidationInterval());
		ds.setValidationQuery(properties.getValidationQuery());
		ds.setTestOnBorrow(properties.isTestOnBorrow());
		ds.setMinIdle(properties.getMinIdle());
		ds.setMaxActive(properties.getMaxActive());
		ds.setMaxIdle(properties.getMaxIde());
		ds.setInitialSize(properties.getInitialSize());
		ds.setValidationQueryTimeout(properties.getValidationQueryTimeout());
		ds.setRemoveAbandoned(properties.isRemoveAbandoned());
		ds.setLogAbandoned(properties.isLogAbandoned());
		ds.setRemoveAbandonedTimeout(properties.getRemoveAbandonedTimeout());
		ds.setTimeBetweenEvictionRunsMillis(properties.getTimeBetweenEvictionRunsMillis());
		return ds;
	}

	@Bean
	public DataSourceAccessor dataSourceAccessor(DataSource dataSource) {
		DataSourceAccessor dsa = new DataSourceAccessor();
		dsa.setDataSource(dataSource);
		return dsa;
	}

	@Bean
	public DataSourceTransactionManager transactionManager(DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	@ConditionalOnBean(Flyway.class)
	@Bean
	public JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate();
	}

	@Bean
	public Boolean useCaseInsensitiveQueries(@Value("${database.caseinsensitive:true}") Boolean dbcase,
			@Value("${spring.datasource.platform}") String platform) {
		return platform.equals("mysql") && dbcase ? true : dbcase;
	}

}
