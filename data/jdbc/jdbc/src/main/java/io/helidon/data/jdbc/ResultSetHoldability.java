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
 * An {@code enum} whose constants correspond to the defined holdability {@code int} constants of the {@link ResultSet}
 * class.
 *
 * @see ResultSet#CLOSE_CURSORS_AT_COMMIT
 * @see ResultSet#HOLD_CURSORS_OVER_COMMIT
 */
public enum ResultSetHoldability {

    /**
     * Represents {@link ResultSet#CLOSE_CURSORS_AT_COMMIT}.
     */
    CLOSE_CURSORS_AT_COMMIT(ResultSet.CLOSE_CURSORS_AT_COMMIT),

    /**
     * Represents {@link ResultSet#HOLD_CURSORS_OVER_COMMIT}.
     */
    HOLD_CURSORS_OVER_COMMIT(ResultSet.HOLD_CURSORS_OVER_COMMIT);

    private final int value;

    ResultSetHoldability(int resultSetHoldability) {
        this.value = switch (resultSetHoldability) {
        case ResultSet.CLOSE_CURSORS_AT_COMMIT, ResultSet.HOLD_CURSORS_OVER_COMMIT -> resultSetHoldability;
        default -> throw new IllegalArgumentException("resultSetHoldability: " + resultSetHoldability);
        };
    }

    /**
     * Returns the appropriate holdability constant value.
     *
     * @return the appropriate holdability constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link ResultSetHoldability} appropriate for the supplied {@code resultSetHoldability} constant, which
     * must be either {@link ResultSet#CLOSE_CURSORS_AT_COMMIT} or {@link ResultSet#HOLD_CURSORS_OVER_COMMIT}.
     *
     * @param resultSetHoldability a holdability constant
     * @return a non-{@code null} {@link ResultSetHoldability} appropriate for the supplied {@code resultSetHoldability}
     * constant
     * @exception IllegalArgumentException if {@code resultSetHoldability} is not a valid value
     * @see ResultSet#CLOSE_CURSORS_AT_COMMIT
     * @see ResultSet#HOLD_CURSORS_OVER_COMMIT
     */
    public static ResultSetHoldability of(int resultSetHoldability) {
        return switch (resultSetHoldability) {
        case ResultSet.CLOSE_CURSORS_AT_COMMIT -> CLOSE_CURSORS_AT_COMMIT;
        case ResultSet.HOLD_CURSORS_OVER_COMMIT -> HOLD_CURSORS_OVER_COMMIT;
        default -> throw new IllegalArgumentException("resultSetHoldability: " + resultSetHoldability);
        };
    }

}
