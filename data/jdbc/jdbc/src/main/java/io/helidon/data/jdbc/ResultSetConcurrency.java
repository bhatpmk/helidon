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

import java.sql.ResultSet;

/**
 * An {@code enum} whose constants correspond to the defined concurrency {@code int} constants of the {@link ResultSet}
 * class.
 *
 * @see ResultSet#TYPE_FORWARD_ONLY
 * @see ResultSet#TYPE_SCROLL_INSENSITIVE
 * @see ResultSet#TYPE_SCROLL_SENSITIVE
 */
public enum ResultSetConcurrency {

    /**
     * Represents {@link ResultSet#CONCUR_READ_ONLY}.
     */
    READ_ONLY(ResultSet.CONCUR_READ_ONLY),

    /**
     * Represents {@link ResultSet#CONCUR_UPDATABLE}.
     */
    UPDATABLE(ResultSet.CONCUR_UPDATABLE);

    private final int value;

    ResultSetConcurrency(int resultSetConcurrency) {
        this.value = switch (resultSetConcurrency) {
        case ResultSet.CONCUR_READ_ONLY, ResultSet.CONCUR_UPDATABLE -> resultSetConcurrency;
        default -> throw new IllegalArgumentException("resultSetConcurrency: " + resultSetConcurrency);
        };
    }

    /**
     * Returns the appropriate concurrency constant value.
     *
     * @return the appropriate concurrency constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link ResultSetConcurrency} appropriate for the supplied {@code resultSetConcurrency} constant, which
     * must be either {@link ResultSet#CONCUR_READ_ONLY} or {@link ResultSet#CONCUR_UPDATABLE}.
     *
     * @param resultSetConcurrency a concurrency constant
     * @return a non-{@code null} {@link ResultSetConcurrency} appropriate for the supplied {@code resultSetConcurrency}
     * constant
     * @exception IllegalArgumentException if {@code resultSetConcurrency} is not a valid value
     * @see ResultSet#CONCUR_READ_ONLY
     * @see ResultSet#CONCUR_UPDATABLE
     */
    public static ResultSetConcurrency of(int resultSetConcurrency) {
        return switch (resultSetConcurrency) {
        case ResultSet.CONCUR_READ_ONLY -> READ_ONLY;
        case ResultSet.CONCUR_UPDATABLE -> UPDATABLE;
        default -> throw new IllegalArgumentException("resultSetConcurrency: " + resultSetConcurrency);
        };
    }

}
