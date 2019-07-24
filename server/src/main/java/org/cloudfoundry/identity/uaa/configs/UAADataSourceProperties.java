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

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Haytham Mohamed
 **/


@ConfigurationProperties(prefix = "spring.datasource")
public class UAADataSourceProperties extends DataSourceProperties {

	private int validationInterval;
	private String validationQuery;
	private boolean testOnBorrow;
	private int minIdle;
	private int maxIde;
	private int maxActive;
	private int initialSize;
	private int validationQueryTimeout;
	private boolean removeAbandoned;
	private boolean logAbandoned;
	private int removeAbandonedTimeout;
	private int timeBetweenEvictionRunsMillis;
	private String jdbcInterceptors;

	int getValidationInterval() {
		return validationInterval;
	}

	void setValidationInterval(int validationInterval) {
		this.validationInterval = validationInterval;
	}

	String getValidationQuery() {
		return validationQuery;
	}

	void setValidationQuery(String validationQuery) {
		this.validationQuery = validationQuery;
	}

	boolean isTestOnBorrow() {
		return testOnBorrow;
	}

	void setTestOnBorrow(boolean testOnBorrow) {
		this.testOnBorrow = testOnBorrow;
	}

	int getMinIdle() {
		return minIdle;
	}

	void setMinIdle(int minIdle) {
		this.minIdle = minIdle;
	}

	int getMaxIde() {
		return maxIde;
	}

	void setMaxIde(int maxIde) {
		this.maxIde = maxIde;
	}

	int getMaxActive() {
		return maxActive;
	}

	void setMaxActive(int maxActive) {
		this.maxActive = maxActive;
	}

	int getInitialSize() {
		return initialSize;
	}

	void setInitialSize(int initialSize) {
		this.initialSize = initialSize;
	}

	int getValidationQueryTimeout() {
		return validationQueryTimeout;
	}

	void setValidationQueryTimeout(int validationQueryTimeout) {
		this.validationQueryTimeout = validationQueryTimeout;
	}

	boolean isRemoveAbandoned() {
		return removeAbandoned;
	}

	void setRemoveAbandoned(boolean removeAbandoned) {
		this.removeAbandoned = removeAbandoned;
	}

	boolean isLogAbandoned() {
		return logAbandoned;
	}

	void setLogAbandoned(boolean logAbandoned) {
		this.logAbandoned = logAbandoned;
	}

	int getRemoveAbandonedTimeout() {
		return removeAbandonedTimeout;
	}

	void setRemoveAbandonedTimeout(int removeAbandonedTimeout) {
		this.removeAbandonedTimeout = removeAbandonedTimeout;
	}

	int getTimeBetweenEvictionRunsMillis() {
		return timeBetweenEvictionRunsMillis;
	}

	void setTimeBetweenEvictionRunsMillis(int timeBetweenEvictionRunsMillis) {
		this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
	}

	String getJdbcInterceptors() {
		return jdbcInterceptors;
	}

	void setJdbcInterceptors(String jdbcInterceptors) {
		this.jdbcInterceptors = jdbcInterceptors;
	}
}
