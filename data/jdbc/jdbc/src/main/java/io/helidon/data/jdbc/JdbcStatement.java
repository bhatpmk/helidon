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
import java.util.Objects;

/**
 * Package-private implementation of the public single-use statement stage.
 *
 * <p>This object stores SQL, bind values, and execution options while a
 * generated repository assembles a call. It never acquires a connection or
 * prepares a JDBC statement. A terminal freezes the state into
 * {@link JdbcOperation} and delegates all I/O to {@link JdbcRunner}.</p>
 */
final class JdbcStatement implements JdbcClient.Statement {
    /** Shared execution engine for all terminals. */
    private final JdbcRunner runner;
    /** SQL text, already in positional form for declarative generated code. */
    private final String sql;
    /** One slot per positional JDBC marker. */
    private final JdbcOperation.Bind[] binds;
    /** Options accumulated before the terminal is selected. */
    private JdbcStatementOptions options = JdbcStatementOptions.EMPTY;
    /** Guards the statement against a second terminal or later mutation. */
    private boolean terminalStarted;

    /**
     * Creates an empty statement stage with the required bind capacity.
     *
     * @param runner execution engine
     * @param sql SQL text
     * @param parameterCount number of positional JDBC markers
     */
    JdbcStatement(JdbcRunner runner, String sql, int parameterCount) {
        this.runner = runner;
        this.sql = sql;
        // Array indexes are zero-based internally, while public JDBC positions are one-based.
        this.binds = new JdbcOperation.Bind[parameterCount];
    }

    /**
     * Stores per-operation options without performing JDBC I/O.
     *
     * @param options options to apply at terminal execution
     * @return this statement stage
     */
    @Override
    public JdbcClient.Statement options(JdbcStatementOptions options) {
        ensureMutable();
        this.options = Objects.requireNonNull(options, "Execution options must not be null");
        return this;
    }

    /**
     * Stores an untyped non-null scalar at a one-based JDBC position.
     *
     * @param index one-based parameter position
     * @param value supported non-null scalar value
     * @return this statement stage
     */
    @Override
    public JdbcClient.Statement bind(int index, Object value) {
        Objects.requireNonNull(value, "Untyped bind value must not be null; use bindNull or the typed bind overload");
        if (!JdbcRow.supportedScalar(value.getClass())) {
            throw new IllegalArgumentException("Unsupported JDBC bind value type: " + value.getClass().getTypeName());
        }
        return bindInternal(index, new JdbcOperation.Bind(value, null));
    }

    /**
     * Stores a value and explicit JDBC type at a one-based position.
     *
     * @param index one-based parameter position
     * @param value value, possibly null when the type is explicit
     * @param type JDBC type
     * @return this statement stage
     */
    @Override
    public JdbcClient.Statement bind(int index, Object value, SQLType type) {
        return bindInternal(index,
                            new JdbcOperation.Bind(value,
                                                   Objects.requireNonNull(type, "JDBC bind type must not be null")));
    }

    /**
     * Stores an explicitly typed SQL NULL.
     *
     * @param index one-based parameter position
     * @param type JDBC type for the NULL
     * @return this statement stage
     */
    @Override
    public JdbcClient.Statement bindNull(int index, SQLType type) {
        return bind(index, null, type);
    }

    /**
     * Selects the update terminal. JDBC execution starts only after the
     * operation snapshot is created and the runner is invoked.
     *
     * @return large update count
     */
    @Override
    public long execute() {
        return runner.execute(operation(JdbcPreparationPlan.update()));
    }

    /**
     * Selects a reducer terminal for a query.
     *
     * @param reducer reducer that consumes callback-scoped rows
     * @param <R> logical result type
     * @return reduced result
     */
    @Override
    public <R> R reduce(JdbcClient.RowReducer<R> reducer) {
        Objects.requireNonNull(reducer, "Row reducer must not be null");
        return runner.reduce(operation(JdbcPreparationPlan.query()), reducer);
    }

    /**
     * Selects a reducer terminal and applies regular query-request settings before capturing the operation.
     *
     * @param reducer reducer that consumes callback-scoped rows
     * @param request regular query request
     * @param <R> logical result type
     * @return reduced result
     */
    @Override
    public <R> R reduce(JdbcClient.RowReducer<R> reducer, JdbcQueryRequest request) {
        Objects.requireNonNull(reducer, "Row reducer must not be null");
        apply(request);
        return runner.reduce(operation(JdbcPreparationPlan.query()), reducer);
    }

    /**
     * Attaches an application row mapper to a query.
     *
     * @param mapper mapper invoked once per physical row
     * @param <T> mapped value type
     * @return mapped terminal stage
     */
    @Override
    public <T> JdbcClient.Rows<T> map(JdbcClient.RowMapper<T> mapper) {
        ensureMutable();
        return new JdbcRows<>(this,
                              Objects.requireNonNull(mapper, "Row mapper must not be null"),
                              JdbcPreparationPlan.query());
    }

    /**
     * Attaches the fixed scalar mapper for column one.
     *
     * <p>Primitive types use {@code required} so SQL NULL cannot be silently
     * unboxed. Reference types use {@code get} so SQL NULL remains representable
     * by the mapped type.</p>
     *
     * @param scalarType supported scalar type
     * @param <T> scalar type
     * @return mapped terminal stage
     */
    @Override
    public <T> JdbcClient.Rows<T> map(Class<T> scalarType) {
        Objects.requireNonNull(scalarType, "Scalar type must not be null");
        if (!JdbcRow.supportedScalar(scalarType)) {
            throw new IllegalArgumentException("Unsupported JDBC scalar type: " + scalarType.getTypeName());
        }
        return map(scalarType.isPrimitive()
                           ? row -> row.required(1, scalarType)
                           : row -> row.get(1, scalarType));
    }

    /**
     * Selects generated-key result processing for an update.
     *
     * @param mapper mapper for generated-key rows
     * @param columnNames requested generated-key columns
     * @param <T> mapped key type
     * @return generated-key terminal stage
     */
    @Override
    public <T> JdbcClient.Rows<T> generatedKeys(JdbcClient.RowMapper<T> mapper, String... columnNames) {
        ensureMutable();
        Objects.requireNonNull(mapper, "Generated-key mapper must not be null");
        return new JdbcRows<>(this, mapper, JdbcPreparationPlan.generatedKeys(columnNames));
    }

    /**
     * Delegates the one-row cardinality terminal after capturing the operation.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param <T> mapped value type
     * @return one mapped value
     */
    <T> T one(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.one(operation(plan), mapper);
    }

    /**
     * Delegates exactly-one cardinality with regular request settings.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param request regular query request
     * @param <T> mapped value type
     * @return one mapped value
     */
    <T> T one(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan, JdbcQueryRequest request) {
        apply(request);
        return runner.one(operation(plan), mapper);
    }

    /**
     * Delegates the optional cardinality terminal after capturing the operation.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param <T> mapped value type
     * @return optional mapped value
     */
    <T> java.util.Optional<T> optional(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.optional(operation(plan), mapper);
    }

    /**
     * Delegates zero-or-one cardinality with regular request settings.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param request regular query request
     * @param <T> mapped value type
     * @return optional mapped value
     */
    <T> java.util.Optional<T> optional(JdbcClient.RowMapper<T> mapper,
                                       JdbcPreparationPlan plan,
                                       JdbcQueryRequest request) {
        apply(request);
        return runner.optional(operation(plan), mapper);
    }

    /**
     * Delegates the materializing list terminal after capturing the operation.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param <T> mapped value type
     * @return mapped values in JDBC encounter order
     */
    <T> java.util.List<T> list(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.list(operation(plan), mapper);
    }

    /**
     * Delegates list materialization with regular request settings.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param request regular query request
     * @param <T> mapped value type
     * @return mapped values in encounter order
     */
    <T> java.util.List<T> list(JdbcClient.RowMapper<T> mapper,
                               JdbcPreparationPlan plan,
                               JdbcQueryRequest request) {
        apply(request);
        return runner.list(operation(plan), mapper);
    }

    /**
     * Delegates push traversal that consumes every row.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param request consume-all request
     * @param <T> mapped value type
     */
    <T> void forEach(JdbcClient.RowMapper<T> mapper,
                     JdbcPreparationPlan plan,
                     JdbcQueryRequest.ForEach<T> request) {
        apply(request.options());
        runner.forEach(operation(plan), mapper, request);
    }

    /**
     * Delegates push traversal with predicate-directed early termination.
     *
     * @param mapper row mapper
     * @param plan preparation plan
     * @param request predicate traversal request
     * @param <T> mapped value type
     * @return true only after normal exhaustion
     */
    <T> boolean forEachWhile(JdbcClient.RowMapper<T> mapper,
                             JdbcPreparationPlan plan,
                             JdbcQueryRequest.ForEachWhile<T> request) {
        apply(request.options());
        return runner.forEachWhile(operation(plan), mapper, request);
    }

    /**
     * Applies a regular request without performing JDBC I/O.
     *
     * @param request regular query request
     */
    private void apply(JdbcQueryRequest request) {
        Objects.requireNonNull(request, "Query request must not be null");
        apply(request.options());
    }

    /**
     * Overlays invocation settings while preserving the statement's single-use invariant.
     *
     * @param requestOptions invocation-level statement settings
     */
    private void apply(JdbcStatementOptions requestOptions) {
        ensureMutable();
        // Explicit statement values remain when the request leaves the corresponding setting unset.
        options = options.overlay(requestOptions);
    }

    /**
     * Assigns one bind slot after checking mutability, position, and duplicates.
     *
     * @param index one-based JDBC position
     * @param bind immutable bind snapshot
     * @return this statement stage
     */
    private JdbcClient.Statement bindInternal(int index, JdbcOperation.Bind bind) {
        ensureMutable();
        if (index < 1 || index > binds.length) {
            throw new IllegalArgumentException("Bind index must be between 1 and " + binds.length + ": " + index);
        }
        if (binds[index - 1] != null) {
            throw new IllegalArgumentException("Bind position " + index + " was already assigned");
        }
        binds[index - 1] = bind;
        return this;
    }

    /**
     * Validates all binds and captures the single immutable operation snapshot.
     *
     * @param plan preparation and result contract
     * @return operation snapshot for the runner
     */
    private JdbcOperation operation(JdbcPreparationPlan plan) {
        ensureMutable();
        for (int i = 0; i < binds.length; i++) {
            if (binds[i] == null) {
                throw new IllegalStateException("Missing bind value at JDBC position " + (i + 1));
            }
        }
        // Mark before delegation so re-entrant or concurrent terminal calls cannot reuse this stage.
        terminalStarted = true;
        // The clone prevents later stage mutation from changing the runner's execution input.
        return new JdbcOperation(sql, binds.clone(), options, plan);
    }

    /**
     * Rejects mutation after a terminal has claimed this statement stage.
     */
    private void ensureMutable() {
        if (terminalStarted) {
            throw new IllegalStateException("A JDBC statement stage permits exactly one terminal operation");
        }
    }
}
