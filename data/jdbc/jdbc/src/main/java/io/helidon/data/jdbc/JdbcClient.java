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
         * Applies immutable options to this statement description.
         * <p>
         * Repeated calls overlay explicitly configured fields. No JDBC work is performed until a terminal operation.
         *
         * @param options immutable statement options, must not be {@code null}
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
         * Use this overload when the value requires an explicit JDBC type. SQL {@code NULL} is represented only by
         * {@link #bindNull(int, SQLType)}.
         *
         * @param index one-based parameter position
         * @param value value to bind, must not be {@code null}
         * @param type JDBC type, must not be {@code null}
         * @return this statement stage
         * @throws NullPointerException if {@code value} or {@code type} is {@code null}
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
         * This method never performs reflective bean or record mapping. Scalar mapping requires a non-null column for
         * both primitive and reference classes. Applications that model SQL {@code NULL} use an explicit mapper with
         * {@link Row#optional(int, Class)}.
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

        /**
         * Invokes an input-only stored procedure that must not produce result channels or output values.
         *
         * @param call immutable callable parameter layout, must not be {@code null}
         * @throws NullPointerException if {@code call} is {@code null}
         * @throws IllegalArgumentException if the layout is invalid for this statement
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if preparation, execution, result validation, or cleanup fails
         */
        void call(JdbcCall call);

        /**
         * Invokes a stored procedure or function and returns its detached scalar output values.
         * <p>
         * This terminal accepts only OUT, INOUT, and function-return values that use supported scalar Java types. Cursor
         * outputs and direct result channels require a callback-scoped call. The provider reads every value and closes
         * all JDBC resources before this method returns.
         *
         * @param call immutable callable parameter layout, must not be {@code null}
         * @return detached scalar output values
         * @throws NullPointerException if {@code call} is {@code null}
         * @throws IllegalArgumentException if the layout contains a cursor or has no scalar outputs
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if execution produces a direct result channel or JDBC processing fails
         */
        CallOutputValues callForOutputs(JdbcCall call);

        /**
         * Invokes a stored procedure or function and consumes its results through a scoped callback.
         *
         * @param call immutable callable parameter layout, must not be {@code null}
         * @param request immutable callback request, must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if the layout is invalid for this statement
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if preparation, execution, callback processing, or cleanup fails
         */
        void call(JdbcCall call, JdbcResultRequest.Call request);

        /**
         * Invokes a stored procedure or function and constructs a detached application result in a scoped callback.
         *
         * @param call immutable callable parameter layout, must not be {@code null}
         * @param request immutable callback request, must not be {@code null}
         * @param <R> detached result type
         * @return callback result, never {@code null}
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if the layout is invalid for this statement
         * @throws IllegalStateException if this statement has already executed or has missing bindings
         * @throws DataException if preparation, execution, callback processing, or cleanup fails
         */
        <R> R call(JdbcCall call, JdbcResultRequest.CallWith<R> request);
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
         * @return the only row, never {@code null}
         * @throws io.helidon.data.NoResultException if no row exists
         * @throws io.helidon.data.NonUniqueResultException if more than one row exists
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        T one();

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
         * Materializes all mapped rows in encounter order.
         *
         * @return mapped rows without {@code null} elements; never {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        List<T> list();

        /**
         * Visits every mapped row synchronously using constant result-buffer memory.
         *
         * @param request immutable visit-all request, must not be {@code null}
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        void visitAll(JdbcResultRequest.VisitAll<T> request);

        /**
         * Visits mapped rows until exhaustion or until the predicate returns {@code false}.
         *
         * @param request immutable predicate traversal request, must not be {@code null}
         * @return {@code true} when all rows were visited, or {@code false} when traversal stopped early
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this stage has already executed
         * @throws DataException if JDBC processing fails
         */
        boolean visitWhile(JdbcResultRequest.VisitWhile<T> request);
    }

    /**
     * Callback-scoped view of one stored-procedure or function invocation.
     * <p>
     * Direct JDBC result channels must be consumed or discarded before cursor and scalar outputs are accessed. This
     * view exposes no JDBC object and becomes invalid when the enclosing call callback returns.
     */
    interface CallScope {
        /**
         * Returns the ordered direct result channels reported by callable execution.
         *
         * @return callback-scoped direct results
         * @throws IllegalStateException if the call callback is no longer active
         */
        CallResults results();

        /**
         * Returns the declared cursor and scalar outputs.
         *
         * @return callback-scoped outputs
         * @throws IllegalStateException if the call callback is no longer active
         */
        CallOutputs outputs();
    }

    /**
     * Ordered direct result sets and update counts reported by a callable statement.
     */
    interface CallResults {
        /**
         * Visits every direct result channel in JDBC encounter order.
         * <p>
         * Each row channel must invoke exactly one mapping terminal or {@link CallRows#discard()} before the visitor
         * returns from that channel.
         *
         * @param visitor result visitor, must not be {@code null}
         * @throws NullPointerException if {@code visitor} is {@code null}
         * @throws IllegalStateException if results were already consumed or the callback is no longer active
         * @throws DataException if result advancement or cleanup fails
         */
        void visit(CallResultVisitor visitor);

        /**
         * Drains and closes every direct result channel.
         *
         * @throws IllegalStateException if results were already consumed or the callback is no longer active
         * @throws DataException if result advancement or cleanup fails
         */
        void discard();
    }

    /**
     * Synchronous visitor for direct callable result sets and update counts.
     */
    @FunctionalInterface
    interface CallResultVisitor {
        /**
         * Visits one direct result set.
         *
         * @param resultSetIndex zero-based result-set index among direct row channels
         * @param rows callback-scoped row-bearing channel
         */
        void rows(int resultSetIndex, CallRows rows);

        /**
         * Visits one direct update count.
         * <p>
         * The default implementation explicitly discards the count.
         *
         * @param itemIndex zero-based index among all direct result channels
         * @param count large JDBC update count
         */
        default void updateCount(int itemIndex, long count) {
        }
    }

    /**
     * Unmapped, callback-scoped rows from a direct callable result or cursor output.
     */
    interface CallRows {
        /**
         * Selects an explicit mapper for this row channel.
         *
         * @param mapper row mapper, must not be {@code null}
         * @param <T> mapped row type
         * @return scoped mapped-row terminals
         * @throws NullPointerException if {@code mapper} is {@code null}
         * @throws IllegalStateException if this channel was already selected or is no longer active
         */
        <T> ScopedRows<T> map(RowMapper<T> mapper);

        /**
         * Selects non-null column-one scalar mapping for this row channel.
         *
         * @param scalarType supported scalar type, must not be {@code null}
         * @param <T> mapped scalar type
         * @return scoped mapped-row terminals
         * @throws NullPointerException if {@code scalarType} is {@code null}
         * @throws IllegalArgumentException if the scalar type is unsupported
         * @throws IllegalStateException if this channel was already selected or is no longer active
         */
        <T> ScopedRows<T> map(Class<T> scalarType);

        /**
         * Explicitly discards this row channel without mapping it.
         *
         * @throws IllegalStateException if this channel was already selected or is no longer active
         * @throws DataException if closing the channel fails
         */
        void discard();
    }

    /**
     * Mapping, cardinality, reduction, and traversal terminals for one callback-scoped callable row channel.
     * <p>
     * Exactly one terminal may be invoked. The stage becomes invalid when its terminal completes or the enclosing call
     * callback returns, whichever happens first.
     *
     * @param <T> mapped row type
     */
    interface ScopedRows<T> {
        /**
         * Returns exactly one mapped row.
         *
         * @return the only row, never {@code null}
         */
        T one();

        /**
         * Returns zero or one mapped row.
         *
         * @return optional mapped row
         */
        Optional<T> optional();

        /**
         * Materializes all mapped rows in encounter order.
         *
         * @return detached mapped rows without {@code null} elements
         */
        List<T> list();

        /**
         * Reduces all physical rows into one detached value.
         *
         * @param reducer result-set-scoped reducer, must not be {@code null}
         * @param <R> reduced result type
         * @return detached reduced result, never {@code null}
         */
        <R> R reduce(RowReducer<R> reducer);

        /**
         * Visits every mapped row synchronously.
         *
         * @param request visit-all request, must not be {@code null}
         */
        void visitAll(JdbcResultRequest.VisitAll<T> request);

        /**
         * Visits rows until exhaustion or predicate-directed termination.
         *
         * @param request visit-while request, must not be {@code null}
         * @return {@code true} after exhaustion, or {@code false} after normal early termination
         */
        boolean visitWhile(JdbcResultRequest.VisitWhile<T> request);

        /**
         * Explicitly discards all rows in this channel.
         */
        void discard();
    }

    /**
     * Scalar OUT, INOUT, and function-return values from a callable statement.
     * <p>
     * A value returned by {@link Statement#callForOutputs(JdbcCall)} is detached and remains valid after the terminal
     * closes its JDBC resources. The callback-scoped {@link CallOutputs} subtype additionally enforces callable result
     * ordering while its owning callback remains active.
     */
    interface CallOutputValues {
        /**
         * Reads a required scalar output by logical name.
         *
         * @param name declared output name, must not be {@code null} or blank
         * @param type declared output Java type, must not be {@code null}
         * @param <T> output type
         * @return non-null output value
         */
        <T> T required(String name, Class<T> type);

        /**
         * Reads a required scalar output by one-based JDBC position.
         *
         * @param index declared output position
         * @param type declared output Java type, must not be {@code null}
         * @param <T> output type
         * @return non-null output value
         */
        <T> T required(int index, Class<T> type);

        /**
         * Reads a nullable scalar output by logical name.
         *
         * @param name declared output name, must not be {@code null} or blank
         * @param type declared output Java type, must not be {@code null}
         * @param <T> output type
         * @return optional output value
         */
        <T> Optional<T> optional(String name, Class<T> type);

        /**
         * Reads a nullable scalar output by one-based JDBC position.
         *
         * @param index declared output position
         * @param type declared output Java type, must not be {@code null}
         * @param <T> output type
         * @return optional output value
         */
        <T> Optional<T> optional(int index, Class<T> type);
    }

    /**
     * Callback-scoped cursor and scalar outputs from a callable statement.
     * <p>
     * Direct results must be complete before any output is accessed. Cursor outputs are consumed or discarded one at a
     * time before scalar outputs are read. This ordering avoids driver behavior that invalidates open result sets when
     * an OUT value is accessed.
     */
    interface CallOutputs extends CallOutputValues {

        /**
         * Opens a declared cursor output by logical name.
         *
         * @param name declared cursor name, must not be {@code null} or blank
         * @return callback-scoped cursor rows
         */
        CallRows cursor(String name);

        /**
         * Opens a declared cursor output by one-based JDBC position.
         *
         * @param index declared cursor position
         * @return callback-scoped cursor rows
         */
        CallRows cursor(int index);

        /**
         * Retrieves and closes a declared cursor output without consuming rows.
         *
         * @param name declared cursor name, must not be {@code null} or blank
         */
        void discardCursor(String name);

        /**
         * Retrieves and closes a declared cursor output without consuming rows.
         *
         * @param index declared cursor position
         */
        void discardCursor(int index);
    }

    /**
     * Maps one callback-scoped row to an application value.
     * <p>
     * Applications may register an implementation as a service. A generated declarative repository resolves a mapper
     * selected by {@code Jdbc.RowMapper(SomeMapper.class)} by its concrete service type. The marker form
     * {@code Jdbc.RowMapper} requires this contract with the exact {@code T} type. For an unannotated, non-scalar query,
     * the repository also resolves a mapper by this generic contract, but the service is optional when the generator can
     * map a record directly. A present service overrides the generated record mapper.
     * <p>
     * A mapper held by a singleton repository can be invoked concurrently and therefore must be stateless or
     * thread-safe. The provider owns the JDBC resources and exposes only the callback-scoped {@link Row} view.
     *
     * @param <T> mapped value type
     */
    @Service.Contract
    @FunctionalInterface
    interface RowMapper<T> {

        /**
         * Maps the current row.
         * <p>
         * The row is valid only for this invocation and must not be retained.
         *
         * @param row current row, never {@code null}
         * @return mapped value, never {@code null}
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
         * @return logical result, never {@code null}
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
         * Reads an optional value by one-based column index.
         *
         * @param index one-based result column index
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return converted value, or an empty optional for SQL {@code NULL}
         * @throws NullPointerException if {@code type} is {@code null}
         * @throws IllegalArgumentException if the index or target type is unsupported
         * @throws DataException if the value cannot be read or converted
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> Optional<T> optional(int index, Class<T> type);

        /**
         * Reads an optional value by column label.
         *
         * @param label result column label, must not be {@code null} or blank
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return converted value, or an empty optional for SQL {@code NULL}
         * @throws NullPointerException if {@code label} or {@code type} is {@code null}
         * @throws IllegalArgumentException if the label is blank or the target type is unsupported
         * @throws DataException if the label is absent or the value cannot be read or converted
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> Optional<T> optional(String label, Class<T> type);

        /**
         * Reads a required value by one-based column index.
         *
         * @param index one-based result column index
         * @param type supported target scalar type, must not be {@code null}
         * @param <T> target type
         * @return non-null converted value
         * @throws NullPointerException if {@code type} is {@code null}
         * @throws IllegalArgumentException if the index or target type is unsupported
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
         * @throws NullPointerException if {@code label} or {@code type} is {@code null}
         * @throws IllegalArgumentException if the label is blank or the target type is unsupported
         * @throws DataException if the label is absent, the value is SQL {@code NULL}, or conversion fails
         * @throws IllegalStateException if this row is no longer callback-scoped
         */
        <T> T required(String label, Class<T> type);
    }
}
