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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;

/**
 * Single package-private execution engine for prepared JDBC operations.
 *
 * <p>Generated declarative repositories reach this class only through the
 * public {@link JdbcClient} stages. Keeping lease acquisition, preparation,
 * binding, execution, row mapping, warning handling, exception translation,
 * and cleanup here prevents the materializing, reducer, and push terminals
 * from developing different JDBC semantics.</p>
 *
 * <p>Query terminals share {@link JdbcStreamingCursor}; update-only execution
 * uses the direct update path and does not create a cursor. JDBC resources are
 * always provider-owned. A transaction lease can therefore release the
 * operation without closing the transaction's physical connection.</p>
 */
final class JdbcRunner {
    /** Datasource used when an operation acquires a connection lease. */
    private final DataSource dataSource;
    /** Client-level options overlaid by operation-level options. */
    private final JdbcStatementOptions defaults;
    /** Policy that supplies owned or transaction-bound operation leases. */
    private final JdbcConnectionLease.Provider leaseProvider;

    /**
     * Creates an execution engine with fixed datasource and lease policy.
     *
     * @param dataSource datasource used for JDBC operations
     * @param defaults default execution options
     * @param leaseProvider operation-lease provider
     */
    JdbcRunner(DataSource dataSource,
               JdbcStatementOptions defaults,
               JdbcConnectionLease.Provider leaseProvider) {
        this.dataSource = dataSource;
        this.defaults = defaults;
        this.leaseProvider = leaseProvider;
    }

    /**
     * Executes an update directly because it has no row-mapping cursor.
     *
     * @param operation immutable update snapshot
     * @return update count
     */
    long execute(JdbcOperation operation) {
        // The lease scope is the connection ownership boundary for an update.
        try (JdbcConnectionLease lease = leaseProvider.acquire(dataSource)) {
            return executeOn(lease.connection(), operation);
        } catch (SQLException e) {
            throw JdbcExceptionTranslator.translate(operation, e);
        }
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
                configure(statement, operation);
                bind(statement, operation.binds());
                statement.clearWarnings();

                boolean resultSet = statement.execute();
                if (resultSet) {
                    // DML terminals cannot return a primary ResultSet; drain it before reporting the contract violation.
                    boolean unexpected = drainFromCurrent(statement, true);
                    DataException failure = unexpectedResult(operation, unexpected);
                    preserveWarnings(failure, connection, statement, null);
                    throw failure;
                }

                long updateCount = largeUpdateCount(statement);
                // Check the JDBC end marker so an update cannot silently discard a second result.
                rejectFollowingResults(statement, operation);
                preserveWarnings(null, connection, statement, null);
                return updateCount < 0 ? 0 : updateCount;
            } catch (SQLException e) {
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
     * Rethrows a script failure while preserving its original exception category.
     *
     * @param operation last script operation
     * @param failure failure captured during script execution or cleanup
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
        throw new DataException("JDBC initialization script failed", failure);
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
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            if (!cursor.hasNextValue()) {
                throw new NoResultException("JDBC query returned no rows");
            }
            T result = cursor.nextValue();
            // A second advance distinguishes exactly-one semantics from optional or list semantics.
            if (cursor.hasNextValue()) {
                throw new NonUniqueResultException("JDBC query returned more than one row");
            }
            return result;
        }
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
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            if (!cursor.hasNextValue()) {
                return Optional.empty();
            }
            T result = cursor.nextValue();
            // Optional still rejects a second row; it represents zero-or-one, not first-row selection.
            if (cursor.hasNextValue()) {
                throw new NonUniqueResultException("JDBC query returned more than one row");
            }
            return Optional.ofNullable(result);
        }
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
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            List<T> result = new ArrayList<>();
            // The cursor remains incremental, while this terminal deliberately materializes the returned list.
            while (cursor.hasNextValue()) {
                result.add(cursor.nextValue());
            }
            return result;
        }
    }

    /**
     * Executes a query and reduces all physical rows into one logical result.
     *
     * <p>The adapter converts the reducer callback into the same cursor mapper
     * path used by ordinary row terminals. The reducer owns only logical state;
     * the runner continues to own the row view and JDBC resources.</p>
     *
     * @param operation immutable query operation
     * @param reducer result-set-scoped reducer
     * @param <R> logical result type
     * @return reducer result after successful exhaustion
     */
    <R> R reduce(JdbcOperation operation, JdbcClient.RowReducer<R> reducer) {
        // Adapt reduction to the shared mapper loop without exposing the JDBC row or cursor to the reducer.
        JdbcClient.RowMapper<Boolean> acceptingMapper = row -> {
            reducer.accept(row);
            return Boolean.TRUE;
        };
        try (JdbcStreamingCursor<Boolean> cursor = openCursor(operation, acceptingMapper)) {
            while (cursor.hasNextValue()) {
                cursor.nextValue();
            }
            return reducer.finish();
        }
    }

    /**
     * Executes push traversal until the result set is exhausted.
     *
     * @param operation immutable query operation
     * @param mapper mapper invoked for each row
     * @param action callback invoked for each mapped value
     * @param <T> mapped type
     */
    <T> void forEach(JdbcOperation operation,
                     JdbcClient.RowMapper<T> mapper,
                     Consumer<? super T> action) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            while (cursor.hasNextValue()) {
                action.accept(cursor.nextValue());
            }
        }
    }

    /**
     * Executes push traversal until exhaustion or predicate-directed stop.
     *
     * @param operation immutable query operation
     * @param mapper mapper invoked for each row
     * @param action callback that decides whether to continue
     * @param <T> mapped type
     * @return true after normal exhaustion, false after predicate stop
     */
    <T> boolean forEachWhile(JdbcOperation operation,
                             JdbcClient.RowMapper<T> mapper,
                             Predicate<? super T> action) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            while (cursor.hasNextValue()) {
                if (!action.test(cursor.nextValue())) {
                    // Leaving this scope closes the current result immediately; no lifecycle handle escapes to the caller.
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Opens the provider-owned result path at terminal execution time.
     *
     * <p>The method acquires the lease, prepares and configures the statement,
     * binds values, executes the query or generated-key operation, and builds
     * one metadata layout. Any failure before the cursor is returned closes
     * every resource acquired so far.</p>
     *
     * @param operation immutable query or generated-key operation
     * @param mapper mapper used by the returned cursor
     * @param <T> mapped type
     * @return open provider-owned cursor
     */
    private <T> JdbcStreamingCursor<T> openCursor(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        JdbcConnectionLease lease = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            // The lease provider selects an owned connection or the active transaction-bound connection.
            lease = leaseProvider.acquire(dataSource);
            Connection connection = lease.connection();
            connection.clearWarnings();
            // Preparation is deferred until this terminal path so stage construction remains I/O-free.
            statement = prepare(connection, operation);
            configure(statement, operation);
            bind(statement, operation.binds());
            statement.clearWarnings();

            if (operation.preparationPlan().resultKind() == JdbcPreparationPlan.ResultKind.GENERATED_KEYS) {
                boolean hasResultSet = statement.execute();
                if (hasResultSet) {
                    // Generated-key execution expects an update first; drain an unexpected primary result before failing.
                    boolean unexpected = drainFromCurrent(statement, true);
                    DataException failure = unexpectedResult(operation, unexpected);
                    preserveWarnings(failure, connection, statement, null);
                    throw failure;
                }
                largeUpdateCount(statement);
                resultSet = statement.getGeneratedKeys();
            } else {
                boolean hasResultSet = statement.execute();
                if (!hasResultSet) {
                    // Query terminals must receive a ResultSet, not an update count.
                    drainFromCurrent(statement, false);
                    DataException failure = unexpectedResult(operation, true);
                    preserveWarnings(failure, connection, statement, null);
                    throw failure;
                }
                resultSet = statement.getResultSet();
            }

            if (resultSet == null) {
                DataException failure = new DataException("JDBC " + operation.preparationPlan().resultKind()
                                                                  + " did not provide an expected result set");
                preserveWarnings(failure, connection, statement, null);
                throw failure;
            }
            // Metadata is resolved once so every row mapper can use label lookup without per-row metadata work.
            JdbcColumnLayout columns = JdbcColumnLayout.create(resultSet.getMetaData(), operation);
            return new JdbcStreamingCursor<>(operation, lease, statement, resultSet, columns, mapper);
        } catch (SQLException e) {
            // Partial-open failures have the same ownership rules as failures after the cursor is returned.
            preserveWarnings(e, lease == null ? null : lease.connection(), statement, resultSet);
            closeOnFailure(e, resultSet, statement, lease);
            throw JdbcExceptionTranslator.translate(operation, e);
        } catch (RuntimeException | Error e) {
            closeOnFailure(e, resultSet, statement, lease);
            throw e;
        }
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
     * Applies effective statement options before execution.
     *
     * @param statement prepared statement to configure
     * @param operation operation whose options override client defaults
     * @throws SQLException if a driver rejects an option
     */
    private void configure(PreparedStatement statement, JdbcOperation operation) throws SQLException {
        JdbcStatementOptions options = defaults.overlay(operation.options());
        Integer fetchSize = options.fetchSize();
        if (fetchSize != null) {
            statement.setFetchSize(fetchSize);
        }
        Duration timeout = options.queryTimeout();
        if (timeout != null) {
            statement.setQueryTimeout(Math.toIntExact(timeout.getSeconds()));
        }
        Long maxRows = options.maxRows();
        if (maxRows != null) {
            statement.setLargeMaxRows(maxRows);
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
    private static void bind(PreparedStatement statement, JdbcOperation.Bind[] binds) throws SQLException {
        for (int i = 0; i < binds.length; i++) {
            int position = i + 1;
            JdbcOperation.Bind bind = binds[i];
            if (!bind.typed()) {
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
     * Attempts cleanup of every partially acquired resource and suppresses secondary failures.
     *
     * @param primary primary failure to which cleanup failures are attached
     * @param resources resources in deterministic close order
     */
    private static void closeOnFailure(Throwable primary, AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                primary.addSuppressed(closeFailure);
            }
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
     * Owns one open JDBC result path for all row-oriented terminals.
     *
     * <p>The cursor is used by materializing, cardinality, reduction, and push
     * terminals so row advancement, mapping, result-channel checks, and
     * cleanup remain identical. It is not used by update-only
     * {@code execute()}, and it never reaches application code.</p>
     */
    private static final class JdbcStreamingCursor<T> implements AutoCloseable {
        /** Operation metadata used for diagnostics and row conversion errors. */
        private final JdbcOperation operation;
        /** Logical lease that owns or borrows the physical connection. */
        private final JdbcConnectionLease lease;
        /** Provider-owned prepared statement. */
        private final PreparedStatement statement;
        /** Provider-owned current result set. */
        private final ResultSet resultSet;
        /** Mapper invoked for each physical row. */
        private final JdbcClient.RowMapper<T> mapper;
        /** Reusable callback-scoped row view backed by the current result set. */
        private final JdbcRow row;
        /** Whether ResultSet.next() has already prepared the current row. */
        private boolean nextReady;
        /** Whether the result set reached its end marker. */
        private boolean exhausted;
        /** Whether resource cleanup has already been attempted. */
        private boolean closed;

        /**
         * Creates a cursor after all resources and metadata have been acquired.
         *
         * @param operation operation metadata
         * @param lease connection lease
         * @param statement prepared statement
         * @param resultSet current result set
         * @param columns cached result-column layout
         * @param mapper row mapper
         */
        private JdbcStreamingCursor(JdbcOperation operation,
                                    JdbcConnectionLease lease,
                                    PreparedStatement statement,
                                    ResultSet resultSet,
                                    JdbcColumnLayout columns,
                                    JdbcClient.RowMapper<T> mapper) {
            this.operation = operation;
            this.lease = lease;
            this.statement = statement;
            this.resultSet = resultSet;
            this.mapper = mapper;
            this.row = new JdbcRow(resultSet, columns, operation);
        }

        /**
         * Advances the result set lazily and closes it at exhaustion.
         *
         * <p>All row terminals use this method. Once the JDBC end marker is
         * reached, trailing results are checked before cleanup so a current
         * single-result terminal cannot silently discard another channel.</p>
         *
         * @return true when a row is ready
         */
        boolean hasNextValue() {
            if (nextReady) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            try {
                nextReady = resultSet.next();
                if (!nextReady) {
                    exhausted = true;
                    // Advance through the JDBC end marker before releasing resources and accepting the result.
                    if (operation.preparationPlan().resultKind() != JdbcPreparationPlan.ResultKind.UPDATE) {
                        rejectFollowingResults(statement, operation);
                    }
                    close();
                }
                return nextReady;
            } catch (SQLException e) {
                preserveWarnings(e, lease.connection(), statement, resultSet);
                throw JdbcExceptionTranslator.translate(operation, e);
            }
        }

        /**
         * Maps the currently prepared row and limits row-view validity to the mapper call.
         *
         * @return mapped row value
         */
        T nextValue() {
            if (!hasNextValue()) {
                throw new NoSuchElementException("No more JDBC rows");
            }
            nextReady = false;
            // A reducer or mapper may read the row only during this synchronous callback.
            row.activate();
            try {
                return mapper.map(row);
            } finally {
                row.deactivate();
            }
        }

        /**
         * Closes result set, statement, and lease in that order.
         *
         * <p>The lease close releases an owned connection or ends only the
         * logical operation lease for a transaction-bound connection.</p>
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // Keep the order explicit: dependent JDBC objects close before their owner.
            Throwable failure = closeAll(resultSet, statement, lease);
            if (failure instanceof SQLException sqlException) {
                throw JdbcExceptionTranslator.translate(operation, sqlException);
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new DataException("JDBC resource cleanup failed", failure);
            }
        }

    }
}
