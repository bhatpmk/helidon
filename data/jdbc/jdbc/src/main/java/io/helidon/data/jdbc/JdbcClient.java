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
import java.util.function.Consumer;

import io.helidon.data.Page;
import io.helidon.data.PageRequest;
import io.helidon.data.Slice;
import io.helidon.service.registry.Service;

/**
 * JDBC client used by generated Helidon Data JDBC repositories and imperative JDBC Data applications.
 * <p>
 * The client is thread-safe and does not expose live JDBC resources. Each fluent {@link Statement} is mutable,
 * thread-confined, and intended to describe one execution at a time. A statement created by {@link #execute(String)}
 * only captures SQL, parameters, batch entries, OUT parameter registrations, generated-key settings, and statement
 * options. It does not acquire a JDBC connection or send SQL to the database.
 * <p>
 * A database call happens when a terminal method is invoked, such as {@link Statement#list(JdbcRowMapper)},
 * {@link Statement#single(JdbcRowMapper)}, {@link Statement#optional(JdbcRowMapper)},
 * {@link Statement#slice(PageRequest, JdbcRowMapper)}, {@link Statement#page(PageRequest, String, JdbcRowMapper)},
 * {@link Statement#openRows(JdbcRowMapper)}, {@link Statement#withRows(JdbcRowMapper, Consumer)},
 * {@link Statement#updateCount()}, {@link Statement#batchUpdateCounts()}, {@link Statement#outParams()},
 * {@link Statement#outCursor(String, JdbcRowMapper)}, or {@link Statement#generatedKeys(JdbcRowMapper)}. The terminal
 * method asks the runtime runner to obtain a connection from the configured data source, create a prepared or callable
 * statement, apply statement options, bind parameters, and execute the JDBC operation.
 * <p>
 * Materialized terminals copy required JDBC results into an internal execution result, close JDBC resources, and then
 * reduce the detached result to the requested type. Streaming terminals use a separate cursor lifecycle and do not
 * create that internal materialized result. {@link Statement#withRows(JdbcRowMapper, Consumer)} closes the cursor before
 * returning. The caller of {@link Statement#openRows(JdbcRowMapper)} owns the returned closeable result and must close
 * it, normally with try-with-resources.
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
 * // The list terminal opens JDBC resources, materializes rows, closes resources, and maps the execution result.
 * List<Pokemon> pokemon = statement.list(row -> new Pokemon(row.value("id", Long.class),
 *                                                           row.value("name", String.class)));
 *
 * // Update terminals follow the same lifecycle and reduce the execution result to an update count.
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
     * Mutable, thread-confined description of one JDBC statement execution.
     * <p>
     * Configuration methods do not access the database. A terminal snapshots the configured values before execution.
     * Do not modify or execute the same instance concurrently.
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
         * Limit row reads to selected result-set column labels.
         * <p>
         * This is an optimization for generated mappers and imperative callers that know exactly which columns they
         * read. Materialized and streaming row mappers see only the selected columns.
         *
         * @param labels selected column labels
         * @return this statement
         */
        Statement readColumns(String... labels);

        /**
         * Limit row reads to selected one-based result-set column indexes.
         * <p>
         * This is an optimization for scalar, generated-key, and streaming mappers that read known column positions.
         * Materialized and streaming row mappers see only the selected columns.
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
         * Execute a row-producing operation and return a closeable, pull-based result.
         * <p>
         * This terminal acquires a logical connection, prepares and executes the statement, and keeps the JDBC operation
         * open while rows are consumed. The returned result is single-use, sequential, and thread-confined. It closes
         * automatically on exhaustion, but callers must use try-with-resources to cover early termination and failures.
         * <p>
         * The mapper receives a snapshot of the current row rather than the live JDBC result set and runs inside the
         * cursor's resource scope. This terminal does not create a materialized {@code JdbcExecutionResult}.
         *
         * @param mapper row mapper
         * @param <T>    mapped row type
         * @return closeable streaming rows
         */
        <T> JdbcResultIterable<T> openRows(JdbcRowMapper<T> mapper);

        /**
         * Execute a row-producing operation and consume its rows inside a provider-owned resource scope.
         * <p>
         * The action receives a single-use sequential iterable. The result set, statement, and logical connection handle
         * are closed before this method returns, including when the action stops early or throws. This is the preferred
         * streaming terminal for generated declarative repository methods. The action must consume the iterable during
         * the call and must not retain it for later use.
         *
         * @param mapper row mapper
         * @param action scoped row action
         * @param <T>    mapped row type
         */
        <T> void withRows(JdbcRowMapper<T> mapper, Consumer<? super Iterable<T>> action);

        /**
         * Execute repository-provided, SQL-level pagination and return one materialized slice.
         * <p>
         * The SQL must contain the named parameter {@code :__helidon_page_size}. Offset pagination must also contain
         * {@code :__helidon_page_offset}. If the offset parameter is absent, the statement is treated as a keyset query:
         * application parameters define the cursor predicate and {@link PageRequest#page()} must be {@code 0}.
         * Pagination syntax remains part of the SQL so the database, rather than this client, selects the requested rows.
         * The SQL must define deterministic ordering suitable for its offset or keyset predicate.
         *
         * @param request pagination request
         * @param mapper  row mapper
         * @param <T>     mapped row type
         * @return materialized slice
         */
        <T> Slice<T> slice(PageRequest request, JdbcRowMapper<T> mapper);

        /**
         * Execute repository-provided, SQL-level offset pagination and return one materialized page.
         * <p>
         * The page SQL must contain {@code :__helidon_page_offset} and {@code :__helidon_page_size}. The count SQL must
         * apply the same application filters, omit pagination, and return exactly one non-null, non-negative integral
         * value. The two statements participate in the caller's transaction when the configured data source is
         * transaction aware. Without a transaction, they are independent database operations and need not observe the
         * same database snapshot. The page SQL must define deterministic ordering.
         *
         * @param request  pagination request
         * @param countSql SQL that returns the total number of matching rows
         * @param mapper   row mapper
         * @param <T>      mapped row type
         * @return materialized page
         */
        <T> Page<T> page(PageRequest request, String countSql, JdbcRowMapper<T> mapper);

        /**
         * Execute the operation, consume all JDBC results, and discard the detached values.
         * <p>
         * This terminal is useful for declarative {@code void} methods and SQL whose result shape is intentionally not
         * part of the application contract.
         */
        void discard();

        /**
         * Execute ordinary SQL and reduce exactly one scalar row or one update count.
         * <p>
         * This terminal does not classify SQL by its text. A row result is read from its first column; an update result
         * is converted from its single JDBC update count. Multiple or mixed direct results are rejected.
         *
         * @param type requested scalar type
         * @param <T> scalar type
         * @return mapped scalar value
         */
        <T> T resultScalar(Class<T> type);

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
         * @return the single update count
         */
        Number updateCount();

        /**
         * Execute an update, DDL, or other statement and return its update count as {@code int}.
         *
         * @return the single update count
         */
        default int updateCountInt() {
            return updateCount().intValue();
        }

        /**
         * Execute an update, DDL, or other statement and return its update count as {@code long}.
         *
         * @return the single update count
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
