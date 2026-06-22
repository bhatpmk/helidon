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
import java.util.function.Supplier;

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents a supplier of results that throws {@link SQLException}s.
 *
 * <p>There is no requirement that a new or distinct result be returned each
 * time the supplier is invoked.
 *
 * <p>This is a functional interface whose functional method is {@link #get()}.
 *
 * @param <T> the type of results supplied by this supplier
 * @see java.util.function.Supplier
 */
@FunctionalInterface
@io.helidon.common.Api.Internal
public interface JdbcSupplier<T> {

    /**
     * Gets a result.
     *
     * @return a result
     * @throws SQLException if a database error occurs
     */
    T get() throws SQLException;

    /**
     * Returns a non-{@code null} {@link Supplier} equivalent to this {@link JdbcSupplier} that wraps any
     * thrown {@link SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link Supplier}
     * @see Supplier
     * @see UncheckedSQLException
     */
    default Supplier<T> toSupplier() {
        return () -> {
            try {
                return this.get();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcSupplier} equivalent to the supplied {@link Supplier}.
     *
     * @param <T> the supplied type
     * @param s a non-{@code null} {@link Supplier}
     * @return a non-{@code null} {@link JdbcSupplier}
     * @throws NullPointerException if {@code s} is {@code null}
     */
    static <T> JdbcSupplier<T> of(Supplier<T> s) {
        return s::get;
    }


}
