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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.common.Api;
import io.helidon.data.DataException;

import static java.util.Objects.requireNonNull;

/**
 * Minimal fluent JDBC client backed by the Helidon Data JDBC execution kernel.
 */
@Api.Preview
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
     * Maps a JDBC row to a result value.
     *
     * @param <T> mapped value type
     */
    @Api.Preview
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
     * Public row view for fluent JDBC mapping.
     */
    @Api.Preview
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
    }

    /**
     * Fluent query builder.
     */
    @Api.Preview
    public static final class Query extends StatementBuilder<Query> {

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
            return operations().list(plan(JdbcStatementKind.QUERY), binder(), adapter(mapper));
        }

        /**
         * Execute the query and map at most one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row, or empty if none was returned
         */
        public <T> Optional<T> optional(Mapper<? extends T> mapper) {
            return operations().optional(plan(JdbcStatementKind.QUERY), binder(), adapter(mapper));
        }

        /**
         * Execute the query and map exactly one row.
         *
         * @param mapper row mapper
         * @param <T> result item type
         * @return mapped row
         */
        public <T> T one(Mapper<? extends T> mapper) {
            return operations().one(plan(JdbcStatementKind.QUERY), binder(), adapter(mapper));
        }
    }

    /**
     * Fluent update builder.
     */
    @Api.Preview
    public static final class Update extends StatementBuilder<Update> {

        private Update(JdbcOperations operations, String sql) {
            super(operations, sql);
        }

        /**
         * Execute the update.
         *
         * @return update count
         */
        public long execute() {
            return operations().update(plan(JdbcStatementKind.UPDATE), binder());
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
            JdbcStatementPlan plan = JdbcStatementPlan.generatedKeys(sql(), columnNames)
                    .withOptions(options());
            return operations().generatedKey(plan, binder(), adapter(mapper));
        }
    }

    private abstract static class StatementBuilder<B extends StatementBuilder<B>> {
        private final JdbcOperations operations;
        private final String sql;
        private final List<Binding> bindings = new ArrayList<>();
        private int queryTimeoutSeconds;
        private int fetchSize;
        private long maxRows;

        StatementBuilder(JdbcOperations operations, String sql) {
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
        public B bind(int index, Object value) {
            if (index < 1) {
                throw new IllegalArgumentException("index must be greater than zero");
            }
            bindings.add(new Binding(index, value));
            return self();
        }

        /**
         * Set the query timeout.
         *
         * @param seconds timeout in seconds
         * @return this builder
         */
        public B queryTimeoutSeconds(int seconds) {
            this.queryTimeoutSeconds = seconds;
            return self();
        }

        /**
         * Set the fetch size.
         *
         * @param fetchSize fetch size
         * @return this builder
         */
        public B fetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
            return self();
        }

        /**
         * Set the maximum row count.
         *
         * @param maxRows maximum row count
         * @return this builder
         */
        public B maxRows(long maxRows) {
            this.maxRows = maxRows;
            return self();
        }

        final JdbcOperations operations() {
            return operations;
        }

        final String sql() {
            return sql;
        }

        final JdbcStatementOptions options() {
            return new JdbcStatementOptions(queryTimeoutSeconds, fetchSize, maxRows);
        }

        final JdbcStatementPlan plan(JdbcStatementKind kind) {
            JdbcStatementPlan plan = switch (kind) {
            case QUERY -> JdbcStatementPlan.query(sql);
            case UPDATE -> JdbcStatementPlan.update(sql);
            case CALL, BATCH -> throw new DataException("Unsupported fluent JDBC statement kind: " + kind);
            };
            return plan.withOptions(options());
        }

        final JdbcBinder binder() {
            if (bindings.isEmpty()) {
                return JdbcBinder.none();
            }
            List<Binding> copy = List.copyOf(bindings);
            return statement -> {
                for (Binding binding : copy) {
                    statement.setObject(binding.index(), binding.value());
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
    }

    private record Binding(int index, Object value) {
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
    }
}
