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
 * An {@code enum} whose constants correspond to the defined type {@code int} constants of the {@link ResultSet}
 * class.
 *
 * @see ResultSet#TYPE_FORWARD_ONLY
 * @see ResultSet#TYPE_SCROLL_INSENSITIVE
 * @see ResultSet#TYPE_SCROLL_SENSITIVE
 */
public enum ResultSetType {

    /**
     * Represents {@link ResultSet#TYPE_FORWARD_ONLY}.
     */
    FORWARD_ONLY(ResultSet.TYPE_FORWARD_ONLY),

    /**
     * Represents {@link ResultSet#TYPE_SCROLL_INSENSITIVE}.
     */
    SCROLL_INSENSITIVE(ResultSet.TYPE_SCROLL_INSENSITIVE),

    /**
     * Represents {@link ResultSet#TYPE_SCROLL_SENSITIVE}.
     */
    SCROLL_SENSITIVE(ResultSet.TYPE_SCROLL_SENSITIVE);

    private final int value;

    ResultSetType(int resultSetType) {
        this.value = switch (resultSetType) {
        case ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.TYPE_SCROLL_SENSITIVE -> resultSetType;
        default -> throw new IllegalArgumentException("resultSetType: " + resultSetType);
        };
    }

    /**
     * Returns the appropriate type constant value.
     *
     * @return the appropriate type constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link ResultSetType} appropriate for the supplied {@code resultSetType} constant, which must be either
     * {@link ResultSet#TYPE_FORWARD_ONLY}, {@link ResultSet#TYPE_SCROLL_INSENSITIVE}, or {@link
     * ResultSet#TYPE_SCROLL_SENSITIVE}.
     *
     * @param resultSetType a type constant
     * @return a non-{@code null} {@link ResultSetType} appropriate for the supplied {@code resultSetType}
     * constant
     * @exception IllegalArgumentException if {@code resultSetType} is not a valid value
     * @see ResultSet#TYPE_FORWARD_ONLY
     * @see ResultSet#TYPE_SCROLL_INSENSITIVE
     * @see ResultSet#TYPE_SCROLL_SENSITIVE
     */
    public static ResultSetType of(int resultSetType) {
        return switch (resultSetType) {
        case ResultSet.TYPE_FORWARD_ONLY -> FORWARD_ONLY;
        case ResultSet.TYPE_SCROLL_INSENSITIVE -> SCROLL_INSENSITIVE;
        case ResultSet.TYPE_SCROLL_SENSITIVE -> SCROLL_SENSITIVE;
        default -> throw new IllegalArgumentException("resultSetType: " + resultSetType);
        };
    }

}
