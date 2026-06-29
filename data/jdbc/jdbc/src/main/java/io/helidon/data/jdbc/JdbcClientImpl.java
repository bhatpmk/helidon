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
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.data.DataException;

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
        private List<String> generatedKeyColumns = List.of();
        private int fetchSize;

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
        public Statement fetchSize(int fetchSize) {
            if (fetchSize < 0) {
                throw new IllegalArgumentException("Fetch size must not be negative");
            }
            this.fetchSize = fetchSize;
            return this;
        }

        @Override
        public Statement generatedKeyColumns(String... columnNames) {
            Objects.requireNonNull(columnNames, "Generated-key column names must not be null");
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
        public <T> List<T> list(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.query(sql, parameters, fetchSize));
                return JdbcReducers.list(transcript, mapper);
            });
        }

        @Override
        public <T> T single(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.query(sql, parameters, fetchSize));
                return JdbcReducers.item(transcript, mapper);
            });
        }

        @Override
        public <T> T singleOrNull(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.query(sql, parameters, fetchSize));
                return JdbcReducers.itemOrNull(transcript, mapper);
            });
        }

        @Override
        public <T> Optional<T> optional(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.query(sql, parameters, fetchSize));
                return JdbcReducers.optional(transcript, mapper);
            });
        }

        @Override
        public Number updateCount() {
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.update(sql, parameters, fetchSize));
                return JdbcReducers.updateCount(transcript);
            });
        }

        @Override
        public <T> List<T> generatedKeys(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.update(sql,
                                                                                parameters,
                                                                                fetchSize,
                                                                                generatedKeysRequest()));
                return JdbcReducers.generatedKeys(transcript, mapper);
            });
        }

        @Override
        public <T> T generatedKey(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.update(sql,
                                                                                parameters,
                                                                                fetchSize,
                                                                                generatedKeysRequest()));
                return JdbcReducers.generatedKey(transcript, mapper);
            });
        }

        @Override
        public <T> Optional<T> optionalGeneratedKey(JdbcRowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "Generated-key row mapper must not be null");
            return execute(() -> {
                JdbcTranscript transcript = runner.execute(JdbcOperation.update(sql,
                                                                                parameters,
                                                                                fetchSize,
                                                                                generatedKeysRequest()));
                return JdbcReducers.optionalGeneratedKey(transcript, mapper);
            });
        }

        private GeneratedKeysRequest generatedKeysRequest() {
            if (generatedKeyColumns.isEmpty()) {
                return GeneratedKeysRequest.any();
            }
            return GeneratedKeysRequest.columns(generatedKeyColumns);
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
