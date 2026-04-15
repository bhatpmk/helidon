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

import static java.util.Objects.requireNonNull;

/**
 * Represents an operation that does not return a result and that throws {@link SQLException}s.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #run()}.
 *
 * @see Runnable
 */
@FunctionalInterface
public interface JdbcRunnable {

    /**
     * Runs this operation.
     *
     * @throws SQLException if a database error occurs
     * @see Runnable
     */
    void run() throws SQLException;

    /**
     * Returns the supplied {@link JdbcRunnable} as a {@link Runnable}.
     *
     * <p>The returned {@link Runnable} may throw {@link UncheckedSQLException}s in addition to any other exceptions it
     * may throw.</p>
     *
     * @param jr a non-{@code null} {@link JdbcRunnable}
     * @return a non-{@code null} {@link Runnable}
     * @throws NullPointerException if {@code r} is {@code null}
     * @see UncheckedSQLException
     */
    static Runnable runnable(JdbcRunnable jr) {
        requireNonNull(jr, "jr");
        return () -> {
            try {
                jr.run();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

}
