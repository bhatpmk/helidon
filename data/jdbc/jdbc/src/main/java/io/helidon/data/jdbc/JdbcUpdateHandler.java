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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns UPDATE and generated-key result semantics while {@link JdbcRunner}
 * owns the JDBC lifecycle.
 *
 * <p>Generated keys remain an UPDATE result. Their row consumption delegates
 * to the same private mapping and cardinality implementation used by QUERY,
 * without routing update execution through the query handler.</p>
 */
final class JdbcUpdateHandler {
    private final JdbcQueryHandler queryHandler;

    /**
     * Creates an update handler that reuses the query row-consumption support.
     *
     * @param queryHandler query row-consumption handler
     */
    JdbcUpdateHandler(JdbcQueryHandler queryHandler) {
        this.queryHandler = queryHandler;
    }

    /**
     * Executes an update and returns its normalized large update count.
     *
     * @param scope runner-owned execution scope
     * @return update count, or zero when JDBC reports no count
     * @throws SQLException if JDBC execution or result advancement fails
     */
    long execute(JdbcRunner.ExecutionScope scope) throws SQLException {
        scope.require(JdbcPreparationPlan.ResultKind.UPDATE);
        boolean hasResultSet = scope.statement().execute();
        if (hasResultSet) {
            // UPDATE terminals cannot accept a primary ResultSet; drain it before failing.
            boolean unexpected = scope.drainFromCurrent(true);
            throw scope.unexpectedResult(unexpected);
        }
        long updateCount = scope.largeUpdateCount();
        scope.rejectFollowingResults();
        return updateCount < 0 ? 0 : updateCount;
    }

    /**
     * Executes an update and returns exactly one generated-key row.
     *
     * @param scope runner-owned execution scope
     * @param mapper generated-key mapper
     * @param <T> mapped type
     * @return the only generated key
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> T oneGeneratedKey(JdbcRunner.ExecutionScope scope, JdbcClient.RowMapper<T> mapper) throws SQLException {
        return queryHandler.one(scope, mapper, executeForGeneratedKeys(scope));
    }

    /**
     * Executes an update and returns zero or one generated-key row.
     *
     * @param scope runner-owned execution scope
     * @param mapper generated-key mapper
     * @param <T> mapped type
     * @return optional generated key
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> Optional<T> optionalGeneratedKey(JdbcRunner.ExecutionScope scope,
                                         JdbcClient.RowMapper<T> mapper) throws SQLException {
        return queryHandler.optional(scope, mapper, executeForGeneratedKeys(scope));
    }

    /**
     * Executes an update and materializes all generated-key rows.
     *
     * @param scope runner-owned execution scope
     * @param mapper generated-key mapper
     * @param <T> mapped type
     * @return generated keys in encounter order
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> List<T> generatedKeys(JdbcRunner.ExecutionScope scope,
                              JdbcClient.RowMapper<T> mapper) throws SQLException {
        return queryHandler.list(scope, mapper, executeForGeneratedKeys(scope));
    }

    /**
     * Executes an update and visits every generated-key row.
     *
     * @param scope runner-owned execution scope
     * @param mapper generated-key mapper
     * @param action callback invoked for every key
     * @param <T> mapped type
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> void visitGeneratedKeys(JdbcRunner.ExecutionScope scope,
                                JdbcClient.RowMapper<T> mapper,
                                Consumer<? super T> action) throws SQLException {
        queryHandler.visitAll(scope, mapper, action, executeForGeneratedKeys(scope));
    }

    /**
     * Executes an update and visits generated-key rows until exhaustion or callback-directed stop.
     *
     * @param scope runner-owned execution scope
     * @param mapper generated-key mapper
     * @param action continuation predicate
     * @param <T> mapped type
     * @return {@code true} after exhaustion, {@code false} after callback-directed stop
     * @throws SQLException if JDBC execution or traversal fails
     */
    <T> boolean visitGeneratedKeysWhile(JdbcRunner.ExecutionScope scope,
                                        JdbcClient.RowMapper<T> mapper,
                                        Predicate<? super T> action) throws SQLException {
        return queryHandler.visitWhile(scope, mapper, action, executeForGeneratedKeys(scope));
    }

    /**
     * Executes the UPDATE primary channel and obtains its generated-key result set.
     *
     * @param scope runner-owned execution scope
     * @return generated-key result set, possibly {@code null} when the driver violates the requested contract
     * @throws SQLException if JDBC execution or key retrieval fails
     */
    private static ResultSet executeForGeneratedKeys(JdbcRunner.ExecutionScope scope) throws SQLException {
        scope.require(JdbcPreparationPlan.ResultKind.GENERATED_KEYS);
        boolean hasResultSet = scope.statement().execute();
        if (hasResultSet) {
            // Generated-key execution expects an update primary channel, not query rows.
            boolean unexpected = scope.drainFromCurrent(true);
            throw scope.unexpectedResult(unexpected);
        }
        scope.largeUpdateCount();
        return scope.statement().getGeneratedKeys();
    }
}
