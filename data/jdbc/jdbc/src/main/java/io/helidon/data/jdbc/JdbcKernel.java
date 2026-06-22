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
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.data.DataException;

import static java.util.Objects.requireNonNull;

final class JdbcKernel {

    private final JdbcConnectionProvider connectionProvider;
    private final JdbcStatementExecutor statementExecutor;
    private final JdbcResultReader resultReader;
    private final JdbcExceptionTranslator exceptionTranslator;

    JdbcKernel(JdbcConnectionProvider connectionProvider,
               JdbcStatementExecutor statementExecutor,
               JdbcResultReader resultReader,
               JdbcExceptionTranslator exceptionTranslator) {
        this.connectionProvider = requireNonNull(connectionProvider, "connectionProvider");
        this.statementExecutor = requireNonNull(statementExecutor, "statementExecutor");
        this.resultReader = requireNonNull(resultReader, "resultReader");
        this.exceptionTranslator = requireNonNull(exceptionTranslator, "exceptionTranslator");
    }

    static JdbcKernel create(DataSource dataSource) {
        return new JdbcKernel(new JdbcDataSourceConnectionProvider(dataSource),
                              new JdbcStatementExecutor(),
                              new JdbcResultReader(),
                              new JdbcExceptionTranslator());
    }

    long update(JdbcStatementPlan plan, JdbcBinder binder) {
        requireKind(plan, JdbcStatementKind.UPDATE, "JDBC update failed.");
        requireNonNull(binder, "binder");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            return statement.executeLargeUpdate();
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC update failed.", e);
        }
    }

    <T> List<T> list(JdbcStatementPlan plan,
                     JdbcBinder binder,
                     JdbcRowMapper<? extends T> mapper) {
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultReader.list(resultSet, mapper);
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> List<T> listReduced(JdbcStatementPlan plan,
                            JdbcBinder binder,
                            JdbcRowReducer<T> reducer) {
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(reducer, "reducer");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultReader.list(resultSet, reducer);
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> Optional<T> optional(JdbcStatementPlan plan,
                            JdbcBinder binder,
                            JdbcRowMapper<? extends T> mapper) {
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultReader.optional(resultSet, mapper, "JDBC query returned more than one row.");
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> Optional<T> optionalReduced(JdbcStatementPlan plan,
                                   JdbcBinder binder,
                                   JdbcRowReducer<T> reducer) {
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(reducer, "reducer");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultReader.optional(resultSet, reducer, "JDBC query returned more than one aggregate.");
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> T one(JdbcStatementPlan plan,
              JdbcBinder binder,
              JdbcRowMapper<? extends T> mapper) {
        return optional(plan, binder, mapper)
                .orElseThrow(() -> new DataException("JDBC query returned no rows."));
    }

    <T> T oneReduced(JdbcStatementPlan plan,
                     JdbcBinder binder,
                     JdbcRowReducer<T> reducer) {
        return optionalReduced(plan, binder, reducer)
                .orElseThrow(() -> new DataException("JDBC query returned no aggregates."));
    }

    <T> Optional<T> generatedKey(JdbcStatementPlan plan,
                                 JdbcBinder binder,
                                 JdbcRowMapper<? extends T> mapper) {
        requireKind(plan, JdbcStatementKind.UPDATE, "JDBC generated-key update failed.");
        requireGeneratedKeys(plan);
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            statement.executeLargeUpdate();
            try (ResultSet resultSet = statement.getGeneratedKeys()) {
                return resultReader.optional(resultSet,
                                             mapper,
                                             "JDBC update returned more than one generated-key row.");
            }
        } catch (SQLException e) {
            throw exceptionTranslator.translate("JDBC generated-key update failed.", e);
        }
    }

    void executePlaceholder(JdbcStatementPlan plan) {
        requireNonNull(plan, "plan");
        throw unsupported(plan.kind());
    }

    void executePlaceholder(JdbcMethodPlan plan) {
        requireNonNull(plan, "plan");
        throw new DataException("""
                JDBC multi-statement repository methods are reserved for a future method plan. The kernel extension \
                should execute the ordered statement plans in the method plan and reduce their result sets, update \
                counts, and generated keys into the declared method result.
                """);
    }

    private void requireKind(JdbcStatementPlan plan, JdbcStatementKind expected, String failureMessage) {
        requireNonNull(plan, "plan");
        if (plan.kind().future()) {
            throw unsupported(plan.kind());
        }
        if (plan.kind() != expected) {
            throw new DataException(failureMessage);
        }
    }

    private void requireGeneratedKeys(JdbcStatementPlan plan) {
        if (!plan.generatedKeys().requested()) {
            throw new DataException("JDBC generated-key update requires a generated-keys statement plan.");
        }
    }

    private DataException unsupported(JdbcStatementKind kind) {
        return switch (kind) {
        case CALL -> new DataException("""
                JDBC stored procedure execution is reserved for a future callable plan. The kernel extension should \
                prepare a CallableStatement, bind IN parameters, register OUT parameters, execute it, and expose \
                result sets, update counts, generated keys, and OUT values through JdbcResults.
                """);
        case BATCH -> new DataException("""
                JDBC batch execution is reserved for a future batch plan. The kernel extension should bind each \
                item, call addBatch, execute the batch, and expose per-item counts and generated keys through \
                JdbcBatchExecutionResults.
                """);
        case QUERY, UPDATE -> new DataException("Unsupported JDBC statement kind: " + kind);
        };
    }

}
