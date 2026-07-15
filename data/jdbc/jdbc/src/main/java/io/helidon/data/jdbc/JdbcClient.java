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

import java.sql.SQLType;
import java.util.List;
import java.util.Optional;

import io.helidon.data.DataException;
import io.helidon.service.registry.Service;

/**
 * Imperative client for prepared JDBC operations.
 * <p>
 * A client is thread-safe and is normally obtained from the Helidon service registry. Calling {@link #create(String)}
 * creates only an in-memory statement description; JDBC work starts at a terminal operation. Statement and row stages
 * are single-use and are not thread-safe. The provider owns every connection, JDBC statement, and result set and closes
 * or releases them before a terminal operation returns.
 */
@Service.Contract
public interface JdbcClient {

    /**
     * Creates a prepared-statement description.
     * <p>
     * This method performs no JDBC I/O. The SQL must use JDBC positional {@code ?} markers; values must be supplied using
     * one-based {@link Statement#bind(int, Object)} operations before a terminal operation is invoked.
     *
     * @param sql SQL statement, must not be {@code null} or blank
     * @return a new single-use statement stage
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank or contains unsupported marker syntax
     */
    Statement create(String sql);

    /**
     * Mutable, single-use description of one prepared JDBC statement.
     * <p>
     * Options and bindings only update this description. Exactly one terminal operation may be invoked, either directly
     * or through a mapped {@link Rows} stage.
     */
    interface Statement {

        /**
         * Applies per-execution statement options.
         * <p>
         * A setting embedded in a later {@link JdbcQueryRequest} overrides the corresponding value. An unset request
         * setting preserves this value.
         *
         * @param options immutable execution options, must not be {@code null}
         * @return this statement stage
         * @throws NullPointerException if {@code options} is {@code null}
         * @throws IllegalStateException if a terminal operation has already started
         */
        Statement options(JdbcStatementOptions options);

        /**
         * Binds a supported non-null scalar value to a one-based JDBC position.
         *
         * @param index one-based parameter position
         * @param value value to bind, must not be {@code null}
         * @return this statement stage
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if the index or value type is unsupported, or the position was already bound
         * @throws IllegalStateException if a terminal operation has already started
         */
        Statement bind(int index, Object value);

        /**
         * Binds a value using an explicit JDBC type.
         * <p>
         * A {@code null} value is permitted by this overload and is passed through the typed JDBC binding path.
         *
         * @param index one-based parameter position
         * @param value value to bind, which may be {@code null}
         * @param type JDBC type, must not be {@code null}
         * @return this statement stage
         * @throws NullPointerException if {@code type} is {@code null}
         * @throws IllegalArgumentException if the index is invalid or the position was already bound
         * @throws IllegalStateException if a terminal operation has already started
         */
        Statement bind(int index, Object value, SQLType type);

        /**
         * Binds SQL {@code NULL} using an explicit JDBC type.
         *
         * @param index one-based parameter position
         * @param type JDBC type, must not be {@code null}
         * @return this statement stage
         * @throws NullPointerException if {@code type} is {@code null}
         * @throws IllegalArgumentException if the index is invalid or the position was already bound
         * @throws IllegalStateException if a terminal operation has already started
         */
        Statement bindNull(int index, SQLType type);

        /**
         * Executes the statement as an update and returns its large update count.
         *
         * @return JDBC update count
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if preparation, execution, result advancement, or cleanup fails
         */
        long execute();

        /**
         * Executes a query and reduces all physical rows into one logical result.
         * <p>
         * {@link RowReducer#finish()} is invoked exactly once after successful result-set exhaustion. The reducer is not
         * reused or synchronized by the client.
         *
         * @param reducer result-set-scoped reducer, must not be {@code null}
         * @param <R> logical result type
         * @return reduced result
         * @throws NullPointerException if {@code reducer} is {@code null}
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if JDBC processing fails
         */
        <R> R reduce(RowReducer<R> reducer);

        /**
         * Executes a query and reduces all physical rows using invocation-specific statement settings.
         *
         * @param reducer result-set-scoped reducer, must not be {@code null}
         * @param request regular query request, must not be {@code null}
         * @param <R> logical result type
         * @return reduced result
         * @throws NullPointerException if {@code reducer} or {@code request} is {@code null}
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if JDBC processing fails
         */
        <R> R reduce(RowReducer<R> reducer, JdbcQueryRequest request);

        /**
         * Selects an explicit row mapper for a query.
         *
         * @param mapper mapper invoked once for each consumed row, must not be {@code null}
         * @param <T> mapped row type
         * @return a single-use row terminal stage
         * @throws NullPointerException if {@code mapper} is {@code null}
         * @throws IllegalStateException if this statement has already executed
         */
        <T> Rows<T> map(RowMapper<T> mapper);

        /**
         * Selects mapping of column one to a supported scalar type.
         * <p>
         * This method never performs reflective bean or record mapping. A primitive class requires a non-null column;
         * the corresponding wrapper class permits SQL {@code NULL}.
         *
         * @param scalarType supported scalar class, must not be {@code null}
         * @param <T> mapped scalar type
         * @return a single-use row terminal stage
         * @throws NullPointerException if {@code scalarType} is {@code null}
         * @throws IllegalArgumentException if the scalar type is unsupported
         * @throws IllegalStateException if this statement has already executed
         */
        <T> Rows<T> map(Class<T> scalarType);

        /**
         * Executes an update and maps its generated-key rows.
         * <p>
         * An empty column-name list requests the driver's default generated keys. Supplying names selects JDBC's named
         * generated-column preparation overload. Execution remains deferred until a {@link Rows} terminal is invoked.
         *
         * @param mapper generated-key mapper, must not be {@code null}
         * @param columnNames generated column names; elements must be non-null and non-blank
         * @param <T> mapped generated-key type
         * @return a single-use generated-key terminal stage
         * @throws NullPointerException if the mapper, array, or an array element is {@code null}
         * @throws IllegalArgumentException if a column name is blank
         * @throws IllegalStateException if this statement has already executed
         */
        <T> Rows<T> generatedKeys(RowMapper<T> mapper, String... columnNames);
    }

    /**
     * Cardinality and traversal terminals for mapped rows.
     * <p>
     * A stage permits exactly one terminal invocation. Every terminal closes provider-owned JDBC resources before it
     * returns, including exceptional and early-termination paths.
     *
     * @param <T> mapped row type
     */
    interface Rows<T> {

        /**
         * Returns exactly one mapped row.
         *
         * @return the only row
         * @throws io.helidon.data.NoResultException if no row exists
         * @throws io.helidon.data.NonUniqueResultException if more than one row exists
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        T one();

        /**
         * Returns exactly one mapped row using invocation-specific statement settings.
         *
         * @param request regular query request, must not be {@code null}
         * @return the only row
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws io.helidon.data.NoResultException if no row exists
         * @throws io.helidon.data.NonUniqueResultException if more than one row exists
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        T one(JdbcQueryRequest request);

        /**
         * Returns zero or one mapped row.
         *
         * @return an optional containing the row, or empty when no row exists
         * @throws io.helidon.data.NonUniqueResultException if more than one row exists
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        Optional<T> optional();

        /**
         * Returns zero or one mapped row using invocation-specific statement settings.
         *
         * @param request regular query request, must not be {@code null}
         * @return an optional containing the row, or empty when no row exists
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws io.helidon.data.NonUniqueResultException if more than one row exists
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        Optional<T> optional(JdbcQueryRequest request);

        /**
         * Materializes all mapped rows in encounter order.
         *
         * @return mapped rows; never {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        List<T> list();

        /**
         * Materializes all mapped rows using invocation-specific statement settings.
         *
         * @param request regular query request, must not be {@code null}
         * @return mapped rows in encounter order; never {@code null}
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        List<T> list(JdbcQueryRequest request);

        /**
         * Visits every mapped row synchronously using constant result-buffer memory.
         *
         * @param request immutable visit-all request, must not be {@code null}
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        void visitAll(JdbcQueryRequest.VisitAll<T> request);

        /**
         * Visits mapped rows until exhaustion or until the predicate returns {@code false}.
         *
         * @param request immutable predicate traversal request, must not be {@code null}
         * @return {@code true} when all rows were visited, or {@code false} when traversal stopped early
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        boolean visitWhile(JdbcQueryRequest.VisitWhile<T> request);
    }

    /**
     * Maps one callback-scoped row to an application value.
     *
     * @param <T> mapped value type
     */
    @FunctionalInterface
    interface RowMapper<T> {

        /**
         * Maps the current row.
         * <p>
         * The row is valid only for this invocation and must not be retained.
         *
         * @param row current row, never {@code null}
         * @return mapped value
         */
        T map(Row row);
    }

    /**
     * Stateful, result-set-scoped reduction of physical rows into one logical result.
     * <p>
     * One reducer instance belongs to one execution and must not be reused concurrently. The provider invokes
     * {@link #accept(Row)} synchronously for every physical row and invokes {@link #finish()} once only after successful
     * result-set exhaustion. The provider owns and closes all JDBC resources.
     *
     * @param <R> logical result type
     */
    interface RowReducer<R> {

        /**
         * Accepts one physical row.
         * <p>
         * The row is valid only for this invocation and must not be retained.
         *
         * @param row current row, never {@code null}
         */
        void accept(Row row);

        /**
         * Produces the logical result after all rows have been accepted successfully.
         * <p>
         * This method is not invoked when row traversal or {@link #accept(Row)} fails.
         *
         * @return logical result
         */
        R finish();
    }

    /**
     * Restricted typed view of the current JDBC row.
     * <p>
     * The view provides values only; it exposes no navigation, metadata, statement, result-set, or connection operations.
     * It is valid only during the mapper or reducer callback that received it.
     */
    interface Row {

        /**
         * Reads a nullable value by one-based column index.
         *
         * @param index one-based result column index
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return converted value, or {@code null} for SQL {@code NULL}
         * @throws DataException if the value cannot be read or converted
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> T get(int index, Class<T> type);

        /**
         * Reads a nullable value by column label.
         *
         * @param label result column label, must not be {@code null} or blank
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return converted value, or {@code null} for SQL {@code NULL}
         * @throws DataException if the label is absent or the value cannot be read or converted
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> T get(String label, Class<T> type);

        /**
         * Reads a required value by one-based column index.
         *
         * @param index one-based result column index
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return non-null converted value
         * @throws DataException if the value is SQL {@code NULL}, cannot be read, or cannot be converted
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> T required(int index, Class<T> type);

        /**
         * Reads a required value by column label.
         *
         * @param label result column label, must not be {@code null} or blank
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return non-null converted value
         * @throws DataException if the label is absent, the value is SQL {@code NULL}, or conversion fails
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> T required(String label, Class<T> type);
    }
}
