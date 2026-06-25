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
import java.util.function.Function;

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents a function that throws {@link SQLException}s that accepts one argument and produces a result.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #apply(Object)}.
 *
 * @param <T> the type of the input to the function
 * @param <R> the type of the result of the function
 * @see java.util.function.Function
 */
@FunctionalInterface
@io.helidon.common.Api.Internal
public interface JdbcFunction<T, R> {

    /**
     * Applies this function to the given argument.
     *
     * @param t the function argument
     * @return the function result
     * @throws SQLException if a database error occurs
     */
    R apply(T t) throws SQLException;

    /**
     * Returns a non-{@code null} {@link Function} equivalent to this {@link JdbcFunction} that wraps any
     * thrown {@link SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link Function}
     * @see Function
     * @see UncheckedSQLException
     */
    default Function<T, R> toFunction() {
        return t -> {
            try {
                return this.apply(t);
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcFunction} equivalent to the supplied {@link Function}..
     *
     * @param <T> the type of the input to the function*
     * @param <R> the type of the result of the function
     * @param f a non-{@code null} {@link Function}
     * @return a non-{@code null} {@link JdbcFunction}
     * @throws NullPointerException if {@code f} is {@code null}
     */
    static <T, R> JdbcFunction<T, R> of(Function<T, R> f) {
        return f::apply;
    }


}
