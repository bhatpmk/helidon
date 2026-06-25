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

    private static final boolean TRACE = Boolean.getBoolean("helidon.data.jdbc.trace");

    private final JdbcConnectionProvider connectionProvider;
    private final JdbcStatementExecutor statementExecutor;
    private final JdbcResultReader resultReader;
    private final JdbcExceptionTranslator exceptionTranslator;

    JdbcKernel(JdbcConnectionProvider connectionProvider,
               JdbcStatementExecutor statementExecutor,
               JdbcResultReader resultReader,
               JdbcExceptionTranslator exceptionTranslator) {
        trace(">>> ENTER JdbcKernel.<init>(connectionProvider=" + connectionProvider
                      + ", statementExecutor=" + statementExecutor
                      + ", resultReader=" + resultReader
                      + ", exceptionTranslator=" + exceptionTranslator + ")");
        this.connectionProvider = requireNonNull(connectionProvider, "connectionProvider");
        this.statementExecutor = requireNonNull(statementExecutor, "statementExecutor");
        this.resultReader = requireNonNull(resultReader, "resultReader");
        this.exceptionTranslator = requireNonNull(exceptionTranslator, "exceptionTranslator");
        trace("<<< EXIT  JdbcKernel.<init>()");
    }

    static JdbcKernel create(DataSource dataSource) {
        trace(">>> ENTER JdbcKernel.create(dataSource=" + dataSource + ")");
        JdbcKernel result = new JdbcKernel(new JdbcDataSourceConnectionProvider(dataSource),
                                           new JdbcStatementExecutor(),
                                           new JdbcResultReader(),
                                           new JdbcExceptionTranslator());
        trace("<<< EXIT  JdbcKernel.create() result=" + result);
        return result;
    }

    long update(JdbcStatementPlan plan, JdbcBinder binder) {
        trace(">>> ENTER JdbcKernel.update(plan=" + plan + ", binder=" + binder + ")");
        requireKind(plan, JdbcStatementKind.UPDATE, "JDBC update failed.");
        requireNonNull(binder, "binder");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            long result = statement.executeLargeUpdate();
            trace("<<< EXIT  JdbcKernel.update() result=" + result);
            return result;
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.update() exception=" + e);
            throw exceptionTranslator.translate("JDBC update failed.", e);
        }
    }

    <T> List<T> list(JdbcStatementPlan plan,
                     JdbcBinder binder,
                     JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcKernel.list(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> result = resultReader.list(resultSet, mapper);
                trace("<<< EXIT  JdbcKernel.list() resultSize=" + result.size());
                return result;
            }
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.list() exception=" + e);
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> List<T> listReduced(JdbcStatementPlan plan,
                            JdbcBinder binder,
                            JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcKernel.listReduced(plan=" + plan + ", binder=" + binder
                      + ", reducer=" + reducer + ")");
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(reducer, "reducer");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> result = resultReader.list(resultSet, reducer);
                trace("<<< EXIT  JdbcKernel.listReduced() resultSize=" + result.size());
                return result;
            }
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.listReduced() exception=" + e);
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> Optional<T> optional(JdbcStatementPlan plan,
                            JdbcBinder binder,
                            JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcKernel.optional(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                Optional<T> result = resultReader.optional(resultSet, mapper, "JDBC query returned more than one row.");
                trace("<<< EXIT  JdbcKernel.optional() present=" + result.isPresent());
                return result;
            }
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.optional() exception=" + e);
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> Optional<T> optionalReduced(JdbcStatementPlan plan,
                                   JdbcBinder binder,
                                   JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcKernel.optionalReduced(plan=" + plan + ", binder=" + binder
                      + ", reducer=" + reducer + ")");
        requireKind(plan, JdbcStatementKind.QUERY, "JDBC query failed.");
        requireNonNull(binder, "binder");
        requireNonNull(reducer, "reducer");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            try (ResultSet resultSet = statement.executeQuery()) {
                Optional<T> result = resultReader.optional(resultSet,
                                                           reducer,
                                                           "JDBC query returned more than one aggregate.");
                trace("<<< EXIT  JdbcKernel.optionalReduced() present=" + result.isPresent());
                return result;
            }
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.optionalReduced() exception=" + e);
            throw exceptionTranslator.translate("JDBC query failed.", e);
        }
    }

    <T> T one(JdbcStatementPlan plan,
              JdbcBinder binder,
              JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcKernel.one(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        T result = optional(plan, binder, mapper)
                .orElseThrow(() -> new DataException("JDBC query returned no rows."));
        trace("<<< EXIT  JdbcKernel.one() result=" + result);
        return result;
    }

    <T> T oneReduced(JdbcStatementPlan plan,
                     JdbcBinder binder,
                     JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcKernel.oneReduced(plan=" + plan + ", binder=" + binder + ", reducer=" + reducer + ")");
        T result = optionalReduced(plan, binder, reducer)
                .orElseThrow(() -> new DataException("JDBC query returned no aggregates."));
        trace("<<< EXIT  JdbcKernel.oneReduced() result=" + result);
        return result;
    }

    <T> Optional<T> generatedKey(JdbcStatementPlan plan,
                                 JdbcBinder binder,
                                 JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcKernel.generatedKey(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        requireKind(plan, JdbcStatementKind.UPDATE, "JDBC generated-key update failed.");
        requireGeneratedKeys(plan);
        requireNonNull(binder, "binder");
        requireNonNull(mapper, "mapper");
        try (Connection connection = connectionProvider.connection();
             PreparedStatement statement = statementExecutor.prepare(connection, plan)) {
            statementExecutor.bind(statement, binder);
            statement.executeLargeUpdate();
            try (ResultSet resultSet = statement.getGeneratedKeys()) {
                Optional<T> result = resultReader.optional(resultSet,
                                                           mapper,
                                                           "JDBC update returned more than one generated-key row.");
                trace("<<< EXIT  JdbcKernel.generatedKey() present=" + result.isPresent());
                return result;
            }
        } catch (SQLException e) {
            trace("xxx FAIL  JdbcKernel.generatedKey() exception=" + e);
            throw exceptionTranslator.translate("JDBC generated-key update failed.", e);
        }
    }

    void executePlaceholder(JdbcStatementPlan plan) {
        trace(">>> ENTER JdbcKernel.executePlaceholder(plan=" + plan + ")");
        requireNonNull(plan, "plan");
        throw unsupported(plan.kind());
    }

    void executePlaceholder(JdbcMethodPlan plan) {
        trace(">>> ENTER JdbcKernel.executePlaceholder(plan=" + plan + ")");
        requireNonNull(plan, "plan");
        throw new DataException("""
                JDBC multi-statement repository methods are reserved for a future method plan. The kernel extension \
                should execute the ordered statement plans in the method plan and reduce their result sets, update \
                counts, and generated keys into the declared method result.
                """);
    }

    private void requireKind(JdbcStatementPlan plan, JdbcStatementKind expected, String failureMessage) {
        trace(">>> ENTER JdbcKernel.requireKind(plan=" + plan + ", expected=" + expected
                      + ", failureMessage=" + failureMessage + ")");
        requireNonNull(plan, "plan");
        if (plan.kind().future()) {
            throw unsupported(plan.kind());
        }
        if (plan.kind() != expected) {
            throw new DataException(failureMessage);
        }
        trace("<<< EXIT  JdbcKernel.requireKind()");
    }

    private void requireGeneratedKeys(JdbcStatementPlan plan) {
        trace(">>> ENTER JdbcKernel.requireGeneratedKeys(plan=" + plan + ")");
        if (!plan.generatedKeys().requested()) {
            throw new DataException("JDBC generated-key update requires a generated-keys statement plan.");
        }
        trace("<<< EXIT  JdbcKernel.requireGeneratedKeys()");
    }

    private DataException unsupported(JdbcStatementKind kind) {
        trace(">>> ENTER JdbcKernel.unsupported(kind=" + kind + ")");
        DataException result = switch (kind) {
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
        trace("<<< EXIT  JdbcKernel.unsupported() result=" + result.getMessage());
        return result;
    }

    private static void trace(String message) {
        if (TRACE) {
            System.out.println("\n+++++ JDBC TRACE [JdbcKernel] " + message + "\n");
        }
    }

}
