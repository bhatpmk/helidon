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
 * Mutable statement stage; no method in this class acquires JDBC resources before a terminal operation.
 */
final class JdbcStatement implements JdbcClient.Statement {
    private final JdbcRunner runner;
    private final String sql;
    private final JdbcOperation.Bind[] binds;
    private JdbcExecutionOptions options = JdbcExecutionOptions.EMPTY;
    private boolean terminalStarted;

    JdbcStatement(JdbcRunner runner, String sql, int parameterCount) {
        this.runner = runner;
        this.sql = sql;
        this.binds = new JdbcOperation.Bind[parameterCount];
    }

    @Override
    public JdbcClient.Statement options(JdbcExecutionOptions options) {
        ensureMutable();
        this.options = Objects.requireNonNull(options, "Execution options must not be null");
        return this;
    }

    @Override
    public JdbcClient.Statement bind(int index, Object value) {
        Objects.requireNonNull(value, "Untyped bind value must not be null; use bindNull or the typed bind overload");
        if (!JdbcRow.supportedScalar(value.getClass())) {
            throw new IllegalArgumentException("Unsupported JDBC bind value type: " + value.getClass().getTypeName());
        }
        return bindInternal(index, new JdbcOperation.Bind(value, null));
    }

    @Override
    public JdbcClient.Statement bind(int index, Object value, SQLType type) {
        return bindInternal(index,
                            new JdbcOperation.Bind(value,
                                                   Objects.requireNonNull(type, "JDBC bind type must not be null")));
    }

    @Override
    public JdbcClient.Statement bindNull(int index, SQLType type) {
        return bind(index, null, type);
    }

    @Override
    public long execute() {
        return runner.execute(operation(JdbcPreparationPlan.update()));
    }

    @Override
    public <R> R reduce(JdbcClient.RowReducer<R> reducer) {
        Objects.requireNonNull(reducer, "Row reducer must not be null");
        return runner.reduce(operation(JdbcPreparationPlan.query()), reducer);
    }

    @Override
    public <T> JdbcClient.Rows<T> map(JdbcClient.RowMapper<T> mapper) {
        ensureMutable();
        return new JdbcRows<>(this,
                              Objects.requireNonNull(mapper, "Row mapper must not be null"),
                              JdbcPreparationPlan.query());
    }

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

    @Override
    public <T> JdbcClient.Rows<T> generatedKeys(JdbcClient.RowMapper<T> mapper, String... columnNames) {
        ensureMutable();
        Objects.requireNonNull(mapper, "Generated-key mapper must not be null");
        return new JdbcRows<>(this, mapper, JdbcPreparationPlan.generatedKeys(columnNames));
    }

    <T> T one(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.one(operation(plan), mapper);
    }

    <T> java.util.Optional<T> optional(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.optional(operation(plan), mapper);
    }

    <T> java.util.List<T> list(JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        return runner.list(operation(plan), mapper);
    }

    <T> void withRows(JdbcClient.RowMapper<T> mapper,
                      JdbcPreparationPlan plan,
                      java.util.function.Consumer<? super Iterable<T>> action) {
        runner.withRows(operation(plan), mapper, action);
    }

    <T> void forEach(JdbcClient.RowMapper<T> mapper,
                     JdbcPreparationPlan plan,
                     java.util.function.Consumer<? super T> action) {
        runner.forEach(operation(plan), mapper, action);
    }

    <T> boolean forEachWhile(JdbcClient.RowMapper<T> mapper,
                             JdbcPreparationPlan plan,
                             java.util.function.Predicate<? super T> action) {
        return runner.forEachWhile(operation(plan), mapper, action);
    }

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

    private JdbcOperation operation(JdbcPreparationPlan plan) {
        ensureMutable();
        for (int i = 0; i < binds.length; i++) {
            if (binds[i] == null) {
                throw new IllegalStateException("Missing bind value at JDBC position " + (i + 1));
            }
        }
        terminalStarted = true;
        return new JdbcOperation(sql, binds.clone(), options, plan);
    }

    private void ensureMutable() {
        if (terminalStarted) {
            throw new IllegalStateException("A JDBC statement stage permits exactly one terminal operation");
        }
    }
}
