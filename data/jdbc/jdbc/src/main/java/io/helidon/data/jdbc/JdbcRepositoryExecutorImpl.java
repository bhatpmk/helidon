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

package io.helidon.data.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.data.NonUniqueResultException;
import io.helidon.data.NoResultException;

/**
 * JDBC implementation of the generated repository executor contract.
 * <p>
 * The current POC uses one method-scoped connection per repository call. That is enough to prove direct SQL
 * repository execution and resource cleanup. A production implementation should keep this public contract but
 * route connection acquisition through a transaction-aware context when Helidon transaction support is added.
 */
final class JdbcRepositoryExecutorImpl implements JdbcRepositoryExecutor {

    private final String persistenceUnitName;
    private final DataSource dataSource;
    private final JdbcParametersConfig parametersConfig;

    JdbcRepositoryExecutorImpl(String persistenceUnitName,
                               DataSource dataSource,
                               JdbcParametersConfig parametersConfig) {
        this.persistenceUnitName = persistenceUnitName;
        this.dataSource = dataSource;
        this.parametersConfig = parametersConfig;
    }

    @Override
    public <T> List<T> queryList(String sql, Class<T> resultType, JdbcParameters parameters) {
        // Resource ownership is centralized here so generated repositories do not need try-with-resources blocks.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, parameters);
                ResultSet resultSet = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(JdbcRowMapper.map(resultSet, resultType));
            }
            return List.copyOf(rows);
        } catch (SQLException e) {
            throw sqlException("Query execution failed", sql, e);
        }
    }

    @Override
    public <T> Optional<T> queryOptional(String sql, Class<T> resultType, JdbcParameters parameters) {
        List<T> rows = queryList(sql, resultType, parameters);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC query returned more than one row.");
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public <T> T queryOne(String sql, Class<T> resultType, JdbcParameters parameters) {
        return queryOptional(sql, resultType, parameters)
                .orElseThrow(() -> new NoResultException("JDBC query returned no rows."));
    }

    @Override
    public long update(String sql, JdbcParameters parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, parameters)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw sqlException("DML execution failed", sql, e);
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, JdbcParameters parameters) throws SQLException {
        // The planner and binder are adapted from DbClient JDBC but kept package-private in Data JDBC.
        // Production code should move plan creation to code generation so runtime only prepares and binds.
        JdbcStatementPlan plan = JdbcStatementPlan.create(sql);
        PreparedStatement statement = connection.prepareStatement(plan.jdbcSql());
        for (int i = 0; i < plan.parameterNames().size(); i++) {
            JdbcParameterBinder.bind(statement, i + 1, parameters.value(plan.parameterNames().get(i)), parametersConfig);
        }
        return statement;
    }

    private DataException sqlException(String message, String sql, SQLException cause) {
        return new DataException(message
                                         + " for JDBC persistence unit \""
                                         + persistenceUnitName
                                         + "\" using SQL: "
                                         + sql,
                                 cause);
    }
}
