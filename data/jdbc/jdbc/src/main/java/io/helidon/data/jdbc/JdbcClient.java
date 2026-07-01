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

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.helidon.service.registry.Service;

/**
 * JDBC client used by generated Helidon Data JDBC repositories and imperative JDBC Data applications.
 * <p>
 * The client does not expose live JDBC resources. A statement created by {@link #execute(String)} only captures SQL,
 * parameters, batch entries, OUT parameter registrations, generated-key settings, and statement options. It does not
 * acquire a JDBC connection or send SQL to the database.
 * <p>
 * A database call happens when a terminal method is invoked, such as {@link Statement#list(JdbcRowMapper)},
 * {@link Statement#single(JdbcRowMapper)}, {@link Statement#optional(JdbcRowMapper)}, {@link Statement#updateCount()},
 * {@link Statement#batchUpdateCounts()}, {@link Statement#outParams()},
 * {@link Statement#outCursor(String, JdbcRowMapper)}, or {@link Statement#generatedKeys(JdbcRowMapper)}. The terminal
 * method asks the runtime runner to obtain a connection from the configured data source, create a prepared or callable
 * statement, apply statement options, bind parameters, execute the JDBC operation, and copy JDBC results into an
 * internal transcript. Result sets, generated keys, OUT cursors, update counts, OUT parameters, warnings, and partial
 * failure metadata are detached from JDBC resources before the terminal method returns. The connection, statement, and
 * result set objects are closed by the runtime; reducers then traverse the transcript to return the type requested by
 * the caller.
 * <p>
 * Example:
 * <pre>{@code
 * JdbcClient jdbcClient = ...;
 *
 * // No connection is opened here; the statement only stores SQL, options, and parameters.
 * JdbcClient.Statement statement = jdbcClient.execute("""
 *         SELECT id, name
 *         FROM pokemon
 *         WHERE type = :type
 *         ORDER BY name
 *         """)
 *         .param("type", "electric")
 *         .fetchSize(32)
 *         .readColumns("id", "name");
 *
 * // The list terminal opens JDBC resources, materializes rows, closes resources, and maps the transcript.
 * List<Pokemon> pokemon = statement.list(row -> new Pokemon(row.value("id", Long.class),
 *                                                           row.value("name", String.class)));
 *
 * // Update terminals follow the same lifecycle and reduce the transcript to an update count.
 * long updated = jdbcClient.execute("UPDATE pokemon SET name = :name WHERE id = :id")
 *         .param("name", "Raichu")
 *         .param("id", 25)
 *         .updateCountLong();
 * }</pre>
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
         * Limit row materialization to selected result-set column labels.
         * <p>
         * This is an optimization for generated mappers and imperative callers that know exactly which columns they read.
         * The resulting {@link JdbcRow} exposes only the selected columns.
         *
         * @param labels selected column labels
         * @return this statement
         */
        Statement readColumns(String... labels);

        /**
         * Limit row materialization to selected one-based result-set column indexes.
         * <p>
         * This is an optimization for scalar and generated-key mappers that read known column positions. The resulting
         * {@link JdbcRow} exposes only the selected columns.
         *
         * @param firstIndex        first selected column index
         * @param additionalIndexes additional selected column indexes
         * @return this statement
         */
        Statement readColumns(int firstIndex, int... additionalIndexes);

        /**
         * Add the currently configured parameters as one batch item and clear them for the next batch item.
         *
         * @return this statement
         */
        Statement addBatch();

        /**
         * Add one batch item with the provided parameters.
         *
         * @param parameters batch item parameters
         * @return this statement
         */
        Statement addBatch(List<JdbcParameter> parameters);

        /**
         * Add one batch item with the provided parameters.
         *
         * @param parameters batch item parameters
         * @return this statement
         */
        default Statement addBatch(JdbcParameter... parameters) {
            return addBatch(List.of(parameters));
        }

        /**
         * Configure JDBC fetch size for query execution.
         *
         * @param fetchSize fetch size, or {@code 0} for driver default
         * @return this statement
         */
        Statement fetchSize(int fetchSize);

        /**
         * Configure the maximum number of rows returned by result-set terminal methods.
         *
         * @param maxRows maximum rows, or {@code 0} for no limit
         * @return this statement
         */
        Statement maxRows(long maxRows);

        /**
         * Configure JDBC query timeout in seconds.
         *
         * @param seconds query timeout in seconds, or {@code 0} for no timeout
         * @return this statement
         */
        Statement queryTimeout(int seconds);

        /**
         * Configure JDBC result set type and concurrency.
         * <p>
         * Values must be constants from {@link java.sql.ResultSet}, such as
         * {@link java.sql.ResultSet#TYPE_FORWARD_ONLY} and {@link java.sql.ResultSet#CONCUR_READ_ONLY}.
         *
         * @param type        result set type
         * @param concurrency result set concurrency
         * @return this statement
         */
        Statement resultSet(int type, int concurrency);

        /**
         * Configure JDBC result set type, concurrency, and holdability.
         * <p>
         * Values must be constants from {@link java.sql.ResultSet}.
         *
         * @param type        result set type
         * @param concurrency result set concurrency
         * @param holdability result set holdability
         * @return this statement
         */
        Statement resultSet(int type, int concurrency, int holdability);

        /**
         * Configure generated-key column names for generated-key terminal methods.
         *
         * @param columnNames generated-key column names
         * @return this statement
         */
        Statement generatedKeyColumns(String... columnNames);

        /**
         * Configure generated-key column indexes for generated-key terminal methods.
         *
         * @param firstColumnIndex         first generated-key column index, one-based
         * @param additionalColumnIndexes additional generated-key column indexes, one-based
         * @return this statement
         */
        Statement generatedKeyColumns(int firstColumnIndex, int... additionalColumnIndexes);

        /**
         * Register a callable statement OUT parameter.
         *
         * @param index   one-based JDBC parameter index
         * @param name    output name in the returned map
         * @param sqlType SQL type from {@link java.sql.Types}
         * @return this statement
         */
        Statement outParam(int index, String name, int sqlType);

        /**
         * Register a callable statement OUT parameter using the index as the output name.
         *
         * @param index   one-based JDBC parameter index
         * @param sqlType SQL type from {@link java.sql.Types}
         * @return this statement
         */
        default Statement outParam(int index, int sqlType) {
            return outParam(index, String.valueOf(index), sqlType);
        }

        /**
         * Register a callable statement cursor OUT parameter.
         *
         * @param index   one-based JDBC parameter index
         * @param name    cursor name used by cursor terminal methods
         * @param sqlType SQL type from {@link java.sql.Types}
         * @return this statement
         */
        Statement outCursor(int index, String name, int sqlType);

        /**
         * Register a callable statement REF_CURSOR OUT parameter.
         *
         * @param index one-based JDBC parameter index
         * @param name  cursor name used by cursor terminal methods
         * @return this statement
         */
        default Statement outCursor(int index, String name) {
            return outCursor(index, name, Types.REF_CURSOR);
        }

        /**
         * Register a callable statement REF_CURSOR OUT parameter using the index as the cursor name.
         *
         * @param index one-based JDBC parameter index
         * @return this statement
         */
        default Statement outCursor(int index) {
            return outCursor(index, String.valueOf(index), Types.REF_CURSOR);
        }

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
         * Execute configured batch items and return JDBC batch update counts.
         *
         * @return batch update counts
         */
        long[] batchUpdateCounts();

        /**
         * Execute a callable statement and return materialized OUT parameter values.
         *
         * @return OUT parameter values keyed by registered name
         */
        Map<String, Object> outParams();

        /**
         * Execute a callable statement and return one scalar OUT parameter.
         *
         * @param name output name
         * @param type target type
         * @param <T>  target type
         * @return converted OUT parameter value
         */
        <T> T outParam(String name, Class<T> type);

        /**
         * Execute a callable statement and return one cursor OUT parameter as mapped rows.
         *
         * @param name   cursor output name
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return mapped cursor rows
         */
        <T> List<T> outCursor(String name, JdbcRowMapper<T> mapper);

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
