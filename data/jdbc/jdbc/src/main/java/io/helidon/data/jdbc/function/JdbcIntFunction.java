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
package io.helidon.data.jdbc.function;

import java.sql.SQLException;
import java.util.function.IntFunction;

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents a function that throws {@link SQLException}s and accepts an {@code int}-valued argument and produces a
 * result.
 *
 * <p>This is the {@code int}-consuming primitive specialization for
 * {@link JdbcFunction}.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #apply(int)}.
 *
 * @param <R> the type of the result of the function
 * @see java.util.function.IntFunction
 */
@FunctionalInterface
public interface JdbcIntFunction<R> {

    /**
     * Applies this function to the given argument.
     *
     * @param value the function argument
     * @return the function result
     * @throws SQLException if a database error occurs
     */
    R apply(int value) throws SQLException;

    /**
     * Returns a non-{@code null} {@link IntFunction} equivalent to this {@link JdbcIntFunction} that wraps any thrown
     * {@link SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link IntFunction}
     * @see IntFunction
     * @see UncheckedSQLException
     */
    default IntFunction<R> toIntFunction() {
        return i -> {
            try {
                return this.apply(i);
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcIntFunction} equivalent to the supplied {@link IntFunction}..
     *
     * @param <R> the return type
     * @param f a non-{@code null} {@link IntFunction}
     * @return a non-{@code null} {@link JdbcIntFunction}
     * @throws NullPointerException if {@code lf} is {@code null}
     */
    static <R> JdbcIntFunction<R> of(IntFunction<R> f) {
        return f::apply;
    }


}
