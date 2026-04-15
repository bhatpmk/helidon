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
import java.util.function.LongFunction;

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents a function that throws {@link SQLException}s and accepts an {@code long}-valued argument and produces a
 * result.
 *
 * <p>This is the {@code long}-consuming primitive specialization for
 * {@link JdbcFunction}.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #apply(long)}.
 *
 * @param <R> the type of the result of the function
 * @see java.util.function.LongFunction
 */
@FunctionalInterface
public interface JdbcLongFunction<R> {

    /**
     * Applies this function to the given argument.
     *
     * @param value the function argument
     * @return the function result
     * @throws SQLException if a database error occurs
     */
    R apply(long value) throws SQLException;

    /**
     * Returns a non-{@code null} {@link LongFunction} equivalent to this {@link JdbcLongFunction} that wraps any
     * thrown {@link SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link LongFunction}
     * @see LongFunction
     * @see UncheckedSQLException
     */
    default LongFunction<R> toLongFunction() {
        return l -> {
            try {
                return this.apply(l);
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcLongFunction} equivalent to the supplied {@link LongFunction}..
     *
     * @param <R> the return type
     * @param lf a non-{@code null} {@link LongFunction}
     * @return a non-{@code null} {@link JdbcLongFunction}
     * @throws NullPointerException if {@code lf} is {@code null}
     */
    static <R> JdbcLongFunction<R> of(LongFunction<R> lf) {
        return lf::apply;
    }

}
