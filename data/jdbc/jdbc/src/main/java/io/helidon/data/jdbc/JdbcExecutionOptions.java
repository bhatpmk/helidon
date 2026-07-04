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
 * Immutable per-execution JDBC options.
 * <p>
 * Each unset option inherits the client or datasource behavior. Instances are thread-safe and reusable. The options
 * currently affect only a prepared statement; transaction state, connection ownership, and resource-closing behavior
 * are deliberately not configurable here.
 */
public final class JdbcExecutionOptions {

    static final JdbcExecutionOptions EMPTY = new JdbcExecutionOptions(null, null, null);

    private final Integer fetchSize;
    private final Duration queryTimeout;
    private final Long maxRows;

    private JdbcExecutionOptions(Integer fetchSize, Duration queryTimeout, Long maxRows) {
        this.fetchSize = fetchSize;
        this.queryTimeout = queryTimeout;
        this.maxRows = maxRows;
    }

    /**
     * Creates an empty options builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    Integer fetchSize() {
        return fetchSize;
    }

    Duration queryTimeout() {
        return queryTimeout;
    }

    Long maxRows() {
        return maxRows;
    }

    JdbcExecutionOptions overlay(JdbcExecutionOptions override) {
        Objects.requireNonNull(override, "Execution options must not be null");
        if (override == EMPTY) {
            return this;
        }
        if (this == EMPTY) {
            return override;
        }
        return new JdbcExecutionOptions(override.fetchSize != null ? override.fetchSize : fetchSize,
                                        override.queryTimeout != null ? override.queryTimeout : queryTimeout,
                                        override.maxRows != null ? override.maxRows : maxRows);
    }

    /**
     * Builder for immutable {@link JdbcExecutionOptions} instances.
     * <p>
     * A builder is mutable and not thread-safe. It may be reused; each call to {@link #build()} creates an independent
     * immutable snapshot.
     */
    public static final class Builder {
        private Integer fetchSize;
        private Duration queryTimeout;
        private Long maxRows;

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
         * Creates an immutable options snapshot.
         *
         * @return immutable options
         */
        public JdbcExecutionOptions build() {
            if (fetchSize == null && queryTimeout == null && maxRows == null) {
                return EMPTY;
            }
            return new JdbcExecutionOptions(fetchSize, queryTimeout, maxRows);
        }
    }
}
