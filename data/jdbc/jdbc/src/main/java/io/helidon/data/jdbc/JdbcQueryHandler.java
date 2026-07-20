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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;

/**
 * Owns QUERY result semantics while {@link JdbcRunner} owns the JDBC lifecycle.
 *
 * <p>The handler validates the primary result, maps rows, applies cardinality,
 * reduces results, and performs callback traversal. It receives an already
 * prepared and bound runner scope and never acquires a connection or closes a
 * statement or lease independently.</p>
 */
final class JdbcQueryHandler {

    /**
     * Executes a query and returns exactly one mapped value.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param <T> mapped type
     * @return the only mapped value
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> T one(JdbcRunner.ExecutionScope scope, JdbcClient.RowMapper<T> mapper) throws SQLException {
        return one(scope, mapper, executeQuery(scope));
    }

    /**
     * Maps an existing row-bearing UPDATE result with exactly-one cardinality.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @return the only mapped value
     * @throws SQLException if JDBC traversal fails
     */
    <T> T one(JdbcRunner.ExecutionScope scope,
              JdbcClient.RowMapper<T> mapper,
              ResultSet resultSet) throws SQLException {
        return one(scope, mapper, resultSet, true);
    }

    <T> T oneScoped(JdbcRunner.ExecutionScope scope,
                    JdbcClient.RowMapper<T> mapper,
                    ResultSet resultSet) throws SQLException {
        return one(scope, mapper, resultSet, false);
    }

    private <T> T one(JdbcRunner.ExecutionScope scope,
                      JdbcClient.RowMapper<T> mapper,
                      ResultSet resultSet,
                      boolean rejectFollowingResults) throws SQLException {
        JdbcResultCursor<T> cursor = cursor(scope, mapper, resultSet, rejectFollowingResults);
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

    /**
     * Executes a query and permits zero or one mapped value.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param <T> mapped type
     * @return empty for no row, otherwise the mapped value
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> Optional<T> optional(JdbcRunner.ExecutionScope scope, JdbcClient.RowMapper<T> mapper) throws SQLException {
        return optional(scope, mapper, executeQuery(scope));
    }

    /**
     * Maps an existing row-bearing UPDATE result with zero-or-one cardinality.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @return empty for no row, otherwise the mapped value
     * @throws SQLException if JDBC traversal fails
     */
    <T> Optional<T> optional(JdbcRunner.ExecutionScope scope,
                            JdbcClient.RowMapper<T> mapper,
                            ResultSet resultSet) throws SQLException {
        return optional(scope, mapper, resultSet, true);
    }

    <T> Optional<T> optionalScoped(JdbcRunner.ExecutionScope scope,
                                  JdbcClient.RowMapper<T> mapper,
                                  ResultSet resultSet) throws SQLException {
        return optional(scope, mapper, resultSet, false);
    }

    private <T> Optional<T> optional(JdbcRunner.ExecutionScope scope,
                                    JdbcClient.RowMapper<T> mapper,
                                    ResultSet resultSet,
                                    boolean rejectFollowingResults) throws SQLException {
        JdbcResultCursor<T> cursor = cursor(scope, mapper, resultSet, rejectFollowingResults);
        if (!cursor.hasNextValue()) {
            return Optional.empty();
        }
        T result = cursor.nextValue();
        // Optional represents zero-or-one cardinality, not first-row selection.
        if (cursor.hasNextValue()) {
            throw new NonUniqueResultException("JDBC query returned more than one row");
        }
        return Optional.of(result);
    }

    /**
     * Executes a query and materializes mapped values in encounter order.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param <T> mapped type
     * @return materialized values
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> List<T> list(JdbcRunner.ExecutionScope scope, JdbcClient.RowMapper<T> mapper) throws SQLException {
        return list(scope, mapper, executeQuery(scope));
    }

    /**
     * Materializes an existing row-bearing UPDATE result.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @return materialized values
     * @throws SQLException if JDBC traversal fails
     */
    <T> List<T> list(JdbcRunner.ExecutionScope scope,
                     JdbcClient.RowMapper<T> mapper,
                     ResultSet resultSet) throws SQLException {
        return list(scope, mapper, resultSet, true);
    }

    <T> List<T> listScoped(JdbcRunner.ExecutionScope scope,
                           JdbcClient.RowMapper<T> mapper,
                           ResultSet resultSet) throws SQLException {
        return list(scope, mapper, resultSet, false);
    }

    private <T> List<T> list(JdbcRunner.ExecutionScope scope,
                             JdbcClient.RowMapper<T> mapper,
                             ResultSet resultSet,
                             boolean rejectFollowingResults) throws SQLException {
        JdbcResultCursor<T> cursor = cursor(scope, mapper, resultSet, rejectFollowingResults);
        List<T> result = new ArrayList<>();
        while (cursor.hasNextValue()) {
            result.add(cursor.nextValue());
        }
        return result;
    }

    /**
     * Executes a query and reduces all physical rows.
     *
     * @param scope runner-owned execution scope
     * @param reducer result-set-scoped reducer
     * @param <R> logical result type
     * @return reduced value
     * @throws SQLException if JDBC execution or traversal fails
     */
    <R> R reduce(JdbcRunner.ExecutionScope scope, JdbcClient.RowReducer<R> reducer) throws SQLException {
        return reduce(scope, reducer, executeQuery(scope), true);
    }

    <R> R reduceScoped(JdbcRunner.ExecutionScope scope,
                       JdbcClient.RowReducer<R> reducer,
                       ResultSet resultSet) throws SQLException {
        return reduce(scope, reducer, resultSet, false);
    }

    private <R> R reduce(JdbcRunner.ExecutionScope scope,
                         JdbcClient.RowReducer<R> reducer,
                         ResultSet resultSet,
                         boolean rejectFollowingResults) throws SQLException {
        JdbcClient.RowMapper<Boolean> acceptingMapper = row -> {
            reducer.accept(row);
            return Boolean.TRUE;
        };
        JdbcResultCursor<Boolean> cursor = cursor(scope, acceptingMapper, resultSet, rejectFollowingResults);
        while (cursor.hasNextValue()) {
            cursor.nextValue();
        }
        R result = reducer.finish();
        if (result == null) {
            throw new DataException("JDBC row reducer returned null");
        }
        return result;
    }

    /**
     * Executes a query and visits every mapped value.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param action callback invoked for every value
     * @param <T> mapped type
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> void visitAll(JdbcRunner.ExecutionScope scope,
                      JdbcClient.RowMapper<T> mapper,
                      Consumer<? super T> action) throws SQLException {
        visitAll(scope, mapper, action, executeQuery(scope));
    }

    /**
     * Visits every value from an existing row-bearing UPDATE result.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param action callback invoked for every value
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @throws SQLException if JDBC traversal fails
     */
    <T> void visitAll(JdbcRunner.ExecutionScope scope,
                      JdbcClient.RowMapper<T> mapper,
                      Consumer<? super T> action,
                      ResultSet resultSet) throws SQLException {
        visitAll(scope, mapper, action, resultSet, true);
    }

    <T> void visitAllScoped(JdbcRunner.ExecutionScope scope,
                            JdbcClient.RowMapper<T> mapper,
                            Consumer<? super T> action,
                            ResultSet resultSet) throws SQLException {
        visitAll(scope, mapper, action, resultSet, false);
    }

    private <T> void visitAll(JdbcRunner.ExecutionScope scope,
                              JdbcClient.RowMapper<T> mapper,
                              Consumer<? super T> action,
                              ResultSet resultSet,
                              boolean rejectFollowingResults) throws SQLException {
        JdbcResultCursor<T> cursor = cursor(scope, mapper, resultSet, rejectFollowingResults);
        while (cursor.hasNextValue()) {
            action.accept(cursor.nextValue());
        }
    }

    /**
     * Executes a query and visits values until exhaustion or callback-directed stop.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param action continuation predicate
     * @param <T> mapped type
     * @return {@code true} after exhaustion, {@code false} after callback-directed stop
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> boolean visitWhile(JdbcRunner.ExecutionScope scope,
                           JdbcClient.RowMapper<T> mapper,
                           Predicate<? super T> action) throws SQLException {
        return visitWhile(scope, mapper, action, executeQuery(scope));
    }

    /**
     * Visits values from an existing row-bearing UPDATE result until exhaustion or callback-directed stop.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param action continuation predicate
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @return {@code true} after exhaustion, {@code false} after callback-directed stop
     * @throws SQLException if JDBC traversal fails
     */
    <T> boolean visitWhile(JdbcRunner.ExecutionScope scope,
                           JdbcClient.RowMapper<T> mapper,
                           Predicate<? super T> action,
                           ResultSet resultSet) throws SQLException {
        return visitWhile(scope, mapper, action, resultSet, true);
    }

    <T> boolean visitWhileScoped(JdbcRunner.ExecutionScope scope,
                                 JdbcClient.RowMapper<T> mapper,
                                 Predicate<? super T> action,
                                 ResultSet resultSet) throws SQLException {
        return visitWhile(scope, mapper, action, resultSet, false);
    }

    private <T> boolean visitWhile(JdbcRunner.ExecutionScope scope,
                                   JdbcClient.RowMapper<T> mapper,
                                   Predicate<? super T> action,
                                   ResultSet resultSet,
                                   boolean rejectFollowingResults) throws SQLException {
        JdbcResultCursor<T> cursor = cursor(scope, mapper, resultSet, rejectFollowingResults);
        while (cursor.hasNextValue()) {
            if (!action.test(cursor.nextValue())) {
                // Runner cleanup closes the current result before the terminal returns.
                return false;
            }
        }
        return true;
    }

    /**
     * Executes the primary QUERY channel and returns its result set.
     *
     * @param scope runner-owned execution scope
     * @return query result set
     * @throws SQLException if JDBC execution or result advancement fails
     */
    private static ResultSet executeQuery(JdbcRunner.ExecutionScope scope) throws SQLException {
        scope.require(JdbcPreparationPlan.ResultKind.QUERY);
        boolean hasResultSet = scope.statement().execute();
        if (!hasResultSet) {
            // Drain the incompatible channel before reporting the QUERY contract violation.
            boolean unexpected = scope.drainFromCurrent(false);
            throw scope.unexpectedResult(unexpected);
        }
        return scope.statement().getResultSet();
    }

    /**
     * Creates the private incremental cursor used by every row terminal.
     *
     * @param scope runner-owned execution scope
     * @param mapper row mapper
     * @param resultSet provider-owned result set
     * @param <T> mapped type
     * @return incremental cursor
     * @throws SQLException if result metadata cannot be read
     */
    private static <T> JdbcResultCursor<T> cursor(JdbcRunner.ExecutionScope scope,
                                                  JdbcClient.RowMapper<T> mapper,
                                                  ResultSet resultSet,
                                                  boolean rejectFollowingResults) throws SQLException {
        if (resultSet == null) {
            throw new DataException("JDBC " + scope.operation().preparationPlan().resultKind()
                                            + " did not provide an expected result set");
        }
        scope.resultSet(resultSet);
        JdbcColumnLayout columns = JdbcColumnLayout.create(resultSet.getMetaData(), scope.operation());
        return new JdbcResultCursor<>(scope, resultSet, columns, mapper, rejectFollowingResults);
    }

    /**
     * Private incremental cursor shared by materializing, reduction, and callback terminals.
     *
     * @param <T> mapped type
     */
    private static final class JdbcResultCursor<T> {
        private final JdbcRunner.ExecutionScope scope;
        private final ResultSet resultSet;
        private final JdbcClient.RowMapper<T> mapper;
        private final JdbcRow row;
        private final boolean rejectFollowingResults;
        private boolean nextReady;
        private boolean exhausted;

        private JdbcResultCursor(JdbcRunner.ExecutionScope scope,
                                 ResultSet resultSet,
                                 JdbcColumnLayout columns,
                                 JdbcClient.RowMapper<T> mapper,
                                 boolean rejectFollowingResults) {
            this.scope = scope;
            this.resultSet = resultSet;
            this.mapper = mapper;
            this.row = new JdbcRow(resultSet, columns, scope.operation());
            this.rejectFollowingResults = rejectFollowingResults;
        }

        /**
         * Advances lazily and verifies that no additional result channel follows exhaustion.
         *
         * @return {@code true} when a row is ready
         * @throws SQLException if row or result advancement fails
         */
        private boolean hasNextValue() throws SQLException {
            if (nextReady) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            nextReady = resultSet.next();
            if (!nextReady) {
                exhausted = true;
                if (rejectFollowingResults) {
                    scope.rejectFollowingResults();
                }
            }
            return nextReady;
        }

        /**
         * Maps the prepared row and limits row-view validity to this callback.
         *
         * @return mapped value
         * @throws SQLException if lazy advancement fails
         */
        private T nextValue() throws SQLException {
            if (!hasNextValue()) {
                throw new NoSuchElementException("No more JDBC rows");
            }
            nextReady = false;
            row.activate();
            try {
                T value = mapper.map(row);
                if (value == null) {
                    throw new DataException("JDBC row mapper returned null");
                }
                return value;
            } finally {
                row.deactivate();
            }
        }
    }
}
