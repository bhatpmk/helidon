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
import java.util.stream.Stream;

import io.helidon.service.registry.Service;

/**
 * JDBC client used by generated Helidon Data JDBC repositories and imperative JDBC Data applications.
 * <p>
 * The client does not expose live JDBC resources. A statement created by {@link #execute(String)} only captures SQL,
 * parameters, and options. JDBC connections, statements, and result sets are opened by terminal methods and closed before
 * those methods return.
 */
@Service.Contract
public interface JdbcClient {

    /**
     * Create a statement operation for the provided SQL text.
     *
     * @param sql SQL statement
     * @return statement operation
     */
    Statement execute(String sql);

    /**
     * Fluent JDBC statement operation.
     */
    interface Statement {

        /**
         * Add a positional statement parameter.
         *
         * @param value parameter value
         * @return this statement
         */
        Statement param(Object value);

        /**
         * Add a named statement parameter.
         *
         * @param name  parameter name
         * @param value parameter value
         * @return this statement
         */
        Statement param(String name, Object value);

        /**
         * Add statement parameters.
         *
         * @param parameters statement parameters
         * @return this statement
         */
        Statement params(List<JdbcParameter> parameters);

        /**
         * Configure JDBC fetch size for query execution.
         *
         * @param fetchSize fetch size, or {@code 0} for driver default
         * @return this statement
         */
        Statement fetchSize(int fetchSize);

        /**
         * Configure generated-key column names for generated-key terminal methods.
         *
         * @param columnNames generated-key column names
         * @return this statement
         */
        Statement generatedKeyColumns(String... columnNames);

        /**
         * Execute a query and return all mapped rows.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped rows
         */
        <T> List<T> list(JdbcRowMapper<T> mapper);

        /**
         * Execute a query and return a materialized stream of mapped rows.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped row stream
         */
        default <T> Stream<T> stream(JdbcRowMapper<T> mapper) {
            return list(mapper).stream();
        }

        /**
         * Execute a query and return the only mapped row.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped row
         */
        <T> T single(JdbcRowMapper<T> mapper);

        /**
         * Execute a query and return the only mapped row, or {@code null} when no row is returned.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped row, or {@code null}
         */
        <T> T singleOrNull(JdbcRowMapper<T> mapper);

        /**
         * Execute a query and return the only mapped row as an {@link Optional}.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped row optional
         */
        <T> Optional<T> optional(JdbcRowMapper<T> mapper);

        /**
         * Execute an update, DDL, or other statement whose result is reduced to update counts.
         *
         * @return summed update count
         */
        Number updateCount();

        /**
         * Execute an update, DDL, or other statement and return its update count as {@code int}.
         *
         * @return summed update count
         */
        default int updateCountInt() {
            return updateCount().intValue();
        }

        /**
         * Execute an update, DDL, or other statement and return its update count as {@code long}.
         *
         * @return summed update count
         */
        default long updateCountLong() {
            return updateCount().longValue();
        }

        /**
         * Execute an update, DDL, or other statement and return whether at least one row was affected.
         *
         * @return whether the update count is greater than zero
         */
        default boolean updateCountBoolean() {
            return updateCountLong() > 0;
        }

        /**
         * Execute an update and return all generated-key rows.
         *
         * @param mapper generated-key row mapper
         * @param <T>    mapped generated-key type
         * @return mapped generated-key rows
         */
        <T> List<T> generatedKeys(JdbcRowMapper<T> mapper);

        /**
         * Execute an update and return the only generated-key row.
         *
         * @param mapper generated-key row mapper
         * @param <T>    mapped generated-key type
         * @return mapped generated key
         */
        <T> T generatedKey(JdbcRowMapper<T> mapper);

        /**
         * Execute an update and return the only generated-key row as an {@link Optional}.
         *
         * @param mapper generated-key row mapper
         * @param <T>    mapped generated-key type
         * @return mapped generated-key optional
         */
        <T> Optional<T> optionalGeneratedKey(JdbcRowMapper<T> mapper);
    }
}
