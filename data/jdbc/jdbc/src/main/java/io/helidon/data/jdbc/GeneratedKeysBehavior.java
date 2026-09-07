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

import java.sql.Statement;

/**
 * An {@code enum} whose constants correspond to the defined generated keys {@code int} constants of the {@link
 * Statement} class.
 *
 * @see Statement#NO_GENERATED_KEYS
 * @see Statement#RETURN_GENERATED_KEYS
 */
public enum GeneratedKeysBehavior {

    /**
     * Represents {@link Statement#NO_GENERATED_KEYS}.
     */
    NONE(Statement.NO_GENERATED_KEYS),

    /**
     * Represents {@link Statement#RETURN_GENERATED_KEYS}.
     */
    RETURN(Statement.RETURN_GENERATED_KEYS),

    /**
     * Indicates that no generated keys behavior has been specified.
     */
    UNSPECIFIED(-1);

    private final int value;

    GeneratedKeysBehavior(int generatedKeysBehavior) {
        this.value = switch (generatedKeysBehavior) {
        case Statement.NO_GENERATED_KEYS, Statement.RETURN_GENERATED_KEYS, -1 -> generatedKeysBehavior;
        default -> throw new IllegalArgumentException("generatedKeysBehavior: " + generatedKeysBehavior);
        };
    }

    /**
     * Returns the appropriate generated keys constant value.
     *
     * @return the appropriate generated constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link GeneratedKeysBehavior} appropriate for the supplied {@code generatedKeysBehavior} constant,
     * which must be either {@link Statement#NO_GENERATED_KEYS}, {@link Statement#RETURN_GENERATED_KEYS}, or {@code -1}
     * to indicate unspecified behavior.
     *
     * @param generatedKeysBehavior a generated keys constant, or {@code -1} to indicate unspecified behavior
     * @return a non-{@code null} {@link GeneratedKeysBehavior} appropriate for the supplied {@code
     * generatedKeysBehavior} constant
     * @exception IllegalArgumentException if {@code generatedKeysBehavior} is not a valid value
     * @see Statement#NO_GENERATED_KEYS
     * @see Statement#RETURN_GENERATED_KEYS
     */
    public static GeneratedKeysBehavior of(int generatedKeysBehavior) {
        return switch (generatedKeysBehavior) {
        case Statement.NO_GENERATED_KEYS -> NONE;
        case Statement.RETURN_GENERATED_KEYS -> RETURN;
        case -1 -> UNSPECIFIED;
        default -> throw new IllegalArgumentException("generatedKeysBehavior: " + generatedKeysBehavior);
        };
    }

}
