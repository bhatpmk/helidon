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

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.common.Api;
import io.helidon.data.DataException;

/**
 * Helidon internal interface, through which both generated declarative API and the future imperative API, interact with Kernel
 *
 * <p>
 * Implementations obtain connections from the supplied {@link DataSource}. Transaction participation is therefore
 * controlled by the {@link DataSource} instance registered with Helidon services.
 */
@Api.Internal
public interface JdbcOperations {

    /**
     * Create JDBC operations for a data source.
     *
     * @param dataSource data source
     * @return JDBC operations
     */
    static JdbcOperations create(DataSource dataSource) {
        return new JdbcOperationsImpl(dataSource);
    }

    /**
     * Execute an update statement without arguments.
     *
     * @param statement JDBC statement
     * @return update count
     * @throws DataException if execution fails
     */
    default long update(String statement) {
        return update(JdbcStatementPlan.update(statement), JdbcBinder.none());
    }

    /**
     * Execute an update statement.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @return update count
     * @throws DataException if execution fails
     */
    default long update(String statement, JdbcBinder binder) {
        return update(JdbcStatementPlan.update(statement), binder);
    }

    /**
     * Execute an update statement plan.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @return update count
     * @throws DataException if execution fails
     */
    long update(JdbcStatementPlan plan, JdbcBinder binder);

    /**
     * Execute a query without arguments and map all result rows.
     *
     * @param statement JDBC statement
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result rows
     * @throws DataException if execution fails
     */
    default <T> List<T> list(String statement, JdbcRowMapper<? extends T> mapper) {
        return list(JdbcStatementPlan.query(statement), JdbcBinder.none(), mapper);
    }

    /**
     * Execute a query and map all result rows.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result rows
     * @throws DataException if execution fails
     */
    default <T> List<T> list(String statement, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return list(JdbcStatementPlan.query(statement), binder, mapper);
    }

    /**
     * Execute a query statement plan and map all result rows.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result rows
     * @throws DataException if execution fails
     */
    <T> List<T> list(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper);

    /**
     * Execute a query and reduce all result rows into aggregate values.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate values
     * @throws DataException if execution fails
     */
    default <T> List<T> listReduced(String statement, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return listReduced(JdbcStatementPlan.query(statement), binder, reducer);
    }

    /**
     * Execute a query statement plan and reduce all result rows into aggregate values.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate values
     * @throws DataException if execution fails
     */
    <T> List<T> listReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer);

    /**
     * Execute a query and map at most one result row.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result row, or empty if none was returned
     * @throws DataException if execution fails or more than one row is returned
     */
    default <T> Optional<T> optional(String statement, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return optional(JdbcStatementPlan.query(statement), binder, mapper);
    }

    /**
     * Execute a query statement plan and map at most one result row.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result row, or empty if none was returned
     * @throws DataException if execution fails or more than one row is returned
     */
    <T> Optional<T> optional(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper);

    /**
     * Execute a query and reduce result rows into at most one aggregate value.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate value, or empty if none was returned
     * @throws DataException if execution fails or more than one aggregate is returned
     */
    default <T> Optional<T> optionalReduced(String statement, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return optionalReduced(JdbcStatementPlan.query(statement), binder, reducer);
    }

    /**
     * Execute a query statement plan and reduce result rows into at most one aggregate value.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate value, or empty if none was returned
     * @throws DataException if execution fails or more than one aggregate is returned
     */
    <T> Optional<T> optionalReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer);

    /**
     * Execute a query and map exactly one result row.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result row
     * @throws DataException if execution fails or row count is not exactly one
     */
    default <T> T one(String statement, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return one(JdbcStatementPlan.query(statement), binder, mapper);
    }

    /**
     * Execute a query statement plan and map exactly one result row.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param mapper row mapper
     * @param <T> result item type
     * @return mapped result row
     * @throws DataException if execution fails or row count is not exactly one
     */
    <T> T one(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper);

    /**
     * Execute a query and reduce result rows into exactly one aggregate value.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate value
     * @throws DataException if execution fails or aggregate count is not exactly one
     */
    default <T> T oneReduced(String statement, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return oneReduced(JdbcStatementPlan.query(statement), binder, reducer);
    }

    /**
     * Execute a query statement plan and reduce result rows into exactly one aggregate value.
     *
     * @param plan statement plan
     * @param binder statement argument binder
     * @param reducer row reducer
     * @param <T> result item type
     * @return reduced aggregate value
     * @throws DataException if execution fails or aggregate count is not exactly one
     */
    <T> T oneReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer);

    /**
     * Execute an update statement and map at most one generated-key row.
     *
     * @param statement JDBC statement
     * @param binder statement argument binder
     * @param mapper generated-key row mapper
     * @param columnNames generated-key column names
     * @param <T> generated-key item type
     * @return mapped generated key, or empty if none was returned
     * @throws DataException if execution fails or more than one generated-key row is returned
     */
    default <T> Optional<T> generatedKey(String statement,
                                         JdbcBinder binder,
                                         JdbcRowMapper<? extends T> mapper,
                                         String... columnNames) {
        return generatedKey(JdbcStatementPlan.generatedKeys(statement, columnNames), binder, mapper);
    }

    /**
     * Execute an update statement plan and map at most one generated-key row.
     *
     * @param plan generated-key statement plan
     * @param binder statement argument binder
     * @param mapper generated-key row mapper
     * @param <T> generated-key item type
     * @return mapped generated key, or empty if none was returned
     * @throws DataException if execution fails or more than one generated-key row is returned
     */
    <T> Optional<T> generatedKey(JdbcStatementPlan plan,
                                 JdbcBinder binder,
                                 JdbcRowMapper<? extends T> mapper);
}
