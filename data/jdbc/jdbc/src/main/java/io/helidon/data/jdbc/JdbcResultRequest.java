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

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Factory and immutable request types for callback-based JDBC result consumption.
 * <p>
 * A request contains its synchronous callback and may carry immutable statement options. It does not contain SQL,
 * bindings, mapping metadata, transaction state, a mutable configuration builder, or a JDBC resource.
 */
public final class JdbcResultRequest {
    private JdbcResultRequest() {
    }

    /**
     * Creates a request that visits every mapped row.
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
     * Creates a request that visits rows until exhaustion or predicate-directed termination.
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
     * Creates a request that consumes stored-procedure or function results without returning a value.
     *
     * @param action callback invoked while the callable statement remains open
     * @return immutable call-use request
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static Call call(CallConsumer action) {
        return new Call(Objects.requireNonNull(action, "Call consumer must not be null"),
                        JdbcStatementOptions.EMPTY);
    }

    /**
     * Creates a request that consumes stored-procedure or function results and returns a detached value.
     *
     * @param action callback invoked while the callable statement remains open
     * @param <R> detached result type
     * @return immutable call-with-result request
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static <R> CallWith<R> call(CallFunction<R> action) {
        return new CallWith<>(Objects.requireNonNull(action, "Call function must not be null"),
                              JdbcStatementOptions.EMPTY);
    }

    /**
     * Immutable request to visit every mapped row.
     * <p>
     * The provider invokes the callback synchronously and does not expose a JDBC resource to it. The request may be
     * reused, but the callback must be safe for the application's reuse pattern.
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
         * Creates a request with the supplied statement options overlaid on this request.
         *
         * @param options immutable statement options
         * @return new immutable request
         * @throws NullPointerException if {@code options} is {@code null}
         */
        public VisitAll<T> withOptions(JdbcStatementOptions options) {
            return new VisitAll<>(action,
                                  this.options.overlay(Objects.requireNonNull(options,
                                                                             "Statement options must not be null")));
        }

        /**
         * Passes one mapped row to the configured callback.
         *
         * @param value mapped row, must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        @Override
        public void accept(T value) {
            action.accept(Objects.requireNonNull(value, "Mapped row must not be null"));
        }

        /**
         * Returns the immutable statement options carried by this request.
         *
         * @return statement options
         */
        JdbcStatementOptions options() {
            return options;
        }
    }

    /**
     * Immutable request to visit rows until exhaustion or predicate-directed termination.
     * <p>
     * The provider invokes the predicate synchronously and does not expose a JDBC resource to it. The request may be
     * reused, but the predicate must be safe for the application's reuse pattern.
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
         * Creates a request with the supplied statement options overlaid on this request.
         *
         * @param options immutable statement options
         * @return new immutable request
         * @throws NullPointerException if {@code options} is {@code null}
         */
        public VisitWhile<T> withOptions(JdbcStatementOptions options) {
            return new VisitWhile<>(action,
                                    this.options.overlay(Objects.requireNonNull(options,
                                                                               "Statement options must not be null")));
        }

        /**
         * Passes one mapped row to the configured continuation predicate.
         *
         * @param value mapped row, must not be {@code null}
         * @return {@code true} to read another row, or {@code false} to stop normally
         * @throws NullPointerException if {@code value} is {@code null}
         */
        @Override
        public boolean test(T value) {
            return action.test(Objects.requireNonNull(value, "Mapped row must not be null"));
        }

        /**
         * Returns the immutable statement options carried by this request.
         *
         * @return statement options
         */
        JdbcStatementOptions options() {
            return options;
        }
    }

    /**
     * Callback that consumes a procedure or function while its scoped results remain valid.
     */
    @FunctionalInterface
    public interface CallConsumer {
        /**
         * Consumes callable results synchronously.
         *
         * @param call callback-scoped callable result view
         */
        void accept(JdbcClient.CallScope call);
    }

    /**
     * Callback that consumes a procedure or function and constructs a detached application result.
     *
     * @param <R> detached result type
     */
    @FunctionalInterface
    public interface CallFunction<R> {
        /**
         * Consumes callable results synchronously.
         *
         * @param call callback-scoped callable result view
         * @return detached application result, never {@code null}
         */
        R apply(JdbcClient.CallScope call);
    }

    /**
     * Immutable request to consume callable results without returning an application value.
     * <p>
     * The callback and every view it receives are synchronous and valid only until the callback returns.
     */
    public static final class Call implements CallConsumer {
        private final CallConsumer action;
        private final JdbcStatementOptions options;

        private Call(CallConsumer action, JdbcStatementOptions options) {
            this.action = action;
            this.options = options;
        }

        /**
         * Creates a request with the supplied statement options overlaid on this request.
         *
         * @param options immutable statement options
         * @return new immutable request
         * @throws NullPointerException if {@code options} is {@code null}
         */
        public Call withOptions(JdbcStatementOptions options) {
            return new Call(action,
                            this.options.overlay(Objects.requireNonNull(options,
                                                                       "Statement options must not be null")));
        }

        /**
         * Invokes the configured callback.
         *
         * @param call callback-scoped callable result view
         * @throws NullPointerException if {@code call} is {@code null}
         */
        @Override
        public void accept(JdbcClient.CallScope call) {
            action.accept(Objects.requireNonNull(call, "Call scope must not be null"));
        }

        JdbcStatementOptions options() {
            return options;
        }
    }

    /**
     * Immutable request to consume callable results and construct a detached application value.
     * <p>
     * The callback and every view it receives are synchronous and valid only until the callback returns.
     *
     * @param <R> detached result type
     */
    public static final class CallWith<R> implements CallFunction<R> {
        private final CallFunction<R> action;
        private final JdbcStatementOptions options;

        private CallWith(CallFunction<R> action, JdbcStatementOptions options) {
            this.action = action;
            this.options = options;
        }

        /**
         * Creates a request with the supplied statement options overlaid on this request.
         *
         * @param options immutable statement options
         * @return new immutable request
         * @throws NullPointerException if {@code options} is {@code null}
         */
        public CallWith<R> withOptions(JdbcStatementOptions options) {
            return new CallWith<>(action,
                                  this.options.overlay(Objects.requireNonNull(options,
                                                                             "Statement options must not be null")));
        }

        /**
         * Invokes the configured callback.
         *
         * @param call callback-scoped callable result view
         * @return detached application result, never {@code null}
         * @throws NullPointerException if {@code call} is {@code null}
         */
        @Override
        public R apply(JdbcClient.CallScope call) {
            return Objects.requireNonNull(action.apply(Objects.requireNonNull(call, "Call scope must not be null")),
                                          "Call function result must not be null");
        }

        JdbcStatementOptions options() {
            return options;
        }
    }
}
