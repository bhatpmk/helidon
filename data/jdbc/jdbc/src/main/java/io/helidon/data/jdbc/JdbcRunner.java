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
import java.util.Iterator;
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
 */
final class JdbcRunner {
    private final DataSource dataSource;
    private final JdbcExecutionOptions defaults;
    private final JdbcConnectionLease.Provider leaseProvider;

    JdbcRunner(DataSource dataSource,
               JdbcExecutionOptions defaults,
               JdbcConnectionLease.Provider leaseProvider) {
        this.dataSource = dataSource;
        this.defaults = defaults;
        this.leaseProvider = leaseProvider;
    }

    long execute(JdbcOperation operation) {
        try (JdbcConnectionLease lease = leaseProvider.acquire(dataSource)) {
            return executeOn(lease.connection(), operation);
        } catch (SQLException e) {
            throw JdbcExceptionTranslator.translate(operation, e);
        }
    }

    /**
     * Executes an initialization script on one provider-owned transaction.
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
            connection = dataSource.getConnection();
            originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            for (String sql : sqlStatements) {
                current = scriptOperation(sql);
                executeOn(connection, current);
            }
            connection.commit();
        } catch (Throwable t) {
            failure = t;
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
        } finally {
            if (connection != null) {
                if (originalAutoCommit != null) {
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

    private long executeOn(Connection connection, JdbcOperation operation) throws SQLException {
        connection.clearWarnings();
        try (PreparedStatement statement = prepare(connection, operation)) {
            try {
                configure(statement, operation);
                bind(statement, operation.binds());
                statement.clearWarnings();

                boolean resultSet = statement.execute();
                if (resultSet) {
                    boolean unexpected = drainFromCurrent(statement, true);
                    DataException failure = unexpectedResult(operation, unexpected);
                    preserveWarnings(failure, connection, statement, null);
                    throw failure;
                }

                long updateCount = largeUpdateCount(statement);
                rejectFollowingResults(statement, operation);
                preserveWarnings(null, connection, statement, null);
                return updateCount < 0 ? 0 : updateCount;
            } catch (SQLException e) {
                preserveWarnings(e, connection, statement, null);
                throw e;
            }
        }
    }

    private static JdbcOperation scriptOperation(String sql) {
        int parameters = JdbcOperation.parameterCount(sql);
        if (parameters != 0) {
            throw new IllegalArgumentException("JDBC initialization statements cannot contain bind markers");
        }
        return new JdbcOperation(sql,
                                 new JdbcOperation.Bind[0],
                                 JdbcExecutionOptions.EMPTY,
                                 JdbcPreparationPlan.update());
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

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

    <T> T one(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            if (!cursor.hasNextValue()) {
                throw new NoResultException("JDBC query returned no rows");
            }
            T result = cursor.nextValue();
            if (cursor.hasNextValue()) {
                throw new NonUniqueResultException("JDBC query returned more than one row");
            }
            return result;
        }
    }

    <T> Optional<T> optional(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            if (!cursor.hasNextValue()) {
                return Optional.empty();
            }
            T result = cursor.nextValue();
            if (cursor.hasNextValue()) {
                throw new NonUniqueResultException("JDBC query returned more than one row");
            }
            return Optional.ofNullable(result);
        }
    }

    <T> List<T> list(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            List<T> result = new ArrayList<>();
            while (cursor.hasNextValue()) {
                result.add(cursor.nextValue());
            }
            return result;
        }
    }

    <R> R reduce(JdbcOperation operation, JdbcClient.RowReducer<R> reducer) {
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

    <T> void withRows(JdbcOperation operation,
                      JdbcClient.RowMapper<T> mapper,
                      Consumer<? super Iterable<T>> action) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            Iterable<T> rows = cursor.iterableFacade();
            try {
                action.accept(rows);
            } finally {
                // Invalidate before resource cleanup so retained facades fail even if a close operation also fails.
                cursor.invalidateFacade();
            }
        }
    }

    <T> void forEach(JdbcOperation operation,
                     JdbcClient.RowMapper<T> mapper,
                     Consumer<? super T> action) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            while (cursor.hasNextValue()) {
                action.accept(cursor.nextValue());
            }
        }
    }

    <T> boolean forEachWhile(JdbcOperation operation,
                             JdbcClient.RowMapper<T> mapper,
                             Predicate<? super T> action) {
        try (JdbcStreamingCursor<T> cursor = openCursor(operation, mapper)) {
            while (cursor.hasNextValue()) {
                if (!action.test(cursor.nextValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    private <T> JdbcStreamingCursor<T> openCursor(JdbcOperation operation, JdbcClient.RowMapper<T> mapper) {
        JdbcConnectionLease lease = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            lease = leaseProvider.acquire(dataSource);
            Connection connection = lease.connection();
            connection.clearWarnings();
            statement = prepare(connection, operation);
            configure(statement, operation);
            bind(statement, operation.binds());
            statement.clearWarnings();

            if (operation.preparationPlan().resultKind() == JdbcPreparationPlan.ResultKind.GENERATED_KEYS) {
                boolean hasResultSet = statement.execute();
                if (hasResultSet) {
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
            JdbcColumnLayout columns = JdbcColumnLayout.create(resultSet.getMetaData(), operation);
            return new JdbcStreamingCursor<>(operation, lease, statement, resultSet, columns, mapper);
        } catch (SQLException e) {
            preserveWarnings(e, lease == null ? null : lease.connection(), statement, resultSet);
            closeOnFailure(e, resultSet, statement, lease);
            throw JdbcExceptionTranslator.translate(operation, e);
        } catch (RuntimeException | Error e) {
            closeOnFailure(e, resultSet, statement, lease);
            throw e;
        }
    }

    private PreparedStatement prepare(Connection connection, JdbcOperation operation) throws SQLException {
        JdbcPreparationPlan plan = operation.preparationPlan();
        if (plan.resultKind() != JdbcPreparationPlan.ResultKind.GENERATED_KEYS) {
            return connection.prepareStatement(operation.sql());
        }
        String[] columns = plan.generatedColumns();
        return columns.length == 0
                ? connection.prepareStatement(operation.sql(), java.sql.Statement.RETURN_GENERATED_KEYS)
                : connection.prepareStatement(operation.sql(), columns);
    }

    private void configure(PreparedStatement statement, JdbcOperation operation) throws SQLException {
        JdbcExecutionOptions options = defaults.overlay(operation.options());
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
    }

    private static void bind(PreparedStatement statement, JdbcOperation.Bind[] binds) throws SQLException {
        for (int i = 0; i < binds.length; i++) {
            int position = i + 1;
            JdbcOperation.Bind bind = binds[i];
            if (!bind.typed()) {
                if (bind.value() instanceof byte[] bytes) {
                    statement.setBytes(position, bytes);
                } else {
                    statement.setObject(position, bind.value());
                }
                continue;
            }

            SQLType type = bind.type();
            if (bind.value() == null && type.getVendorTypeNumber() != null) {
                statement.setNull(position, type.getVendorTypeNumber());
            } else {
                statement.setObject(position, bind.value(), type);
            }
        }
    }

    private static long largeUpdateCount(PreparedStatement statement) throws SQLException {
        try {
            return statement.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException e) {
            return statement.getUpdateCount();
        }
    }

    private static void rejectFollowingResults(PreparedStatement statement, JdbcOperation operation) throws SQLException {
        boolean nextIsResultSet = statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT);
        if (drainFromCurrent(statement, nextIsResultSet)) {
            DataException failure = new DataException("JDBC " + operation.preparationPlan().resultKind()
                                                              + " returned unexpected additional results");
            preserveWarnings(failure, null, statement, null);
            throw failure;
        }
    }

    private static boolean drainFromCurrent(PreparedStatement statement, boolean currentIsResultSet) throws SQLException {
        boolean unexpected = false;
        boolean resultSet = currentIsResultSet;
        while (true) {
            if (resultSet) {
                unexpected = true;
                ResultSet current = statement.getResultSet();
                if (current != null) {
                    current.close();
                }
            } else {
                long count = largeUpdateCount(statement);
                if (count == -1) {
                    return unexpected;
                }
                unexpected = true;
            }
            resultSet = statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT);
        }
    }

    private static DataException unexpectedResult(JdbcOperation operation, boolean resultPresent) {
        String detail = resultPresent ? "an incompatible result" : "no expected result";
        return new DataException("JDBC " + operation.preparationPlan().resultKind() + " returned " + detail);
    }

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

    private static void addWarningChain(Throwable primary, SQLWarning warning) {
        if (primary == null) {
            return;
        }
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            primary.addSuppressed(current);
        }
    }

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
     * Owns the open result path for every materializing and traversal terminal.
     */
    private static final class JdbcStreamingCursor<T> implements AutoCloseable {
        private final JdbcOperation operation;
        private final JdbcConnectionLease lease;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final JdbcClient.RowMapper<T> mapper;
        private final JdbcRow row;
        private final Thread owner;
        private boolean facadeActive;
        private boolean iteratorCreated;
        private boolean nextReady;
        private boolean exhausted;
        private boolean closed;

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
            this.owner = Thread.currentThread();
        }

        Iterable<T> iterableFacade() {
            facadeActive = true;
            return () -> {
                checkFacadeAccess();
                if (iteratorCreated) {
                    throw new IllegalStateException("JDBC row iterable permits one iterator");
                }
                iteratorCreated = true;
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        checkFacadeAccess();
                        return hasNextValue();
                    }

                    @Override
                    public T next() {
                        checkFacadeAccess();
                        return nextValue();
                    }
                };
            };
        }

        void invalidateFacade() {
            facadeActive = false;
        }

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

        T nextValue() {
            if (!hasNextValue()) {
                throw new NoSuchElementException("No more JDBC rows");
            }
            nextReady = false;
            row.activate();
            try {
                return mapper.map(row);
            } finally {
                row.deactivate();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
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

        private void checkFacadeAccess() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("JDBC row iterable is thread-confined");
            }
            if (!facadeActive) {
                throw new IllegalStateException("JDBC row iterable is valid only while its callback is running");
            }
        }
    }
}
