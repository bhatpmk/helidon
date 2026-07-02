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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.data.Page;
import io.helidon.data.PageRequest;
import io.helidon.data.Slice;

final class JdbcClientImpl implements JdbcClient {

    private final String name;
    private final JdbcRunner runner;

    JdbcClientImpl(String name, DataSource dataSource, Optional<Path> initScript) {
        this.name = name;
        this.runner = new JdbcRunner(dataSource);
        initScript.ifPresent(this::runInitScript);
    }

    @Override
    public Statement execute(String sql) {
        return new StatementImpl(sql);
    }

    private void runInitScript(Path script) {
        for (String statement : SqlScripts.statements(name, script)) {
            execute(statement).updateCount();
        }
    }

    private final class StatementImpl implements Statement {

        private final String sql;
        private final List<JdbcParameter> parameters = new ArrayList<>();
        private final List<List<JdbcParameter>> batchParameters = new ArrayList<>();
        private final List<JdbcOutParameter> outParameters = new ArrayList<>();
        private List<Integer> generatedKeyColumnIndexes = List.of();
        private List<String> generatedKeyColumns = List.of();
        private JdbcColumnSelection columnSelection = JdbcColumnSelection.ALL;
        private JdbcStatementOptions options = JdbcStatementOptions.DEFAULT;

        private StatementImpl(String sql) {
            this.sql = Objects.requireNonNull(sql, "SQL must not be null");
        }

        @Override
        public Statement param(Object value) {
            parameters.add(JdbcParameter.create(value));
            return this;
        }

        @Override
        public Statement param(String name, Object value) {
            parameters.add(JdbcParameter.create(name, value));
            return this;
        }

        @Override
        public Statement params(List<JdbcParameter> parameters) {
            Objects.requireNonNull(parameters, "Parameters must not be null");
            this.parameters.addAll(parameters);
            return this;
        }

        @Override
        public Statement readColumns(String... labels) {
            Objects.requireNonNull(labels, "Column labels must not be null");
            this.columnSelection = JdbcColumnSelection.labels(labels);
            return this;
        }

        @Override
        public Statement readColumns(int firstIndex, int... additionalIndexes) {
            Objects.requireNonNull(additionalIndexes, "Additional column indexes must not be null");
            this.columnSelection = JdbcColumnSelection.indexes(firstIndex, additionalIndexes);
            return this;
        }

        @Override
        public Statement addBatch() {
            batchParameters.add(List.copyOf(parameters));
            parameters.clear();
            return this;
        }

        @Override
        public Statement addBatch(List<JdbcParameter> parameters) {
            Objects.requireNonNull(parameters, "Batch parameters must not be null");
            batchParameters.add(List.copyOf(parameters));
            return this;
        }

        @Override
        public Statement fetchSize(int fetchSize) {
            this.options = options.withFetchSize(fetchSize);
            return this;
        }

        @Override
        public Statement maxRows(long maxRows) {
            this.options = options.withMaxRows(maxRows);
            return this;
        }

        @Override
        public Statement queryTimeout(int seconds) {
            this.options = options.withQueryTimeoutSeconds(seconds);
            return this;
        }

        @Override
        public Statement resultSet(int type, int concurrency) {
            this.options = options.withResultSet(type, concurrency);
            return this;
        }

        @Override
        public Statement resultSet(int type, int concurrency, int holdability) {
            this.options = options.withResultSet(type, concurrency, holdability);
            return this;
        }

        @Override
        public Statement generatedKeyColumns(String... columnNames) {
            Objects.requireNonNull(columnNames, "Generated-key column names must not be null");
            if (!generatedKeyColumnIndexes.isEmpty()) {
                throw new IllegalStateException("Generated-key column indexes are already configured");
            }
            List<String> columns = new ArrayList<>(columnNames.length);
            for (String columnName : columnNames) {
                if (columnName == null || columnName.isBlank()) {
                    throw new IllegalArgumentException("Generated-key column name must not be blank");
                }
                columns.add(columnName);
            }
            this.generatedKeyColumns = List.copyOf(columns);
            return this;
        }

        @Override
        public Statement generatedKeyColumns(int firstColumnIndex, int... additionalColumnIndexes) {
            Objects.requireNonNull(additionalColumnIndexes, "Additional generated-key column indexes must not be null");
            if (!generatedKeyColumns.isEmpty()) {
                throw new IllegalStateException("Generated-key column names are already configured");
            }
            List<Integer> columns = new ArrayList<>(additionalColumnIndexes.length + 1);
            columns.add(firstColumnIndex);
            for (int columnIndex : additionalColumnIndexes) {
                columns.add(columnIndex);
            }
            for (int columnIndex : columns) {
                if (columnIndex < 1) {
                    throw new IllegalArgumentException("Generated-key column index must be positive");
                }
            }
            this.generatedKeyColumnIndexes = List.copyOf(columns);
            return this;
        }

        @Override
        public Statement outParam(int index, String name, int sqlType) {
            outParameters.add(JdbcOutParameter.scalar(index, name, sqlType));
            return this;
        }

        @Override
        public Statement outCursor(int index, String name, int sqlType) {
            outParameters.add(JdbcOutParameter.cursor(index, name, sqlType));
            return this;
        }

        @Override
        public <T> List<T> list(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                parameters,
                                                                                options,
                                                                                columnSelection,
                                                                                0));
                return JdbcReducers.list(result, mapper);
            });
        }

        @Override
        public <T> JdbcResultIterable<T> openRows(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> runner.openRows(JdbcOperation.query(sql,
                                                                     parameters,
                                                                     options,
                                                                     columnSelection,
                                                                     0),
                                                  mapper));
        }

        @Override
        public <T> void withRows(JdbcRowMapper<T> mapper, Consumer<? super Iterable<T>> action) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            Objects.requireNonNull(action, "Row action must not be null");
            try (JdbcResultIterable<T> rows = openRows(mapper)) {
                action.accept(rows);
            }
        }

        @Override
        public <T> Slice<T> slice(PageRequest request, JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                List<JdbcParameter> pageParameters = JdbcPagination.sliceParameters(sql, parameters, request);
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                pageParameters,
                                                                                options,
                                                                                columnSelection,
                                                                                request.size()));
                return Slice.create(request, JdbcReducers.list(result, mapper));
            });
        }

        @Override
        public <T> Page<T> page(PageRequest request, String countSql, JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcPagination.PageParameters pageParameters =
                        JdbcPagination.pageParameters(sql, countSql, parameters, request);
                JdbcExecutionResult countResult = runner.execute(JdbcOperation.query(countSql,
                                                                                      pageParameters.countParameters(),
                                                                                      options,
                                                                                      JdbcColumnSelection.indexes(1),
                                                                                      2));
                int totalSize = JdbcReducers.pageTotal(countResult);
                if (totalSize == 0 || pageParameters.offset() >= totalSize) {
                    return Page.create(request, List.of(), totalSize);
                }

                JdbcExecutionResult pageResult = runner.execute(JdbcOperation.query(sql,
                                                                                     pageParameters.pageParameters(),
                                                                                     options,
                                                                                     columnSelection,
                                                                                     request.size()));
                return Page.create(request, JdbcReducers.list(pageResult, mapper), totalSize);
            });
        }

        @Override
        public void discard() {
            execute(() -> {
                runner.execute(JdbcOperation.update(sql, parameters, options));
                return null;
            });
        }

        @Override
        public <T> T resultScalar(Class<T> type) {
            Objects.requireNonNull(type, "Scalar type must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                parameters,
                                                                                options,
                                                                                columnSelection,
                                                                                2));
                return JdbcReducers.scalar(result, type);
            });
        }

        @Override
        public <T> T single(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                parameters,
                                                                                options,
                                                                                columnSelection,
                                                                                2));
                return JdbcReducers.item(result, mapper);
            });
        }

        @Override
        public <T> T singleOrNull(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                parameters,
                                                                                options,
                                                                                columnSelection,
                                                                                2));
                return JdbcReducers.itemOrNull(result, mapper);
            });
        }

        @Override
        public <T> Optional<T> optional(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.query(sql,
                                                                                parameters,
                                                                                options,
                                                                                columnSelection,
                                                                                2));
                return JdbcReducers.optional(result, mapper);
            });
        }

        @Override
        public Number updateCount() {
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.update(sql, parameters, options));
                return JdbcReducers.updateCount(result);
            });
        }

        @Override
        public long[] batchUpdateCounts() {
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.batch(sql, batchParameters, options));
                return JdbcReducers.batchUpdateCounts(result);
            });
        }

        @Override
        public Map<String, Object> outParams() {
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.call(sql,
                                                                               parameters,
                                                                               outParameters,
                                                                               options,
                                                                               columnSelection,
                                                                               0));
                return JdbcReducers.outParams(result);
            });
        }

        @Override
        public <T> T outParam(String name, Class<T> type) {
            Objects.requireNonNull(name, "OUT parameter name must not be null");
            Objects.requireNonNull(type, "OUT parameter type must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.call(sql,
                                                                               parameters,
                                                                               outParameters,
                                                                               options,
                                                                               columnSelection,
                                                                               0));
                return JdbcReducers.outParam(result, name, type);
            });
        }

        @Override
        public <T> List<T> outCursor(String name, JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(name, "OUT cursor name must not be null");
            Objects.requireNonNull(mapper, "OUT cursor row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.call(sql,
                                                                               parameters,
                                                                               outParameters,
                                                                               options,
                                                                               columnSelection,
                                                                               0));
                return JdbcReducers.outCursor(result, name, mapper);
            });
        }

        @Override
        public <T> List<T> generatedKeys(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.update(sql,
                                                                                 parameters,
                                                                                 options,
                                                                                 generatedKeysRequest(),
                                                                                 columnSelection,
                                                                                 0));
                return JdbcReducers.generatedKeys(result, mapper);
            });
        }

        @Override
        public <T> T generatedKey(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.update(sql,
                                                                                 parameters,
                                                                                 options,
                                                                                 generatedKeysRequest(),
                                                                                 columnSelection,
                                                                                 2));
                return JdbcReducers.generatedKey(result, mapper);
            });
        }

        @Override
        public <T> Optional<T> optionalGeneratedKey(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcExecutionResult result = runner.execute(JdbcOperation.update(sql,
                                                                                 parameters,
                                                                                 options,
                                                                                 generatedKeysRequest(),
                                                                                 columnSelection,
                                                                                 2));
                return JdbcReducers.optionalGeneratedKey(result, mapper);
            });
        }

        private GeneratedKeysRequest generatedKeysRequest() {
            if (generatedKeyColumnIndexes.isEmpty() && generatedKeyColumns.isEmpty()) {
                return GeneratedKeysRequest.any();
            }
            if (!generatedKeyColumnIndexes.isEmpty()) {
                return GeneratedKeysRequest.columnIndexes(generatedKeyColumnIndexes);
            }
            return GeneratedKeysRequest.columnNames(generatedKeyColumns);
        }

        private <T> T execute(Execution<T> execution) {
            try {
                return execution.run();
            } catch (DataException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new DataException("Execution of JDBC statement failed.", e);
            }
        }
    }

    @FunctionalInterface
    private interface Execution<T> {
        T run();
    }
}
