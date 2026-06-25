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

import io.helidon.data.jdbc.UncheckedSQLException;

/**
 * Represents an operation that does not return a result and that throws {@link SQLException}s.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #run()}.
 *
 * @see Runnable
 */
@FunctionalInterface
@io.helidon.common.Api.Internal
public interface JdbcRunnable {

    /**
     * Runs this operation.
     *
     * @throws SQLException if a database error occurs
     * @see Runnable
     */
    void run() throws SQLException;

    /**
     * Returns a non-{@code null} {@link Runnable} equivalent to this {@link JdbcRunnable} that wraps any thrown {@link
     * SQLException}s in {@link UncheckedSQLException}s.
     *
     * @return a non-{@code null} {@link Runnable}
     * @see Runnable
     * @see UncheckedSQLException
     */
    default Runnable toRunnable() {
        return () -> {
            try {
                this.run();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Returns a non-{@code null} {@link JdbcRunnable} equivalent to the supplied {@link Runnable}.
     *
     * @param r a non-{@code null} {@link Runnable}
     * @return a non-{@code null} {@link JdbcRunnable}
     * @throws NullPointerException if {@code r} is {@code null}
     */
    static JdbcRunnable of(Runnable r) {
        return r::run;
    }

}
