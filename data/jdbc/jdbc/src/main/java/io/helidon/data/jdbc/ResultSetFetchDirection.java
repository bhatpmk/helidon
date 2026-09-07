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
 * An {@code enum} whose constants correspond to the defined fetch direction {@code int} constants of the {@link
 * ResultSet} class.
 *
 * @see ResultSet#FETCH_FORWARD
 * @see ResultSet#FETCH_REVERSE
 * @see ResultSet#FETCH_UNKNOWN
 */
public enum ResultSetFetchDirection {

    /**
     * Represents {@link ResultSet#FETCH_FORWARD}.
     */
    FORWARD(ResultSet.FETCH_FORWARD),

    /**
     * Represents {@link ResultSet#FETCH_REVERSE}.
     */
    REVERSE(ResultSet.FETCH_REVERSE),

    /**
     * Represents {@link ResultSet#FETCH_UNKNOWN}.
     */
    UNKNOWN(ResultSet.FETCH_UNKNOWN);

    private final int value;

    ResultSetFetchDirection(int resultSetFetchDirection) {
        this.value = switch (resultSetFetchDirection) {
        case ResultSet.FETCH_FORWARD, ResultSet.FETCH_REVERSE, ResultSet.FETCH_UNKNOWN -> resultSetFetchDirection;
        default -> throw new IllegalArgumentException("resultSetFetchDirection: " + resultSetFetchDirection);
        };
    }

    /**
     * Returns the appropriate fetchDirection constant value.
     *
     * @return the appropriate fetchDirection constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link ResultSetFetchDirection} appropriate for the supplied {@code resultSetFetchDirection} constant, which
     * must be either {@link ResultSet#FETCH_FORWARD}, {@link ResultSet#FETCH_REVERSE}, or {@link ResultSet#FETCH_UNKNOWN}.
     *
     * @param resultSetFetchDirection a fetchDirection constant
     * @return a non-{@code null} {@link ResultSetFetchDirection} appropriate for the supplied {@code resultSetFetchDirection}
     * constant
     * @exception IllegalArgumentException if {@code resultSetFetchDirection} is not a valid value
     * @see ResultSet#FETCH_FORWARD
     * @see ResultSet#FETCH_REVERSE
     * @see ResultSet#FETCH_UNKNOWN
     */
    public static ResultSetFetchDirection of(int resultSetFetchDirection) {
        return switch (resultSetFetchDirection) {
        case ResultSet.FETCH_FORWARD -> FORWARD;
        case ResultSet.FETCH_REVERSE -> REVERSE;
        case ResultSet.FETCH_UNKNOWN -> UNKNOWN;
        default -> throw new IllegalArgumentException("resultSetFetchDirection: " + resultSetFetchDirection);
        };
    }

}
