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

    private static final boolean TRACE = Boolean.getBoolean("helidon.data.jdbc.trace");

    private final JdbcOperations operations;

    private JdbcClient(JdbcOperations operations) {
        trace(">>> ENTER JdbcClient.<init>(operations=" + operations + ")");
        this.operations = requireNonNull(operations, "operations");
        trace("<<< EXIT  JdbcClient.<init>()");
    }

    /**
     * Create a fluent JDBC client for a data source.
     *
     * @param dataSource data source
     * @return JDBC client
     */
    public static JdbcClient create(DataSource dataSource) {
        trace(">>> ENTER JdbcClient.create(dataSource=" + dataSource + ")");
        JdbcClient result = new JdbcClient(JdbcOperations.create(dataSource));
        trace("<<< EXIT  JdbcClient.create() result=" + result);
        return result;
    }

    /**
     * Create a query builder.
     *
     * @param sql SQL query
     * @return query builder
     */
    public Query query(String sql) {
        trace(">>> ENTER JdbcClient.query(sql=" + sql + ")");
        Query result = new Query(operations, requireNonNull(sql, "sql"));
        trace("<<< EXIT  JdbcClient.query() result=" + result);
        return result;
    }

    /**
     * Create an update builder.
     *
     * @param sql SQL update
     * @return update builder
     */
    public Update update(String sql) {
        trace(">>> ENTER JdbcClient.update(sql=" + sql + ")");
        Update result = new Update(operations, requireNonNull(sql, "sql"));
        trace("<<< EXIT  JdbcClient.update() result=" + result);
        return result;
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
            trace(">>> ENTER JdbcClient.Query.<init>(operations=" + operations + ", sql=" + sql + ")");
            trace("<<< EXIT  JdbcClient.Query.<init>()");
        }

        /**
         * Execute the query and map all rows.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped rows
         */
        public <T> List<T> list(Mapper<? extends T> mapper) {
            trace(">>> ENTER JdbcClient.Query.list(mapper=" + mapper + ")");
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            List<T> result = operations().list(execution.plan(), execution.binder(), adapter(mapper));
            trace("<<< EXIT  JdbcClient.Query.list() resultSize=" + result.size());
            return result;
        }

        /**
         * Execute the query and map the first column of all rows.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped rows
         */
        public <T> List<T> list(Class<T> type) {
            trace(">>> ENTER JdbcClient.Query.list(type=" + type + ")");
            requireNonNull(type, "type");
            List<T> result = list(row -> row.get(1, type));
            trace("<<< EXIT  JdbcClient.Query.list(Class) resultSize=" + result.size());
            return result;
        }

        /**
         * Execute the query and map at most one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row, or empty if none was returned
         */
        public <T> Optional<T> optional(Mapper<? extends T> mapper) {
            trace(">>> ENTER JdbcClient.Query.optional(mapper=" + mapper + ")");
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            Optional<T> result = operations().optional(execution.plan(), execution.binder(), adapter(mapper));
            trace("<<< EXIT  JdbcClient.Query.optional() present=" + result.isPresent());
            return result;
        }

        /**
         * Execute the query and map the first column of at most one row.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped row, or empty if none was returned
         */
        public <T> Optional<T> optional(Class<T> type) {
            trace(">>> ENTER JdbcClient.Query.optional(type=" + type + ")");
            requireNonNull(type, "type");
            Optional<T> result = optional(row -> row.get(1, type));
            trace("<<< EXIT  JdbcClient.Query.optional(Class) present=" + result.isPresent());
            return result;
        }

        /**
         * Execute the query and map exactly one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row
         */
        public <T> T one(Mapper<? extends T> mapper) {
            trace(">>> ENTER JdbcClient.Query.one(mapper=" + mapper + ")");
            StatementExecution execution = statement(JdbcStatementKind.QUERY);
            T result = operations().one(execution.plan(), execution.binder(), adapter(mapper));
            trace("<<< EXIT  JdbcClient.Query.one() result=" + result);
            return result;
        }

        /**
         * Execute the query and map the first column of exactly one row.
         *
         * @param type result item type
         * @param <T> result item type
         * @return mapped row
         */
        public <T> T one(Class<T> type) {
            trace(">>> ENTER JdbcClient.Query.one(type=" + type + ")");
            requireNonNull(type, "type");
            T result = one(row -> row.get(1, type));
            trace("<<< EXIT  JdbcClient.Query.one(Class) result=" + result);
            return result;
        }
    }

    /**
     * Fluent update builder.
     */
    public static final class Update extends Statement<Update> {

        private Update(JdbcOperations operations, String sql) {
            super(operations, sql);
            trace(">>> ENTER JdbcClient.Update.<init>(operations=" + operations + ", sql=" + sql + ")");
            trace("<<< EXIT  JdbcClient.Update.<init>()");
        }

        /**
         * Execute the update.
         *
         * @return update count
         */
        public long execute() {
            trace(">>> ENTER JdbcClient.Update.execute()");
            StatementExecution execution = statement(JdbcStatementKind.UPDATE);
            long result = operations().update(execution.plan(), execution.binder());
            trace("<<< EXIT  JdbcClient.Update.execute() result=" + result);
            return result;
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
            trace(">>> ENTER JdbcClient.Update.generatedKey(mapper=" + mapper
                          + ", columnNames=" + java.util.Arrays.toString(columnNames) + ")");
            StatementExecution execution = generatedKeysStatement(columnNames);
            Optional<T> result = operations().generatedKey(execution.plan(), execution.binder(), adapter(mapper));
            trace("<<< EXIT  JdbcClient.Update.generatedKey() present=" + result.isPresent());
            return result;
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
            trace(">>> ENTER JdbcClient.Update.generatedKey(type=" + type
                          + ", columnNames=" + java.util.Arrays.toString(columnNames) + ")");
            requireNonNull(type, "type");
            Optional<T> result = generatedKey(row -> row.get(1, type), columnNames);
            trace("<<< EXIT  JdbcClient.Update.generatedKey(Class) present=" + result.isPresent());
            return result;
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
            trace(">>> ENTER JdbcClient.Statement.<init>(operations=" + operations + ", sql=" + sql + ")");
            this.operations = requireNonNull(operations, "operations");
            this.sql = requireNonNull(sql, "sql");
            trace("<<< EXIT  JdbcClient.Statement.<init>()");
        }

        /**
         * Bind a positional parameter.
         *
         * @param index parameter index
         * @param value parameter value
         * @return this builder
         */
        public final B bind(int index, Object value) {
            trace(">>> ENTER JdbcClient.Statement.bind(index=" + index + ", value=" + value + ")");
            if (index < 1) {
                throw new IllegalArgumentException("index must be greater than zero");
            }
            indexedBindings.put(index, value);
            trace("<<< EXIT  JdbcClient.Statement.bind(int,Object)");
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
            trace(">>> ENTER JdbcClient.Statement.bind(name=" + name + ", value=" + value + ")");
            String normalizedName = normalizeName(name);
            namedBindings.put(normalizedName, value);
            trace("<<< EXIT  JdbcClient.Statement.bind(String,Object) normalizedName=" + normalizedName);
            return self();
        }

        /**
         * Bind named parameters from a map.
         *
         * @param values named parameter values
         * @return this builder
         */
        public final B bindAll(Map<String, ?> values) {
            trace(">>> ENTER JdbcClient.Statement.bindAll(values=" + values + ")");
            Map<String, ?> nonNullValues = requireNonNull(values, "values");
            nonNullValues.forEach(this::bind);
            trace("<<< EXIT  JdbcClient.Statement.bindAll() count=" + nonNullValues.size());
            return self();
        }

        /**
         * Set the query timeout.
         *
         * @param seconds timeout in seconds
         * @return this builder
         */
        public final B queryTimeoutSeconds(int seconds) {
            trace(">>> ENTER JdbcClient.Statement.queryTimeoutSeconds(seconds=" + seconds + ")");
            requireNotNegative(seconds, "seconds");
            this.queryTimeoutSeconds = seconds;
            trace("<<< EXIT  JdbcClient.Statement.queryTimeoutSeconds()");
            return self();
        }

        /**
         * Set the query timeout.
         *
         * @param timeout timeout duration
         * @return this builder
         */
        public final B queryTimeout(Duration timeout) {
            trace(">>> ENTER JdbcClient.Statement.queryTimeout(timeout=" + timeout + ")");
            requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            this.queryTimeoutSeconds = timeoutSeconds(timeout);
            trace("<<< EXIT  JdbcClient.Statement.queryTimeout() seconds=" + this.queryTimeoutSeconds);
            return self();
        }

        /**
         * Set the fetch size.
         *
         * @param fetchSize fetch size
         * @return this builder
         */
        public final B fetchSize(int fetchSize) {
            trace(">>> ENTER JdbcClient.Statement.fetchSize(fetchSize=" + fetchSize + ")");
            requireNotNegative(fetchSize, "fetchSize");
            this.fetchSize = fetchSize;
            trace("<<< EXIT  JdbcClient.Statement.fetchSize()");
            return self();
        }

        /**
         * Set the maximum row count.
         *
         * @param maxRows maximum row count
         * @return this builder
         */
        public final B maxRows(long maxRows) {
            trace(">>> ENTER JdbcClient.Statement.maxRows(maxRows=" + maxRows + ")");
            requireNotNegative(maxRows, "maxRows");
            this.maxRows = maxRows;
            trace("<<< EXIT  JdbcClient.Statement.maxRows()");
            return self();
        }

        final JdbcOperations operations() {
            trace(">>> ENTER JdbcClient.Statement.operations()");
            trace("<<< EXIT  JdbcClient.Statement.operations() result=" + operations);
            return operations;
        }

        final JdbcStatementOptions options() {
            trace(">>> ENTER JdbcClient.Statement.options()");
            JdbcStatementOptions result = new JdbcStatementOptions(queryTimeoutSeconds, fetchSize, maxRows);
            trace("<<< EXIT  JdbcClient.Statement.options() result=" + result);
            return result;
        }

        final StatementExecution statement(JdbcStatementKind kind) {
            trace(">>> ENTER JdbcClient.Statement.statement(kind=" + kind + ")");
            ParsedStatement parsed = parse();
            JdbcStatementPlan plan = switch (kind) {
            case QUERY -> JdbcStatementPlan.query(parsed.sql());
            case UPDATE -> JdbcStatementPlan.update(parsed.sql());
            case CALL, BATCH -> throw new DataException("Unsupported fluent JDBC statement kind: " + kind);
            };
            StatementExecution result = new StatementExecution(plan.withOptions(options()), binder(parsed));
            trace("<<< EXIT  JdbcClient.Statement.statement() plan=" + result.plan()
                          + ", binder=" + result.binder());
            return result;
        }

        final StatementExecution generatedKeysStatement(String... columnNames) {
            trace(">>> ENTER JdbcClient.Statement.generatedKeysStatement(columnNames="
                          + java.util.Arrays.toString(columnNames) + ")");
            ParsedStatement parsed = parse();
            JdbcStatementPlan plan = JdbcStatementPlan.generatedKeys(parsed.sql(), columnNames)
                    .withOptions(options());
            StatementExecution result = new StatementExecution(plan, binder(parsed));
            trace("<<< EXIT  JdbcClient.Statement.generatedKeysStatement() plan=" + result.plan()
                          + ", binder=" + result.binder());
            return result;
        }

        final JdbcBinder binder(ParsedStatement parsed) {
            trace(">>> ENTER JdbcClient.Statement.binder(parsed=" + parsed + ")");
            if (indexedBindings.isEmpty() && namedBindings.isEmpty()) {
                JdbcBinder result = JdbcBinder.none();
                trace("<<< EXIT  JdbcClient.Statement.binder() result=" + result);
                return result;
            }
            if (parsed.named()) {
                List<String> markers = parsed.markers();
                Map<String, Object> copy = new LinkedHashMap<>(namedBindings);
                JdbcBinder result = statement -> {
                    for (int i = 0; i < markers.size(); i++) {
                        trace(">>> ENTER JdbcClient.Statement.namedBinder.bind(index=" + (i + 1)
                                      + ", name=" + markers.get(i)
                                      + ", value=" + copy.get(markers.get(i).substring(1)) + ")");
                        statement.setObject(i + 1, copy.get(markers.get(i).substring(1)));
                        trace("<<< EXIT  JdbcClient.Statement.namedBinder.bind(index=" + (i + 1) + ")");
                    }
                };
                trace("<<< EXIT  JdbcClient.Statement.binder() result=" + result);
                return result;
            }
            Map<Integer, Object> copy = new LinkedHashMap<>(indexedBindings);
            JdbcBinder result = statement -> {
                for (Map.Entry<Integer, Object> binding : copy.entrySet()) {
                    trace(">>> ENTER JdbcClient.Statement.indexedBinder.bind(index=" + binding.getKey()
                                  + ", value=" + binding.getValue() + ")");
                    statement.setObject(binding.getKey(), binding.getValue());
                    trace("<<< EXIT  JdbcClient.Statement.indexedBinder.bind(index=" + binding.getKey() + ")");
                }
            };
            trace("<<< EXIT  JdbcClient.Statement.binder() result=" + result);
            return result;
        }

        final <T> JdbcRowMapper<? extends T> adapter(Mapper<? extends T> mapper) {
            trace(">>> ENTER JdbcClient.Statement.adapter(mapper=" + mapper + ")");
            requireNonNull(mapper, "mapper");
            JdbcRowMapper<? extends T> result = row -> mapper.map(new RowImpl(row));
            trace("<<< EXIT  JdbcClient.Statement.adapter() result=" + result);
            return result;
        }

        @SuppressWarnings("unchecked")
        private B self() {
            trace(">>> ENTER JdbcClient.Statement.self()");
            trace("<<< EXIT  JdbcClient.Statement.self()");
            return (B) this;
        }

        private ParsedStatement parse() {
            trace(">>> ENTER JdbcClient.Statement.parse(sql=" + sql
                          + ", indexedBindings=" + indexedBindings
                          + ", namedBindings=" + namedBindings + ")");
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
                throw new IllegalArgumentException("JDBC statement must not mix named and positional "
                                                           + "parameter markers");
            }
            if (named) {
                validateNamedMarkers(markers);
            } else {
                validatePositionalMarkers(markers.size());
            }
            ParsedStatement result = new ParsedStatement(rewritten, List.copyOf(markers), named);
            trace("<<< EXIT  JdbcClient.Statement.parse() result=" + result);
            return result;
        }

        private void validateNamedMarkers(List<String> markers) {
            trace(">>> ENTER JdbcClient.Statement.validateNamedMarkers(markers=" + markers + ")");
            if (namedBindings.isEmpty()) {
                throw new IllegalArgumentException("JDBC statement uses named parameters but no named values "
                                                           + "were bound");
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
            trace("<<< EXIT  JdbcClient.Statement.validateNamedMarkers()");
        }

        private void validatePositionalMarkers(int markerCount) {
            trace(">>> ENTER JdbcClient.Statement.validatePositionalMarkers(markerCount=" + markerCount + ")");
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
            trace("<<< EXIT  JdbcClient.Statement.validatePositionalMarkers()");
        }

        private static String normalizeName(String name) {
            trace(">>> ENTER JdbcClient.Statement.normalizeName(name=" + name + ")");
            requireNonNull(name, "name");
            String normalized = name.startsWith(":") ? name.substring(1) : name;
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            trace("<<< EXIT  JdbcClient.Statement.normalizeName() result=" + normalized);
            return normalized;
        }

        private static int timeoutSeconds(Duration timeout) {
            trace(">>> ENTER JdbcClient.Statement.timeoutSeconds(timeout=" + timeout + ")");
            if (timeout.isZero()) {
                trace("<<< EXIT  JdbcClient.Statement.timeoutSeconds() result=0");
                return 0;
            }
            long seconds = timeout.toSeconds();
            if (timeout.getNano() > 0) {
                seconds++;
            }
            if (seconds > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("timeout is too large");
            }
            int result = (int) seconds;
            trace("<<< EXIT  JdbcClient.Statement.timeoutSeconds() result=" + result);
            return result;
        }

        private static void requireNotNegative(int value, String name) {
            trace(">>> ENTER JdbcClient.Statement.requireNotNegative(value=" + value + ", name=" + name + ")");
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            trace("<<< EXIT  JdbcClient.Statement.requireNotNegative(int,String)");
        }

        private static void requireNotNegative(long value, String name) {
            trace(">>> ENTER JdbcClient.Statement.requireNotNegative(value=" + value + ", name=" + name + ")");
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            trace("<<< EXIT  JdbcClient.Statement.requireNotNegative(long,String)");
        }
    }

    private record ParsedStatement(String sql, List<String> markers, boolean named) {
    }

    private record StatementExecution(JdbcStatementPlan plan, JdbcBinder binder) {
    }

    private static final class RowImpl implements Row {
        private final JdbcResultSetRowView row;

        private RowImpl(JdbcResultSetRowView row) {
            trace(">>> ENTER JdbcClient.RowImpl.<init>(row=" + row + ")");
            this.row = requireNonNull(row, "row");
            trace("<<< EXIT  JdbcClient.RowImpl.<init>()");
        }

        @Override
        public Object get(int columnIndex) {
            trace(">>> ENTER JdbcClient.RowImpl.get(columnIndex=" + columnIndex + ")");
            try {
                Object result = row.getObject(columnIndex);
                trace("<<< EXIT  JdbcClient.RowImpl.get(int) result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.get(int) exception=" + e);
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public Object get(String columnLabel) {
            trace(">>> ENTER JdbcClient.RowImpl.get(columnLabel=" + columnLabel + ")");
            try {
                Object result = row.getObject(columnLabel);
                trace("<<< EXIT  JdbcClient.RowImpl.get(String) result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.get(String) exception=" + e);
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public <T> T get(int columnIndex, Class<T> type) {
            trace(">>> ENTER JdbcClient.RowImpl.get(columnIndex=" + columnIndex + ", type=" + type + ")");
            try {
                T result = row.getObject(columnIndex, type);
                trace("<<< EXIT  JdbcClient.RowImpl.get(int,Class) result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.get(int,Class) exception=" + e);
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public <T> T get(String columnLabel, Class<T> type) {
            trace(">>> ENTER JdbcClient.RowImpl.get(columnLabel=" + columnLabel + ", type=" + type + ")");
            try {
                T result = row.getObject(columnLabel, type);
                trace("<<< EXIT  JdbcClient.RowImpl.get(String,Class) result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.get(String,Class) exception=" + e);
                throw new DataException("Failed to read JDBC column value.", e);
            }
        }

        @Override
        public String string(String columnLabel) {
            trace(">>> ENTER JdbcClient.RowImpl.string(columnLabel=" + columnLabel + ")");
            try {
                String result = row.getString(columnLabel);
                trace("<<< EXIT  JdbcClient.RowImpl.string() result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.string() exception=" + e);
                throw new DataException("Failed to read JDBC string column value.", e);
            }
        }

        @Override
        public int intValue(String columnLabel) {
            trace(">>> ENTER JdbcClient.RowImpl.intValue(columnLabel=" + columnLabel + ")");
            try {
                int result = row.getInt(columnLabel);
                trace("<<< EXIT  JdbcClient.RowImpl.intValue() result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.intValue() exception=" + e);
                throw new DataException("Failed to read JDBC int column value.", e);
            }
        }

        @Override
        public long longValue(String columnLabel) {
            trace(">>> ENTER JdbcClient.RowImpl.longValue(columnLabel=" + columnLabel + ")");
            try {
                long result = row.getLong(columnLabel);
                trace("<<< EXIT  JdbcClient.RowImpl.longValue() result=" + result);
                return result;
            } catch (SQLException e) {
                trace("xxx FAIL  JdbcClient.RowImpl.longValue() exception=" + e);
                throw new DataException("Failed to read JDBC long column value.", e);
            }
        }

        @Override
        public boolean isNull(int columnIndex) {
            trace(">>> ENTER JdbcClient.RowImpl.isNull(columnIndex=" + columnIndex + ")");
            boolean result = get(columnIndex) == null;
            trace("<<< EXIT  JdbcClient.RowImpl.isNull(int) result=" + result);
            return result;
        }

        @Override
        public boolean isNull(String columnLabel) {
            trace(">>> ENTER JdbcClient.RowImpl.isNull(columnLabel=" + columnLabel + ")");
            boolean result = get(columnLabel) == null;
            trace("<<< EXIT  JdbcClient.RowImpl.isNull(String) result=" + result);
            return result;
        }
    }

    private static void trace(String message) {
        if (TRACE) {
            System.out.println("\n***** JDBC TRACE [JdbcClient] " + message + "\n");
        }
    }
}
