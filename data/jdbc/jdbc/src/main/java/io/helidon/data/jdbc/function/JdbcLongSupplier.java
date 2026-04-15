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
import java.util.function.LongSupplier;

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents a supplier of {@code long}-valued results that throws {@link SQLException}s.
 *
 * <p>This is the {@code long}-producing primitive specialization of
 * {@link JdbcSupplier}.
 *
 * <p>There is no requirement that a new or distinct result be returned each
 * time the supplier is invoked.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #getAsLong()}.
 *
 * @see java.util.function.LongSupplier
 */
@FunctionalInterface
public interface JdbcLongSupplier {

    /**
     * Gets a result.
     *
     * @return a result
     * @throws SQLException if a database error occurs
     */
    long getAsLong() throws SQLException;

    /**
     * Returns a non-{@code null} {@link LongSupplier} equivalent to this {@link JdbcLongSupplier} that wraps any
     * thrown {@link SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link LongSupplier}
     * @see LongSupplier
     * @see UncheckedSQLException
     */
    default LongSupplier toLongSupplier() {
        return () -> {
            try {
                return this.getAsLong();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcLongSupplier} equivalent to the supplied {@link LongSupplier}..
     *
     * @param ls a non-{@code null} {@link LongSupplier}
     * @return a non-{@code null} {@link JdbcLongSupplier}
     * @throws NullPointerException if {@code ls} is {@code null}
     */
    static JdbcLongSupplier of(LongSupplier ls) {
        return ls::getAsLong;
    }


}
