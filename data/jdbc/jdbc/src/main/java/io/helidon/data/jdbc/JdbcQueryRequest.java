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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Immutable, invocation-scoped JDBC query request and factory for typed callback-based row traversal requests.
 * <p>
 * A regular request contains portable statement settings and may be passed to materializing, cardinality, reduction, or
 * generated-key terminals. The {@link VisitAll} and {@link VisitWhile} variants additionally contain one synchronous
 * callback. No request contains SQL, bindings, mapping metadata, transaction state, or a JDBC resource. A configured
 * request setting overrides the corresponding setting on the statement stage. An unset setting preserves the statement,
 * client, datasource, or driver value.
 */
public final class JdbcQueryRequest {
    private static final JdbcQueryRequest DEFAULTS = new JdbcQueryRequest(JdbcStatementOptions.EMPTY);

    private final JdbcStatementOptions options;

    private JdbcQueryRequest(JdbcStatementOptions options) {
        this.options = options;
    }

    /**
     * Returns a reusable regular query request that preserves all statement defaults.
     * <p>
     * A repository method that declares a leading request requires an argument even when one invocation needs no
     * overrides. This shared immutable value avoids allocating an otherwise empty request.
     *
     * @return immutable request with no configured overrides
     */
    public static JdbcQueryRequest defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a request that visits every mapped row using provider and driver statement defaults.
     *
     * @param action callback invoked for every mapped row
     * @param <T> mapped row type
     * @return immutable visit-all request
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static <T> VisitAll<T> visitAll(Consumer<? super T> action) {
        return new VisitAll<>(Objects.requireNonNull(action, "Row action must not be null"), JdbcStatementOptions.EMPTY);
    }

    /**
     * Creates an early-termination request using provider and driver statement defaults.
     *
     * @param action predicate invoked for every mapped row until it returns {@code false}
     * @param <T> mapped row type
     * @return immutable predicate traversal request
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static <T> VisitWhile<T> visitWhile(Predicate<? super T> action) {
        return new VisitWhile<>(Objects.requireNonNull(action, "Row continuation predicate must not be null"),
                                  JdbcStatementOptions.EMPTY);
    }

    /**
     * Creates a mutable, single-use request builder.
     *
     * @param <T> mapped row type
     * @return new request builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Returns the immutable statement settings captured with this request.
     *
     * @return statement settings
     */
    JdbcStatementOptions options() {
        return options;
    }

    /**
     * Immutable request to visit every mapped row.
     * <p>
     * The provider invokes the callback synchronously and does not expose a JDBC resource to it. The value may be reused
     * sequentially. Concurrent reuse is safe only when the supplied callback is itself safe for concurrent invocation;
     * the provider does not synchronize application callback state.
     *
     * @param <T> mapped row type
     */
    public static final class VisitAll<T> implements Consumer<T> {
        private final Consumer<? super T> action;
        private final JdbcStatementOptions options;

        private VisitAll(Consumer<? super T> action, JdbcStatementOptions options) {
            this.action = action;
            this.options = options;
        }

        /**
         * Passes one mapped row to the configured callback.
         * <p>
         * Applications normally pass this request to {@link JdbcClient.Rows#visitAll(VisitAll)}. This method also keeps
         * the request usable by another public {@link JdbcClient} implementation without exposing the wrapped callback.
         *
         * @param value mapped row
         */
        @Override
        public void accept(T value) {
            action.accept(value);
        }

        /**
         * Returns the immutable statement settings captured with this request.
         *
         * @return statement settings
         */
        JdbcStatementOptions options() {
            return options;
        }
    }

    /**
     * Immutable request to visit rows until exhaustion or predicate-directed termination.
     * <p>
     * The provider invokes the predicate synchronously and does not expose a JDBC resource to it. The value may be
     * reused sequentially. Concurrent reuse is safe only when the supplied predicate is itself safe for concurrent
     * invocation; the provider does not synchronize application callback state.
     *
     * @param <T> mapped row type
     */
    public static final class VisitWhile<T> implements Predicate<T> {
        private final Predicate<? super T> action;
        private final JdbcStatementOptions options;

        private VisitWhile(Predicate<? super T> action, JdbcStatementOptions options) {
            this.action = action;
            this.options = options;
        }

        /**
         * Passes one mapped row to the configured continuation predicate.
         * <p>
         * Applications normally pass this request to {@link JdbcClient.Rows#visitWhile(VisitWhile)}. This method
         * also keeps the request usable by another public {@link JdbcClient} implementation without exposing the
         * wrapped predicate.
         *
         * @param value mapped row
         * @return {@code true} to read another row, or {@code false} to stop normally
         */
        @Override
        public boolean test(T value) {
            return action.test(value);
        }

        /**
         * Returns the immutable statement settings captured with this request.
         *
         * @return statement settings
         */
        JdbcStatementOptions options() {
            return options;
        }
    }

    /**
     * Mutable, single-use builder for a regular query request or callback-based row traversal request.
     * <p>
     * Calling {@link #build()}, {@link #visitAll(Consumer)}, or {@link #visitWhile(Predicate)} creates one immutable
     * request snapshot and permanently completes this builder. Further configuration or request creation fails. The
     * builder is not thread-safe.
     *
     * @param <T> mapped row type
     */
    public static final class Builder<T> {
        private final JdbcStatementOptions.Builder options = JdbcStatementOptions.builder();
        private boolean completed;

        private Builder() {
        }

        /**
         * Requests the JDBC fetch size.
         *
         * @param rows requested rows; zero leaves the choice to the driver
         * @return this builder
         * @throws IllegalArgumentException if {@code rows} is negative
         * @throws IllegalStateException if this builder has already created a request
         */
        public Builder<T> fetchSize(int rows) {
            ensureOpen();
            options.fetchSize(rows);
            return this;
        }

        /**
         * Requests a whole-second JDBC query timeout.
         *
         * @param timeout non-negative whole-second timeout
         * @return this builder
         * @throws NullPointerException if {@code timeout} is {@code null}
         * @throws IllegalArgumentException if the duration is negative, contains fractional seconds, or exceeds the JDBC
         *         integer timeout range
         * @throws IllegalStateException if this builder has already created a request
         */
        public Builder<T> queryTimeout(Duration timeout) {
            ensureOpen();
            options.queryTimeout(timeout);
            return this;
        }

        /**
         * Requests the JDBC large maximum row count.
         *
         * @param rows maximum rows; zero means no limit
         * @return this builder
         * @throws IllegalArgumentException if {@code rows} is negative
         * @throws IllegalStateException if this builder has already created a request
         */
        public Builder<T> maxRows(long rows) {
            ensureOpen();
            options.maxRows(rows);
            return this;
        }

        /**
         * Supplies the JDBC statement-pooling hint.
         *
         * @param poolable whether the driver should consider the statement poolable
         * @return this builder
         * @throws IllegalStateException if this builder has already created a request
         */
        public Builder<T> poolableHint(boolean poolable) {
            ensureOpen();
            options.poolableHint(poolable);
            return this;
        }

        /**
         * Completes this builder with a configuration-only query request.
         *
         * @return immutable regular query request
         * @throws IllegalStateException if this builder has already created a request
         */
        public JdbcQueryRequest build() {
            complete();
            JdbcStatementOptions statementOptions = options.build();
            return statementOptions == JdbcStatementOptions.EMPTY
                    ? DEFAULTS
                    : new JdbcQueryRequest(statementOptions);
        }

        /**
         * Completes this builder with a request that visits every mapped row.
         *
         * @param action callback invoked for every mapped row
         * @return immutable visit-all request
         * @throws NullPointerException if {@code action} is {@code null}
         * @throws IllegalStateException if this builder has already created a request
         */
        public VisitAll<T> visitAll(Consumer<? super T> action) {
            Objects.requireNonNull(action, "Row action must not be null");
            complete();
            return new VisitAll<>(action, options.build());
        }

        /**
         * Completes this builder with predicate-controlled traversal.
         *
         * @param action predicate invoked until it returns {@code false}
         * @return immutable predicate traversal request
         * @throws NullPointerException if {@code action} is {@code null}
         * @throws IllegalStateException if this builder has already created a request
         */
        public VisitWhile<T> visitWhile(Predicate<? super T> action) {
            Objects.requireNonNull(action, "Row continuation predicate must not be null");
            complete();
            return new VisitWhile<>(action, options.build());
        }

        private void complete() {
            ensureOpen();
            completed = true;
        }

        private void ensureOpen() {
            if (completed) {
                throw new IllegalStateException("JdbcQueryRequest.Builder has already created a request");
            }
        }
    }
}
