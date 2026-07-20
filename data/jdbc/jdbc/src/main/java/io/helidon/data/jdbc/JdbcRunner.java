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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.sql.DataSource;

import io.helidon.data.DataException;

/**
 * Package-private lifecycle orchestrator for prepared JDBC operations.
 *
 * <p>Generated declarative repositories reach this class only through the
 * public {@link JdbcClient} stages. Keeping lease acquisition, preparation,
 * option application, binding, warning handling, exception translation, and
 * cleanup here prevents execution-specific handlers from developing different
 * JDBC ownership semantics.</p>
 *
 * <p>{@link JdbcQueryHandler} owns QUERY result semantics, {@link JdbcUpdateHandler} owns UPDATE and generated-key
 * semantics, and {@link JdbcCallHandler} owns callable result semantics. JDBC resources remain runner-owned, so a
 * transaction lease can release an operation without closing the transaction's physical connection.</p>
 */
final class JdbcRunner {
    /** Datasource used when an operation acquires a connection lease. */
    private final DataSource dataSource;
    /** Policy that supplies owned or transaction-bound operation leases. */
    private final JdbcConnectionLease.Provider leaseProvider;
    /** QUERY semantics, including mapping, cardinality, reduction, and traversal. */
    private final JdbcQueryHandler queryHandler;
    /** UPDATE and generated-key semantics. */
    private final JdbcUpdateHandler updateHandler;
    /** Stored-procedure and function semantics. */
    private final JdbcCallHandler callHandler;

    /**
     * Creates an execution engine with fixed datasource and lease policy.
     *
     * @param dataSource datasource used for JDBC operations
     * @param leaseProvider operation-lease provider
     */
    JdbcRunner(DataSource dataSource,
               JdbcConnectionLease.Provider leaseProvider) {
        this.dataSource = dataSource;
        this.leaseProvider = leaseProvider;
        this.queryHandler = new JdbcQueryHandler();
        this.updateHandler = new JdbcUpdateHandler(queryHandler);
        this.callHandler = new JdbcCallHandler(queryHandler);
    }

    /**
     * Executes an update directly because it has no row-mapping cursor.
     *
     * @param operation immutable update snapshot
     * @return update count
     */
    long execute(JdbcOperation operation) {
        return run(operation, updateHandler::execute);
    }

    /**
     * Executes an input-only stored procedure with no result channels.
     *
     * @param operation immutable callable snapshot
     */
    void call(JdbcOperation operation) {
        run(operation, scope -> {
            callHandler.invoke(scope);
            return null;
        });
    }

    /**
     * Executes a callable operation and snapshots all scalar outputs.
     *
     * @param operation immutable callable snapshot
     * @return detached scalar output values
     */
    JdbcClient.CallOutputValues callForOutputs(JdbcOperation operation) {
        return run(operation, callHandler::invokeForOutputs);
    }

    /**
     * Executes a callable operation with a void callback.
     *
     * @param operation immutable callable snapshot
     * @param request callback request
     */
    void call(JdbcOperation operation, JdbcResultRequest.Call request) {
        run(operation, scope -> {
            callHandler.invoke(scope, request);
            return null;
        });
    }

    /**
     * Executes a callable operation whose callback creates a detached result.
     *
     * @param operation immutable callable snapshot
     * @param request callback request
     * @param <R> detached result type
     * @return callback result
     */
    <R> R call(JdbcOperation operation, JdbcResultRequest.CallWith<R> request) {
        return run(operation, scope -> callHandler.invoke(scope, request));
    }

    /**
     * Executes an initialization script on one provider-owned transaction.
     *
     * <p>This setup path is intentionally separate from repository terminals:
     * it receives validated, bind-free statements, owns one setup connection,
     * and commits or rolls back the complete script as a unit.</p>
     *
     * @param sqlStatements validated statements in execution order
     */
    void executeScript(List<String> sqlStatements) {
        if (sqlStatements.isEmpty()) {
            throw new IllegalArgumentException("JDBC initialization script must contain at least one statement");
        }
        Connection connection = null;
        Boolean originalAutoCommit = null;
        JdbcOperation current = scriptOperation(sqlStatements.getFirst());
        Throwable failure = null;
        try {
            // Initialization deliberately owns this connection rather than joining an application transaction.
            connection = dataSource.getConnection();
            originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            // Initialization statements share one setup connection and transaction, but have no bind values.
            for (String sql : sqlStatements) {
                current = scriptOperation(sql);
                executeOn(connection, current);
            }
            connection.commit();
        } catch (Throwable t) {
            failure = t;
            if (connection != null) {
                // A failure in any statement rolls back all setup work; rollback failures remain suppressed.
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
        } finally {
            if (connection != null) {
                if (originalAutoCommit != null) {
                    // Restore pool-visible connection state before closing the setup connection.
                    try {
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (Throwable restoreFailure) {
                        failure = mergeFailure(failure, restoreFailure);
                    }
                }
                try {
                    connection.close();
                } catch (Throwable closeFailure) {
                    failure = mergeFailure(failure, closeFailure);
                }
            }
        }
        if (failure != null) {
            rethrow(current, failure);
        }
    }

    /**
     * Executes one update on an already leased connection.
     *
     * <p>This method is also used by initialization scripts. It expects one
     * primary update result and rejects result sets or additional result
     * channels so ordinary update terminals have deterministic semantics.</p>
     *
     * @param connection leased connection
     * @param operation immutable operation snapshot
     * @return normalized large update count
     * @throws SQLException if JDBC preparation or execution fails
     */
    private long executeOn(Connection connection, JdbcOperation operation) throws SQLException {
        connection.clearWarnings();
        try (PreparedStatement statement = prepare(connection, operation)) {
            try {
                configure(statement, operation.options());
                bind(statement, operation.binds(), operation.preparationPlan());
                statement.clearWarnings();
                ExecutionScope scope = new ExecutionScope(operation, statement);
                long updateCount = updateHandler.execute(scope);
                preserveWarnings(null, connection, statement, null);
                return updateCount;
            } catch (SQLException e) {
                preserveWarnings(e, connection, statement, null);
                throw e;
            } catch (RuntimeException | Error e) {
                preserveWarnings(e, connection, statement, null);
                throw e;
            }
        }
    }

    /**
     * Creates the no-bind operation used for one initialization-script statement.
     *
     * @param sql validated script statement
     * @return update operation with no bind slots
     */
    private static JdbcOperation scriptOperation(String sql) {
        int parameters = JdbcOperation.parameterCount(sql);
        if (parameters != 0) {
            throw new IllegalArgumentException("JDBC initialization statements cannot contain bind markers");
        }
        return new JdbcOperation(sql,
                                 new JdbcOperation.Bind[0],
                                 JdbcStatementOptions.EMPTY,
                                 JdbcPreparationPlan.update());
    }

    /**
     * Merges a secondary failure into a primary failure.
     *
     * @param primary existing failure, possibly null
     * @param secondary additional failure
     * @return primary failure with suppression, or secondary when primary is null
     */
    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    /**
     * Rethrows an operation failure while preserving its original exception category.
     *
     * @param operation operation used for SQL diagnostics
     * @param failure failure captured during execution or cleanup
     */
    private static void rethrow(JdbcOperation operation, Throwable failure) {
        if (failure instanceof SQLException sqlException) {
            throw JdbcExceptionTranslator.translate(operation, sqlException);
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new DataException("JDBC operation failed", failure);
    }

    /**
     * Executes a query and requires exactly one mapped row.
     *
     * @param operation immutable query or generated-key operation
     * @param mapper mapper invoked for the selected row
     * @param <T> mapped type
     * @return the only mapped value
     */
    <T> T one(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        return switch (operation.preparationPlan().resultKind()) {
            case QUERY -> run(operation, scope -> queryHandler.one(scope, mapper));
            case GENERATED_KEYS -> run(operation, scope -> updateHandler.oneGeneratedKey(scope, mapper));
            case UPDATE, CALL -> throw incompatibleTerminal(operation, "one");
        };
    }

    /**
     * Executes a query and permits zero or one mapped row.
     *
     * @param operation immutable query or generated-key operation
     * @param mapper mapper invoked for the selected row
     * @param <T> mapped type
     * @return empty for no row, otherwise the mapped value
     */
    <T> Optional<T> optional(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        return switch (operation.preparationPlan().resultKind()) {
            case QUERY -> run(operation, scope -> queryHandler.optional(scope, mapper));
            case GENERATED_KEYS -> run(operation, scope -> updateHandler.optionalGeneratedKey(scope, mapper));
            case UPDATE, CALL -> throw incompatibleTerminal(operation, "optional");
        };
    }

    /**
     * Executes a query and materializes all mapped rows in encounter order.
     *
     * @param operation immutable query or generated-key operation
     * @param mapper mapper invoked once per physical row
     * @param <T> mapped type
     * @return materialized mapped values
     */
    <T> List<T> list(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        return switch (operation.preparationPlan().resultKind()) {
            case QUERY -> run(operation, scope -> queryHandler.list(scope, mapper));
            case GENERATED_KEYS -> run(operation, scope -> updateHandler.generatedKeys(scope, mapper));
            case UPDATE, CALL -> throw incompatibleTerminal(operation, "list");
        };
    }

    /**
     * Executes a query and reduces all physical rows into one logical result.
     *
     * <p>The query handler delivers callback-scoped rows to the reducer. The
     * reducer owns only logical state; the runner continues to own the row view
     * and JDBC resources.</p>
     *
     * @param operation immutable query operation
     * @param reducer result-set-scoped reducer
     * @param <R> logical result type
     * @return reducer result after successful exhaustion
     */
    <R> R reduce(JdbcOperation operation, JdbcClient.RowReducer<R> reducer) {
        if (operation.preparationPlan().resultKind() != JdbcPreparationPlan.ResultKind.QUERY) {
            throw incompatibleTerminal(operation, "reduce");
        }
        return run(operation, scope -> queryHandler.reduce(scope, reducer));
    }

    /**
     * Visits every mapped row until the result set is exhausted.
     *
     * @param operation immutable query operation
     * @param mapper mapper invoked for each row
     * @param action callback invoked for each mapped value
     * @param <T> mapped type
     */
    <T> void visitAll(JdbcOperation operation,
                      JdbcClient.RowMapper<T> mapper,
                      Consumer<? super T> action) {
        switch (operation.preparationPlan().resultKind()) {
            case QUERY -> run(operation, scope -> {
                queryHandler.visitAll(scope, mapper, action);
                return null;
            });
            case GENERATED_KEYS -> run(operation, scope -> {
                updateHandler.visitGeneratedKeys(scope, mapper, action);
                return null;
            });
            case UPDATE -> throw incompatibleTerminal(operation, "visitAll");
            default -> throw incompatibleTerminal(operation, "visitAll");
        }
    }

    /**
     * Visits mapped rows until exhaustion or predicate-directed stop.
     *
     * @param operation immutable query operation
     * @param mapper mapper invoked for each row
     * @param action callback that decides whether to continue
     * @param <T> mapped type
     * @return true after normal exhaustion, false after predicate stop
     */
    <T> boolean visitWhile(JdbcOperation operation,
                           JdbcClient.RowMapper<T> mapper,
                           Predicate<? super T> action) {
        return switch (operation.preparationPlan().resultKind()) {
            case QUERY -> run(operation, scope -> queryHandler.visitWhile(scope, mapper, action));
            case GENERATED_KEYS -> run(operation, scope -> updateHandler.visitGeneratedKeysWhile(scope, mapper, action));
            case UPDATE, CALL -> throw incompatibleTerminal(operation, "visitWhile");
        };
    }

    /**
     * Executes one handler inside the runner-owned resource boundary.
     *
     * <p>The operation scope is created only after the statement has been
     * prepared, configured, and bound. The runner snapshots warnings and closes
     * the current result set, statement, and logical lease before returning the
     * handler result.</p>
     *
     * @param operation immutable operation
     * @param action execution-specific handler action
     * @param <T> terminal result type
     * @return terminal result
     */
    private <T> T run(JdbcOperation operation, HandlerAction<T> action) {
        JdbcConnectionLease lease = null;
        PreparedStatement statement = null;
        ExecutionScope scope = null;
        T result = null;
        Throwable failure = null;
        try {
            // The lease provider selects an owned connection or the active transaction-bound connection.
            lease = leaseProvider.acquire(dataSource);
            Connection connection = lease.connection();
            connection.clearWarnings();
            statement = prepare(connection, operation);
            configure(statement, operation.options());
            bind(statement, operation.binds(), operation.preparationPlan());
            if (statement instanceof CallableStatement callableStatement) {
                callHandler.registerOutputs(callableStatement, operation.preparationPlan().call());
            }
            statement.clearWarnings();
            scope = new ExecutionScope(operation, statement);
            result = action.execute(scope);
        } catch (Throwable t) {
            failure = t;
        }

        Connection connection = lease == null ? null : lease.connection();
        ResultSet resultSet = scope == null ? null : scope.resultSet();
        preserveWarnings(failure, connection, statement, resultSet);
        Throwable cleanupFailure = closeAll(resultSet, statement, lease);
        if (cleanupFailure != null) {
            if (failure == null) {
                failure = cleanupFailure;
            } else if (failure instanceof SQLException) {
                // Preserve raw JDBC cleanup details on a primary JDBC failure before translating it.
                failure.addSuppressed(cleanupFailure);
            } else {
                failure.addSuppressed(cleanupException(operation, cleanupFailure));
            }
        }
        if (scope != null) {
            scope.addCapturedResultWarnings(failure);
        }
        if (failure != null) {
            rethrow(operation, failure);
        }
        return result;
    }

    /**
     * Prepares the JDBC statement selected by the operation contract.
     *
     * @param connection leased connection
     * @param operation operation containing SQL and preparation metadata
     * @return prepared statement
     * @throws SQLException if the driver rejects preparation
     */
    private PreparedStatement prepare(Connection connection, JdbcOperation operation) throws SQLException {
        JdbcPreparationPlan plan = operation.preparationPlan();
        if (plan.resultKind() == JdbcPreparationPlan.ResultKind.CALL) {
            return connection.prepareCall(operation.sql());
        }
        if (plan.resultKind() != JdbcPreparationPlan.ResultKind.GENERATED_KEYS) {
            // Ordinary query and update operations use the portable prepared-statement overload.
            return connection.prepareStatement(operation.sql());
        }
        String[] columns = plan.generatedColumns();
        // Empty names request the driver's default generated keys; names select the portable column overload.
        return columns.length == 0
                ? connection.prepareStatement(operation.sql(), java.sql.Statement.RETURN_GENERATED_KEYS)
                : connection.prepareStatement(operation.sql(), columns);
    }

    /**
     * Applies configured statement options in one portable, deterministic order.
     *
     * @param statement prepared statement
     * @param options immutable operation options
     * @throws SQLException if the driver rejects an explicitly requested option
     */
    private static void configure(PreparedStatement statement, JdbcStatementOptions options) throws SQLException {
        Integer fetchSize = options.fetchSize();
        if (fetchSize != null) {
            statement.setFetchSize(fetchSize);
        }

        Long maxRows = options.maxRows();
        if (maxRows != null) {
            try {
                statement.setLargeMaxRows(maxRows);
            } catch (SQLFeatureNotSupportedException e) {
                if (maxRows > Integer.MAX_VALUE) {
                    // The legacy setter cannot represent this request without narrowing.
                    throw e;
                }
                statement.setMaxRows(maxRows.intValue());
            }
        }

        Duration queryTimeout = options.queryTimeout();
        if (queryTimeout != null) {
            statement.setQueryTimeout(Math.toIntExact(queryTimeout.getSeconds()));
        }

        Boolean poolableHint = options.poolableHint();
        if (poolableHint != null) {
            statement.setPoolable(poolableHint);
        }
    }

    /**
     * Binds the immutable positional snapshots in JDBC order.
     *
     * @param statement prepared statement
     * @param binds ordered bind values
     * @throws SQLException if a JDBC setter fails
     */
    private static void bind(PreparedStatement statement,
                             JdbcOperation.Bind[] binds,
                             JdbcPreparationPlan plan) throws SQLException {
        for (int i = 0; i < binds.length; i++) {
            int position = i + 1;
            JdbcOperation.Bind bind = binds[i];
            if (bind == null) {
                // CALL output-only and function-return positions deliberately have no input bind.
                continue;
            }
            if (!bind.typed()) {
                if (plan.resultKind() == JdbcPreparationPlan.ResultKind.CALL) {
                    JdbcCall.Parameter parameter = plan.call().parameters().get(i);
                    if (parameter.jdbcType() != Jdbc.INFERRED_TYPE) {
                        statement.setObject(position, bind.value(), parameter.jdbcType());
                        continue;
                    }
                }
                // A byte array has a dedicated JDBC setter; other supported values use driver conversion.
                if (bind.value() instanceof byte[] bytes) {
                    statement.setBytes(position, bytes);
                } else {
                    statement.setObject(position, bind.value());
                }
                continue;
            }

            SQLType type = bind.type();
            // Typed nulls need setNull when the SQLType exposes the integer vendor code.
            if (bind.value() == null && type.getVendorTypeNumber() != null) {
                statement.setNull(position, type.getVendorTypeNumber());
            } else {
                statement.setObject(position, bind.value(), type);
            }
        }
    }

    /**
     * Reads the large update count with compatibility fallback for older drivers.
     *
     * @param statement prepared statement
     * @return JDBC update count
     * @throws SQLException if either count accessor fails
     */
    private static long largeUpdateCount(PreparedStatement statement) throws SQLException {
        try {
            return statement.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException e) {
            // Some drivers implement only the legacy integer count accessor.
            return statement.getUpdateCount();
        }
    }

    /**
     * Verifies that the primary result was the only JDBC result.
     *
     * <p>Single-result terminals must not silently discard a second result set
     * or update count. The advancement call closes the current result before
     * checking the next one.</p>
     *
     * @param statement prepared statement
     * @param operation operation used for diagnostics
     * @throws SQLException if result advancement fails
     */
    private static void rejectFollowingResults(PreparedStatement statement, JdbcOperation operation) throws SQLException {
        boolean nextIsResultSet = statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT);
        if (drainFromCurrent(statement, nextIsResultSet)) {
            DataException failure = new DataException("JDBC " + operation.preparationPlan().resultKind()
                                                              + " returned unexpected additional results");
            preserveWarnings(failure, null, statement, null);
            throw failure;
        }
    }

    /**
     * Closes and advances through unexpected result channels.
     *
     * <p>This helper is used when a query receives an update count, generated
     * key execution receives a result set, or a single-result terminal checks
     * for trailing channels. It deliberately drains and rejects them in the
     * current release, while its explicit JDBC advancement boundary leaves room
     * for a future callable result collector.</p>
     *
     * @param statement prepared statement
     * @param currentIsResultSet whether the current JDBC result is a result set
     * @return true when any result channel was encountered
     * @throws SQLException if a result cannot be closed or advanced
     */
    private static boolean drainFromCurrent(PreparedStatement statement, boolean currentIsResultSet) throws SQLException {
        boolean unexpected = false;
        boolean resultSet = currentIsResultSet;
        while (true) {
            if (resultSet) {
                unexpected = true;
                ResultSet current = statement.getResultSet();
                if (current != null) {
                    // CLOSE_CURRENT_RESULT is also requested on the next advance; close explicitly for deterministic ownership.
                    current.close();
                }
            } else {
                long count = largeUpdateCount(statement);
                if (count == -1) {
                    return unexpected;
                }
                unexpected = true;
            }
            // Never retain a JDBC result while advancing to the next channel.
            resultSet = statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT);
        }
    }

    /**
     * Creates a diagnostic for a missing or incompatible primary result.
     *
     * @param operation operation whose contract was violated
     * @param resultPresent whether an incompatible result was present
     * @return data exception
     */
    private static DataException unexpectedResult(JdbcOperation operation, boolean resultPresent) {
        String detail = resultPresent ? "an incompatible result" : "no expected result";
        return new DataException("JDBC " + operation.preparationPlan().resultKind() + " returned " + detail);
    }

    /**
     * Reads and clears warning chains from all JDBC ownership layers.
     *
     * <p>Warnings are attached to an existing primary failure. A successful
     * operation currently has no public warning result, so its warnings are
     * cleared after being inspected.</p>
     *
     * @param primary primary failure, or null for a successful path
     * @param connection connection whose warnings should be read
     * @param statement statement whose warnings should be read
     * @param resultSet result set whose warnings should be read
     */
    private static void preserveWarnings(Throwable primary,
                                         Connection connection,
                                         PreparedStatement statement,
                                         ResultSet resultSet) {
        try {
            if (resultSet != null) {
                addWarningChain(primary, resultSet.getWarnings());
                resultSet.clearWarnings();
            }
            if (statement != null) {
                addWarningChain(primary, statement.getWarnings());
                statement.clearWarnings();
            }
            if (connection != null) {
                addWarningChain(primary, connection.getWarnings());
                connection.clearWarnings();
            }
        } catch (Throwable warningFailure) {
            if (primary != null) {
                primary.addSuppressed(warningFailure);
            }
        }
    }

    /**
     * Adds every warning in a JDBC warning chain as a suppressed exception.
     *
     * @param primary failure that receives warnings
     * @param warning first warning in the chain
     */
    private static void addWarningChain(Throwable primary, SQLWarning warning) {
        if (primary == null) {
            return;
        }
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            primary.addSuppressed(current);
        }
    }

    /**
     * Closes every resource and returns the first cleanup failure.
     *
     * @param resources resources in deterministic close order
     * @return first failure with later failures suppressed, or null
     */
    private static Throwable closeAll(AutoCloseable... resources) {
        Throwable first = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                if (first == null) {
                    first = closeFailure;
                } else {
                    first.addSuppressed(closeFailure);
                }
            }
        }
        return first;
    }

    /**
     * Converts a cleanup failure before attaching it to a non-JDBC primary failure.
     *
     * @param operation operation used for SQL diagnostics
     * @param failure cleanup failure
     * @return translated cleanup failure
     */
    private static Throwable cleanupException(JdbcOperation operation, Throwable failure) {
        if (failure instanceof SQLException sqlException) {
            return JdbcExceptionTranslator.translate(operation, sqlException);
        }
        if (failure instanceof RuntimeException || failure instanceof Error) {
            return failure;
        }
        return new DataException("JDBC resource cleanup failed", failure);
    }

    /**
     * Creates an internal diagnostic when a terminal and operation plan disagree.
     *
     * @param operation immutable operation
     * @param terminal terminal name
     * @return internal state exception
     */
    private static IllegalStateException incompatibleTerminal(JdbcOperation operation, String terminal) {
        return new IllegalStateException("JDBC " + operation.preparationPlan().resultKind()
                                                 + " operation cannot use the " + terminal + " terminal");
    }

    /**
     * Handler callback invoked inside one prepared-operation scope.
     *
     * @param <T> terminal result type
     */
    @FunctionalInterface
    private interface HandlerAction<T> {

        /**
         * Executes operation-specific semantics.
         *
         * @param scope runner-owned scope
         * @return terminal result
         * @throws SQLException if JDBC execution fails
         */
        T execute(ExecutionScope scope) throws SQLException;
    }

    /**
     * Runner-owned view of one prepared and bound JDBC operation.
     *
     * <p>Handlers may execute and inspect the statement through this scope, but
     * they cannot acquire or release a connection lease. The runner tracks the
     * current result set and closes it with the statement and lease after the
     * handler completes.</p>
     */
    static final class ExecutionScope {
        private final JdbcOperation operation;
        private final PreparedStatement statement;
        private final List<Throwable> capturedResultWarnings = new ArrayList<>();
        private ResultSet resultSet;

        private ExecutionScope(JdbcOperation operation,
                               PreparedStatement statement) {
            this.operation = operation;
            this.statement = statement;
        }

        /**
         * Returns the immutable operation metadata.
         *
         * @return operation metadata
         */
        JdbcOperation operation() {
            return operation;
        }

        /**
         * Returns the prepared and bound statement.
         *
         * @return prepared statement
         */
        PreparedStatement statement() {
            return statement;
        }

        /**
         * Returns the callable statement for a CALL operation.
         *
         * @return callable statement
         */
        CallableStatement callableStatement() {
            require(JdbcPreparationPlan.ResultKind.CALL);
            return (CallableStatement) statement;
        }

        /**
         * Verifies that a handler is processing its expected operation kind.
         *
         * @param expected expected result kind
         */
        void require(JdbcPreparationPlan.ResultKind expected) {
            JdbcPreparationPlan.ResultKind actual = operation.preparationPlan().resultKind();
            if (actual != expected) {
                throw new IllegalStateException("JDBC handler expected " + expected + " but received " + actual);
            }
        }

        /**
         * Registers the one provider-owned result set associated with this operation.
         *
         * @param resultSet current result set
         */
        void resultSet(ResultSet resultSet) {
            if (this.resultSet != null && this.resultSet != resultSet) {
                throw new IllegalStateException("A simple JDBC operation cannot own multiple live result sets");
            }
            this.resultSet = resultSet;
        }

        /**
         * Clears a result set after an execution handler has closed it.
         *
         * @param expected result set that was closed
         */
        void clearResultSet(ResultSet expected) {
            if (resultSet != expected) {
                throw new IllegalStateException("JDBC execution scope does not own the supplied result set");
            }
            resultSet = null;
        }

        /**
         * Captures and clears warnings before a handler closes an intermediate result set.
         *
         * @param resultSet result set whose warning chain is still accessible
         */
        void captureResultWarnings(ResultSet resultSet) {
            try {
                for (SQLWarning warning = resultSet.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                    capturedResultWarnings.add(warning);
                }
                resultSet.clearWarnings();
            } catch (Throwable warningFailure) {
                // Warning inspection must not replace the operation outcome; retain the failure for later diagnostics.
                capturedResultWarnings.add(warningFailure);
            }
        }

        private void addCapturedResultWarnings(Throwable failure) {
            if (failure == null) {
                return;
            }
            for (Throwable detail : capturedResultWarnings) {
                if (detail != failure) {
                    failure.addSuppressed(detail);
                }
            }
        }

        /**
         * Returns the current provider-owned result set for warning capture and cleanup.
         *
         * @return current result set, or {@code null}
         */
        ResultSet resultSet() {
            return resultSet;
        }

        /**
         * Reads the current update count with the shared compatibility fallback.
         *
         * @return large update count
         * @throws SQLException if the driver rejects both accessors
         */
        long largeUpdateCount() throws SQLException {
            return JdbcRunner.largeUpdateCount(statement);
        }

        /**
         * Drains incompatible result channels through the shared advancement path.
         *
         * @param currentIsResultSet whether the current result is a result set
         * @return {@code true} when at least one result channel was encountered
         * @throws SQLException if result closure or advancement fails
         */
        boolean drainFromCurrent(boolean currentIsResultSet) throws SQLException {
            return JdbcRunner.drainFromCurrent(statement, currentIsResultSet);
        }

        /**
         * Rejects any result channel following the accepted primary result.
         *
         * @throws SQLException if result advancement fails
         */
        void rejectFollowingResults() throws SQLException {
            JdbcRunner.rejectFollowingResults(statement, operation);
        }

        /**
         * Creates a diagnostic for an incompatible or missing primary result.
         *
         * @param resultPresent whether an incompatible channel was present
         * @return data exception
         */
        DataException unexpectedResult(boolean resultPresent) {
            return JdbcRunner.unexpectedResult(operation, resultPresent);
        }

    }
}
