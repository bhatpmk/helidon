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
 * An {@code enum} whose constants correspond to the defined results advancement {@code int} constants of the {@link
 * Statement} class.
 *
 * @see Statement#getMoreResults(int)
 * @see Statement#CLOSE_ALL_RESULTS
 * @see Statement#CLOSE_CURRENT_RESULT
 * @see Statement#KEEP_CURRENT_RESULT
 */
public enum ResultsAdvancementBehavior {

    /**
     * Represents {@link Statement#CLOSE_ALL_RESULTS}.
     */
    CLOSE_ALL_RESULTS(Statement.CLOSE_ALL_RESULTS),

    /**
     * Represents {@link Statement#CLOSE_CURRENT_RESULT}.
     */
    CLOSE_CURRENT_RESULT(Statement.CLOSE_CURRENT_RESULT),

    /**
     * Represents {@link Statement#KEEP_CURRENT_RESULT}.
     */
    KEEP_CURRENT_RESULT(Statement.KEEP_CURRENT_RESULT),

    /**
     * Indicates unspecified behavior.
     */
    UNSPECIFIED(-1);

    private final int value;

    ResultsAdvancementBehavior(int resultsAdvancementBehavior) {
        this.value = switch (resultsAdvancementBehavior) {
        case Statement.CLOSE_ALL_RESULTS,
        Statement.CLOSE_CURRENT_RESULT,
        Statement.KEEP_CURRENT_RESULT,
        -1 -> resultsAdvancementBehavior;
        default -> throw new IllegalArgumentException("resultsAdvancementBehavior: " + resultsAdvancementBehavior);
        };
    }

    /**
     * Returns the appropriate results advancement constant value.
     *
     * @return the appropriate results advancement constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link ResultsAdvancementBehavior} appropriate for the supplied {@code resultsAdvancementBehavior}
     * constant, which must be either {@link Statement#CLOSE_ALL_RESULTS}, {@link Statement#CLOSE_CURRENT_RESULT},
     * {@link Statement#KEEP_CURRENT_RESULT}, or {@code -1} to indicate unspecified behavior.
     *
     * @param resultsAdvancementBehavior a results advancement behavior constant, or {@code -1} to indicate unspecified
     * behavior
     * @return a non-{@code null} {@link ResultsAdvancementBehavior} appropriate for the supplied {@code
     * resultsAdvancementBehavior} constant
     * @exception IllegalArgumentException if {@code resultsAdvancementBehavior} is not a valid value
     * @see Statement#getMoreResults(int)
     * @see Statement#CLOSE_ALL_RESULTS
     * @see Statement#CLOSE_CURRENT_RESULT
     * @see Statement#KEEP_CURRENT_RESULT
     */
    public static ResultsAdvancementBehavior of(int resultsAdvancementBehavior) {
        return switch (resultsAdvancementBehavior) {
        case Statement.CLOSE_ALL_RESULTS -> CLOSE_ALL_RESULTS;
        case Statement.CLOSE_CURRENT_RESULT -> CLOSE_CURRENT_RESULT;
        case Statement.KEEP_CURRENT_RESULT -> KEEP_CURRENT_RESULT;
        case -1 -> UNSPECIFIED;
        default -> throw new IllegalArgumentException("resultsAdvancementBehavior: " + resultsAdvancementBehavior);
        };
    }

}
