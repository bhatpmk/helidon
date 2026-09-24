/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.data.jdbc.tests.support;

import java.util.Map;
import java.util.Objects;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Creates bounded external-database pools and matching registry client configuration for tests.
 */
public final class ExternalPoolSupport {
    private static final String CONNECTION_PREFIX = "data.clients.jdbc.0.connection.";

    private ExternalPoolSupport() {
    }

    /**
     * Creates a one-connection pool from an existing external database configuration.
     *
     * @param databaseConfig external database connection configuration
     * @return one-connection pool
     * @throws NullPointerException if {@code databaseConfig} is {@code null}
     */
    public static HikariDataSource pool(Config databaseConfig) {
        Objects.requireNonNull(databaseConfig, "The external database configuration must not be null.");
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(databaseConfig.get(CONNECTION_PREFIX + "url").asString().get());
        poolConfig.setUsername(databaseConfig.get(CONNECTION_PREFIX + "username").asString().orElse(""));
        poolConfig.setPassword(databaseConfig.get(CONNECTION_PREFIX + "password").asString().orElse(""));
        poolConfig.setDriverClassName(databaseConfig.get(CONNECTION_PREFIX + "jdbc-driver-class-name")
                                              .asString()
                                              .get());
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setConnectionTimeout(2_000);
        return new HikariDataSource(poolConfig);
    }

    /**
     * Creates configuration for a default JDBC client backed by a named test datasource.
     *
     * @param dataSourceName published datasource name
     * @return JDBC client configuration
     * @throws NullPointerException if {@code dataSourceName} is {@code null}
     */
    public static Config clientConfig(String dataSourceName) {
        Objects.requireNonNull(dataSourceName, "The test data source name must not be null.");
        return Config.just(ConfigSources.create(Map.of(
                "data.clients.jdbc.0.name", "@default",
                "data.clients.jdbc.0.data-source", dataSourceName)));
    }
}
