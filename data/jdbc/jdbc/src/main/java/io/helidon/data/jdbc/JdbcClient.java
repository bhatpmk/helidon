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

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import io.helidon.common.Api;
import io.helidon.data.DataException;
import io.helidon.data.jdbc.namedparameters.NamedParameters;

import static java.util.Objects.requireNonNull;

/**
 * Fluent JDBC client backed by the Helidon Data JDBC execution kernel.
 *
 * <p>{@link JdbcClient} instances are safe for concurrent use. Statement builders returned by this client are mutable
 * and are intended for single-statement use by one thread.</p>
 */
@SuppressWarnings(Api.SUPPRESS_INTERNAL)
public final class JdbcClient {

    private final JdbcOperations operations;

    private JdbcClient(JdbcOperations operations) {
        this.operations = requireNonNull(operations, "operations");
    }

    /**
     * Create a fluent JDBC client for a data source.
     *
     * @param dataSource data source
     * @return JDBC client
     */
    public static JdbcClient create(DataSource dataSource) {
        return new JdbcClient(JdbcOperations.create(dataSource));
    }

    /**
     * Create a query builder.
     *
     * @param sql SQL query
     * @return query builder
     */
    public Query query(String sql) {
        return new Query(operations, requireNonNull(sql, "sql"));
    }

    /**
     * Create an update builder.
     *
     * @param sql SQL update
     * @return update builder
     */
    public Update update(String sql) {
        return new Update(operations, requireNonNull(sql, "sql"));
    }

    /**
     * Maps a row to a result value.
     *
     * @param <T> mapped value type
     */
    @FunctionalInterface
    public interface Mapper<T> {

        /**
         * Map the current row.
         *
         * @param row current row
         * @return mapped value
         */
        T map(Row row);
    }

    /**
     * Row view for JDBC mapping.
     */
    public interface Row {

        /**
         * Get a column value by column index.
         *
         * @param columnIndex column index
         * @return column value
         */
        Object get(int columnIndex);

        /**
         * Get a column value by column label.
         *
         * @param columnLabel column label
         * @return column value
         */
        Object get(String columnLabel);

        /**
         * Get a typed column value by column index.
         *
         * @param columnIndex column index
         * @param type value type
         * @param <T> value type
         * @return column value
         */
        <T> T get(int columnIndex, Class<T> type);

        /**
         * Get a typed column value by column label.
         *
         * @param columnLabel column label
         * @param type value type
         * @param <T> value type
         * @return column value
         */
        <T> T get(String columnLabel, Class<T> type);

        /**
         * Get a string column value by column label.
         *
         * @param columnLabel column label
         * @return column value
         */
        String string(String columnLabel);

        /**
         * Get an int column value by column label.
         *
         * @param columnLabel column label
         * @return column value
         */
        int intValue(String columnLabel);

        /**
         * Get a long column value by column label.
         *
         * @param columnLabel column label
         * @return column value
         */
        long longValue(String columnLabel);

        /**
         * Test whether a column value is {@code SQL NULL}.
         *
         * @param columnIndex column index
         * @return {@code true} when the column value is {@code SQL NULL}
         */
        boolean isNull(int columnIndex);

        /**
         * Test whether a column value is {@code SQL NULL}.
         *
         * @param columnLabel column label
         * @return {@code true} when the column value is {@code SQL NULL}
         */
        boolean isNull(String columnLabel);
    }

    /**
     * Fluent query builder.
     */
    public static final class Query extends Statement<Query> {

        private Query(JdbcOperations operations, String sql) {
            super(operations, sql);
        }

        /**
         * Execute the query and map all rows.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped rows
         */
        public <T> List<T> list(Mapper<? extends T> mapper) {
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            return operations().list(execution.plan(), execution.binder(), adapter(mapper));
        }

        /**
         * Execute the query and map the first column of all rows.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped rows
         */
        public <T> List<T> list(Class<T> type) {
            requireNonNull(type, "type");
            return list(row -> row.get(1, type));
        }

        /**
         * Execute the query and map at most one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row, or empty if none was returned
         */
        public <T> Optional<T> optional(Mapper<? extends T> mapper) {
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            return operations().optional(execution.plan(), execution.binder(), adapter(mapper));
        }

        /**
         * Execute the query and map the first column of at most one row.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped row, or empty if none was returned
         */
        public <T> Optional<T> optional(Class<T> type) {
            requireNonNull(type, "type");
            return optional(row -> row.get(1, type));
        }

        /**
         * Execute the query and map exactly one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row
         */
        public <T> T one(Mapper<? extends T> mapper) {
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            return operations().one(execution.plan(), execution.binder(), adapter(mapper));
        }

        /**
         * Execute the query and map the first column of exactly one row.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped row
         */
        public <T> T one(Class<T> type) {
            requireNonNull(type, "type");
            return one(row -> row.get(1, type));
        }
    }

    /**
     * Fluent update builder.
     */
    public static final class Update extends Statement<Update> {

        private Update(JdbcOperations operations, String sql) {
            super(operations, sql);
        }

        /**
         * Execute the update.
         *
         * @return update count
         */
        public long execute() {
            StatementExecution execution = statement(JdbcStatementKind.UPDATE);
            return operations().update(execution.plan(), execution.binder());
        }

        /**
         * Execute the update and map at most one generated-key row.
         *
         * @param mapper generated-key row mapper
         * @param columnNames generated-key column names
         * @param <T> generated-key item type
         * @return mapped generated key, or empty if none was returned
         */
        public <T> Optional<T> generatedKey(Mapper<? extends T> mapper, String... columnNames) {
            StatementExecution execution = generatedKeysStatement(columnNames);
            return operations().generatedKey(execution.plan(), execution.binder(), adapter(mapper));
        }

        /**
         * Execute the update and map the first column of at most one generated-key row.
         *
         * @param type generated-key item type
         * @param columnNames generated-key column names
         * @param <T> generated-key item type
         * @return mapped generated key, or empty if none was returned
         */
        public <T> Optional<T> generatedKey(Class<T> type, String... columnNames) {
            requireNonNull(type, "type");
            return generatedKey(row -> row.get(1, type), columnNames);
        }
    }

    /**
     * Base class for fluent JDBC statement builders.
     *
     * @param <B> builder type
     */
    public abstract static class Statement<B extends Statement<B>> {
        private final JdbcOperations operations;
        private final String sql;
        private final Map<Integer, Object> indexedBindings = new LinkedHashMap<>();
        private final Map<String, Object> namedBindings = new LinkedHashMap<>();
        private int queryTimeoutSeconds;
        private int fetchSize;
        private long maxRows;

        Statement(JdbcOperations operations, String sql) {
            this.operations = requireNonNull(operations, "operations");
            this.sql = requireNonNull(sql, "sql");
        }

        /**
         * Bind a positional parameter.
         *
         * @param index parameter index
         * @param value parameter value
         * @return this builder
         */
        public final B bind(int index, Object value) {
            if (index < 1) {
                throw new IllegalArgumentException("index must be greater than zero");
            }
            indexedBindings.put(index, value);
            return self();
        }

        /**
         * Bind a named parameter.
         *
         * @param name parameter name, with or without a leading colon
         * @param value parameter value
         * @return this builder
         */
        public final B bind(String name, Object value) {
            namedBindings.put(normalizeName(name), value);
            return self();
        }

        /**
         * Bind named parameters from a map.
         *
         * @param values named parameter values
         * @return this builder
         */
        public final B bindAll(Map<String, ?> values) {
            requireNonNull(values, "values")
                    .forEach(this::bind);
            return self();
        }

        /**
         * Set the query timeout.
         *
         * @param seconds timeout in seconds
         * @return this builder
         */
        public final B queryTimeoutSeconds(int seconds) {
            requireNotNegative(seconds, "seconds");
            this.queryTimeoutSeconds = seconds;
            return self();
        }

        /**
         * Set the query timeout.
         *
         * @param timeout timeout duration
         * @return this builder
         */
        public final B queryTimeout(Duration timeout) {
            requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            this.queryTimeoutSeconds = timeoutSeconds(timeout);
            return self();
        }

        /**
         * Set the fetch size.
         *
         * @param fetchSize fetch size
         * @return this builder
         */
        public final B fetchSize(int fetchSize) {
            requireNotNegative(fetchSize, "fetchSize");
            this.fetchSize = fetchSize;
            return self();
        }

        /**
         * Set the maximum row count.
         *
         * @param maxRows maximum row count
         * @return this builder
         */
        public final B maxRows(long maxRows) {
            requireNotNegative(maxRows, "maxRows");
            this.maxRows = maxRows;
            return self();
        }

        final JdbcOperations operations() {
            return operations;
        }

        final JdbcStatementOptions options() {
            return new JdbcStatementOptions(queryTimeoutSeconds, fetchSize, maxRows);
        }

        final StatementExecution statement(JdbcStatementKind kind) {
            ParsedStatement parsed = parse();
            JdbcStatementPlan plan = switch (kind) {
            case QUERY -> JdbcStatementPlan.query(parsed.sql());
            case UPDATE -> JdbcStatementPlan.update(parsed.sql());
            case CALL, BATCH -> throw new DataException("Unsupported fluent JDBC statement kind: " + kind);
            };
            return new StatementExecution(plan.withOptions(options()), binder(parsed));
        }

        final StatementExecution generatedKeysStatement(String... columnNames) {
            ParsedStatement parsed = parse();
            JdbcStatementPlan plan = JdbcStatementPlan.generatedKeys(parsed.sql(), columnNames)
                    .withOptions(options());
            return new StatementExecution(plan, binder(parsed));
        }

        final JdbcBinder binder(ParsedStatement parsed) {
            if (indexedBindings.isEmpty() && namedBindings.isEmpty()) {
                return JdbcBinder.none();
            }
            if (parsed.named()) {
                List<String> markers = parsed.markers();
                Map<String, Object> copy = new LinkedHashMap<>(namedBindings);
                return statement -> {
                    for (int i = 0; i < markers.size(); i++) {
                        statement.setObject(i + 1, copy.get(markers.get(i).substring(1)));
                    }
                };
            }
            Map<Integer, Object> copy = new LinkedHashMap<>(indexedBindings);
            return statement -> {
                for (Map.Entry<Integer, Object> binding : copy.entrySet()) {
                    statement.setObject(binding.getKey(), binding.getValue());
                }
            };
        }

        final <T> JdbcRowMapper<? extends T> adapter(Mapper<? extends T> mapper) {
            requireNonNull(mapper, "mapper");
            return row -> mapper.map(new RowImpl(row));
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        private ParsedStatement parse() {
            if (!indexedBindings.isEmpty() && !namedBindings.isEmpty()) {
                throw new IllegalArgumentException("Named and positional JDBC parameters cannot be mixed");
            }

            List<String> markers = new ArrayList<>();
            String rewritten = NamedParameters.rewrite(sql, markers::add);
            boolean named = markers.stream()
                    .anyMatch(marker -> !"?".equals(marker));
            boolean positional = markers.stream()
                    .anyMatch("?"::equals);
            if (named && positional) {
                throw new IllegalArgumentException("JDBC statement must not mix named and positional parameter markers");
            }
            if (named) {
                validateNamedMarkers(markers);
            } else {
                validatePositionalMarkers(markers.size());
            }
            return new ParsedStatement(rewritten, List.copyOf(markers), named);
        }

        private void validateNamedMarkers(List<String> markers) {
            if (namedBindings.isEmpty()) {
                throw new IllegalArgumentException("JDBC statement uses named parameters but no named values were bound");
            }
            Set<String> used = new HashSet<>();
            for (String marker : markers) {
                String name = marker.substring(1);
                if (!namedBindings.containsKey(name)) {
                    throw new IllegalArgumentException("Missing JDBC named parameter: " + name);
                }
                used.add(name);
            }
            namedBindings.keySet()
                    .stream()
                    .filter(name -> !used.contains(name))
                    .findFirst()
                    .ifPresent(name -> {
                        throw new IllegalArgumentException("JDBC named parameter is not used by SQL: " + name);
                    });
        }

        private void validatePositionalMarkers(int markerCount) {
            if (markerCount == 0 && !indexedBindings.isEmpty()) {
                throw new IllegalArgumentException("JDBC statement has no positional parameter markers");
            }
            for (int i = 1; i <= markerCount; i++) {
                if (!indexedBindings.containsKey(i)) {
                    throw new IllegalArgumentException("Missing JDBC positional parameter at index " + i);
                }
            }
            indexedBindings.keySet()
                    .stream()
                    .filter(index -> index > markerCount)
                    .findFirst()
                    .ifPresent(index -> {
                        throw new IllegalArgumentException("JDBC positional parameter is not used by SQL: " + index);
                    });
        }

        private static String normalizeName(String name) {
            requireNonNull(name, "name");
            String normalized = name.startsWith(":") ? name.substring(1) : name;
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return normalized;
        }

        private static int timeoutSeconds(Duration timeout) {
            if (timeout.isZero()) {
                return 0;
            }
            long seconds = timeout.toSeconds();
            if (timeout.getNano() > 0) {
                seconds++;
            }
            if (seconds > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("timeout is too large");
            }
            return (int) seconds;
        }

        private static void requireNotNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }

        private static void requireNotNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }
    }

    private record ParsedStatement(String sql, List<String> markers, boolean named) {
    }

    private record StatementExecution(JdbcStatementPlan plan, JdbcBinder binder) {
    }

    private static final class RowImpl implements Row {
        private final JdbcResultSetRowView row;

        private RowImpl(JdbcResultSetRowView row) {
            this.row = requireNonNull(row, "row");
        }

        @Override
        public Object get(int columnIndex) {
            try {
                return row.getObject(columnIndex);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public Object get(String columnLabel) {
            try {
                return row.getObject(columnLabel);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public <T> T get(int columnIndex, Class<T> type) {
            try {
                return row.getObject(columnIndex, type);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public <T> T get(String columnLabel, Class<T> type) {
            try {
                return row.getObject(columnLabel, type);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public String string(String columnLabel) {
            try {
                return row.getString(columnLabel);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC string column value.", e);
            }
        }

        @Override
        public int intValue(String columnLabel) {
            try {
                return row.getInt(columnLabel);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC int column value.", e);
            }
        }

        @Override
        public long longValue(String columnLabel) {
            try {
                return row.getLong(columnLabel);
            } catch (SQLException e) {
                throw new DataException("Failed to read JDBC long column value.", e);
            }
        }

        @Override
        public boolean isNull(int columnIndex) {
            return get(columnIndex) == null;
        }

        @Override
        public boolean isNull(String columnLabel) {
            return get(columnLabel) == null;
        }
    }
}
