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

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable per-execution JDBC statement options.
 * <p>
 * Each unset option inherits client, datasource, driver, or persistence-unit behavior. Instances are thread-safe and
 * reusable. These options do not control connection ownership, transactions, mapping, traversal, or resource cleanup.
 */
public final class JdbcStatementOptions {

    static final JdbcStatementOptions EMPTY = new JdbcStatementOptions(null, null, null, null);

    private final Integer fetchSize;
    private final Duration queryTimeout;
    private final Long maxRows;
    private final Boolean poolableHint;

    private JdbcStatementOptions(Integer fetchSize,
                                 Duration queryTimeout,
                                 Long maxRows,
                                 Boolean poolableHint) {
        this.fetchSize = fetchSize;
        this.queryTimeout = queryTimeout;
        this.maxRows = maxRows;
        this.poolableHint = poolableHint;
    }

    /**
     * Creates an empty options builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configured fetch size, or {@code null} when the driver default should remain unchanged.
     *
     * @return configured fetch size or {@code null}
     */
    Integer fetchSize() {
        return fetchSize;
    }

    /**
     * Returns the configured query timeout, or {@code null} when the driver default should remain unchanged.
     *
     * @return configured timeout or {@code null}
     */
    Duration queryTimeout() {
        return queryTimeout;
    }

    /**
     * Returns the configured large maximum row count, or {@code null} when the driver default should remain unchanged.
     *
     * @return configured maximum or {@code null}
     */
    Long maxRows() {
        return maxRows;
    }

    /**
     * Returns the configured statement-pooling hint, or {@code null} when the driver default should remain unchanged.
     *
     * @return configured pooling hint or {@code null}
     */
    Boolean poolableHint() {
        return poolableHint;
    }

    /**
     * Tests whether this value configures no statement setting.
     *
     * @return {@code true} when every setting is absent
     */
    boolean empty() {
        return fetchSize == null && queryTimeout == null && maxRows == null && poolableHint == null;
    }

    /**
     * Overlays explicitly configured values on this options value.
     *
     * @param override invocation-level overrides
     * @return this value with each configured override applied
     */
    JdbcStatementOptions overlay(JdbcStatementOptions override) {
        Objects.requireNonNull(override, "Statement options must not be null");
        if (override == EMPTY) {
            return this;
        }
        if (this == EMPTY) {
            return override;
        }
        return new JdbcStatementOptions(override.fetchSize != null ? override.fetchSize : fetchSize,
                                        override.queryTimeout != null ? override.queryTimeout : queryTimeout,
                                        override.maxRows != null ? override.maxRows : maxRows,
                                        override.poolableHint != null ? override.poolableHint : poolableHint);
    }

    /**
     * Mutable builder for immutable {@link JdbcStatementOptions} instances.
     * <p>
     * A builder is not thread-safe. It may be reused; each call to {@link #build()} creates an independent immutable
     * snapshot. Callback-based row traversal composes a snapshot through
     * {@link JdbcResultRequest.VisitAll#withOptions(JdbcStatementOptions)} or
     * {@link JdbcResultRequest.VisitWhile#withOptions(JdbcStatementOptions)}. Callable callbacks use
     * {@link JdbcResultRequest.Call#withOptions(JdbcStatementOptions)} or
     * {@link JdbcResultRequest.CallWith#withOptions(JdbcStatementOptions)}.
     */
    public static final class Builder {
        private Integer fetchSize;
        private Duration queryTimeout;
        private Long maxRows;
        private Boolean poolableHint;

        private Builder() {
        }

        /**
         * Requests the JDBC fetch size.
         *
         * @param rows requested rows; zero leaves the choice to the driver
         * @return this builder
         * @throws IllegalArgumentException if {@code rows} is negative
         */
        public Builder fetchSize(int rows) {
            if (rows < 0) {
                throw new IllegalArgumentException("Fetch size must not be negative: " + rows);
            }
            this.fetchSize = rows;
            return this;
        }

        /**
         * Requests a whole-second JDBC query timeout.
         *
         * @param timeout non-negative whole-second timeout; zero means no timeout
         * @return this builder
         * @throws NullPointerException if {@code timeout} is {@code null}
         * @throws IllegalArgumentException if the duration is negative, contains fractional seconds, or exceeds the JDBC
         *         integer timeout range
         */
        public Builder queryTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "Query timeout must not be null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Query timeout must not be negative: " + timeout);
            }
            if (timeout.getNano() != 0) {
                throw new IllegalArgumentException("Query timeout must use whole seconds: " + timeout);
            }
            if (timeout.getSeconds() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Query timeout exceeds the JDBC integer range: " + timeout);
            }
            this.queryTimeout = timeout;
            return this;
        }

        /**
         * Requests the JDBC large maximum row count.
         *
         * @param rows maximum rows; zero means no limit
         * @return this builder
         * @throws IllegalArgumentException if {@code rows} is negative
         */
        public Builder maxRows(long rows) {
            if (rows < 0) {
                throw new IllegalArgumentException("Maximum rows must not be negative: " + rows);
            }
            this.maxRows = rows;
            return this;
        }

        /**
         * Supplies a hint about whether the JDBC driver should pool the prepared statement.
         * <p>
         * The provider applies this value through {@link java.sql.Statement#setPoolable(boolean)} before statement
         * execution. A driver may ignore the hint. Leaving this option unset preserves the driver's default and does not
         * invoke {@code setPoolable}. This hint concerns JDBC statement pooling only; it neither configures the datasource
         * connection pool nor guarantees that the driver will cache the statement.
         *
         * @param poolable {@code true} to request statement pooling, or {@code false} to request that the statement not be
         *                 pooled
         * @return this builder
         */
        public Builder poolableHint(boolean poolable) {
            this.poolableHint = poolable;
            return this;
        }

        /**
         * Creates an immutable options snapshot.
         *
         * @return immutable options
         */
        public JdbcStatementOptions build() {
            if (fetchSize == null && queryTimeout == null && maxRows == null && poolableHint == null) {
                return EMPTY;
            }
            return new JdbcStatementOptions(fetchSize, queryTimeout, maxRows, poolableHint);
        }
    }
}
