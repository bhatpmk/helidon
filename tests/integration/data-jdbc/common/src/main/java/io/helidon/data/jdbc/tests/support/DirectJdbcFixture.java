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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.helidon.config.Config;
import io.helidon.service.registry.Service;

/**
 * Executes detached direct-JDBC controls against the configured integration database.
 */
@Service.Singleton
public final class DirectJdbcFixture {
    private static final String CONNECTION_PREFIX = "data.clients.jdbc.0.connection.";

    private final String url;
    private final String username;
    private final String password;

    /**
     * Creates the direct-JDBC control fixture.
     *
     * @param config active integration-test configuration
     */
    @Service.Inject
    DirectJdbcFixture(Config config) {
        url = config.get(CONNECTION_PREFIX + "url").asString().get();
        username = config.get(CONNECTION_PREFIX + "username").asString().orElse("");
        password = config.get(CONNECTION_PREFIX + "password").asString().orElse("");
    }

    /**
     * Executes SQL through the database driver and returns detached outcome metadata.
     *
     * @param sql SQL text
     * @return execution outcome
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public SqlOutcome execute(String sql) {
        Objects.requireNonNull(sql, "The direct JDBC SQL must not be null.");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.execute();
            return new SqlOutcome(true, Optional.empty(), 0);
        } catch (SQLException e) {
            return new SqlOutcome(false, Optional.ofNullable(e.getSQLState()), e.getErrorCode());
        }
    }

    /**
     * Reads direct-driver column labels and physical names without retaining JDBC resources.
     *
     * @param sql query text
     * @return detached metadata outcome
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public MetadataOutcome metadata(String sql) {
        Objects.requireNonNull(sql, "The direct JDBC metadata SQL must not be null.");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<String> labels = new ArrayList<>(metadata.getColumnCount());
            List<String> names = new ArrayList<>(metadata.getColumnCount());
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                labels.add(metadata.getColumnLabel(index));
                names.add(metadata.getColumnName(index));
            }
            return new MetadataOutcome(true,
                                       Collections.unmodifiableList(labels),
                                       Collections.unmodifiableList(names),
                                       Optional.empty(),
                                       0);
        } catch (SQLException e) {
            return new MetadataOutcome(false,
                                       List.of(),
                                       List.of(),
                                       Optional.ofNullable(e.getSQLState()),
                                       e.getErrorCode());
        }
    }

    /**
     * Detached direct-JDBC execution result.
     *
     * @param successful whether execution succeeded
     * @param sqlState driver SQLSTATE when execution failed
     * @param vendorCode driver vendor code, or zero when unavailable
     */
    public record SqlOutcome(boolean successful, Optional<String> sqlState, int vendorCode) {
    }

    /**
     * Detached direct-JDBC result metadata or failure metadata.
     *
     * @param successful whether the query executed
     * @param labels driver column labels
     * @param names driver physical column names
     * @param sqlState driver SQLSTATE when execution failed
     * @param vendorCode driver vendor code, or zero when unavailable
     */
    public record MetadataOutcome(boolean successful,
                                  List<String> labels,
                                  List<String> names,
                                  Optional<String> sqlState,
                                  int vendorCode) {
    }
}
