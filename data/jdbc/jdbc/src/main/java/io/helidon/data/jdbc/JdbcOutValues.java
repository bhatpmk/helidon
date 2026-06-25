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

import java.sql.SQLException;
import java.util.Iterator;

/**
 * A {@link JdbcResult} providing access to a {@link java.sql.CallableStatement}'s registered {@code OUT} parameter
 * values.
 *
 * <p>As with all constructs related to JDBC, a {@link JdbcOutValues} object is not necessarily safe for concurrent use
 * by multiple threads unless explicitly noted.</p>
 *
 * @see #hasNext()
 * @see #next()
 * @see #nextAs(Class)
 * @see java.sql.CallableStatement
 * @see java.sql.CallableStatement#getObject(int)
 * @see java.sql.CallableStatement#getObject(int, Class)
 */
@io.helidon.common.Api.Internal
public non-sealed interface JdbcOutValues extends Iterable<Object>, JdbcResult {

    /**
     * Returns {@code true} if and only if this {@link JdbcOutValues} has more values.
     *
     * @return {@code true} if and only if this {@link JdbcOutValues} has more values
     * @throws SQLException if a database error occurs
     * @see #next()
     */
    boolean hasNext() throws SQLException;

    /**
     * Returns an {@link Iterator} for this {@link JdbcOutValues}.
     *
     * <p>The returned {@link Iterator}'s methods may throw {@link UncheckedSQLException}s in addition to the throwing
     * of any other exceptions they may report.</p>
     *
     * @return a non-{@code null} {@link Iterator}
     * @see #hasNext()
     * @see #next()
     * @see UncheckedSQLException
     */
    default Iterator<Object> iterator() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    return JdbcOutValues.this.hasNext();
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
            @Override
            public Object next() {
                try {
                    return JdbcOutValues.this.next();
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };
    }

    /**
     * Returns the next value, if there is one.
     *
     * <p>The default implementation calls {@link #nextAs(Class)} with {@code java.lang.Object.class}.</p>
     *
     * @return the next value
     * @throws java.util.NoSuchElementException if there are no more values
     * @throws SQLException if a database error occurs
     * @see #nextAs(Class)
     * @see #hasNext()
     * @see java.sql.CallableStatement#getObject(int)
     */
    default Object next() throws SQLException {
        return this.nextAs(Object.class);
    }

    /**
     * Returns the next value, if there is one, converted via JDBC internals to the supplied type.
     *
     * @param <T> the conversion type
     * @param type a non-{@code null} {@link Class}
     * @return the next value
     * @throws java.util.NoSuchElementException if there are no more values
     * @throws SQLException if a database error occurs
     * @see #hasNext()
     * @see java.sql.CallableStatement#getObject(int, Class)
     */
    <T> T nextAs(Class<T> type) throws SQLException;

}
