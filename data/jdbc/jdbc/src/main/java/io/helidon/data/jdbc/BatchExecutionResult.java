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
 * An {@code enum} whose constants correspond to the defined batch execution result {@code int} constants of the {@link
 * Statement} class.
 *
 * @see Statement#executeBatch()
 * @see Statement#executeLargeBatch()
 * @see Statement#EXECUTE_FAILED
 * @see Statement#SUCCESS_NO_INFO
 */
public enum BatchExecutionResult {

    /**
     * Represents {@link Statement#EXECUTE_FAILED}.
     */
    EXECUTION_FAILURE(Statement.EXECUTE_FAILED),

    /**
     * Represents {@link Statement#SUCCESS_NO_INFO}.
     */
    SUCCESS_NO_INFO(Statement.SUCCESS_NO_INFO);

    private final int value;

    BatchExecutionResult(int batchExecutionResult) {
        this.value = switch (batchExecutionResult) {
        case Statement.EXECUTE_FAILED, Statement.SUCCESS_NO_INFO -> batchExecutionResult;
        default -> throw new IllegalArgumentException("batchExecutionResult: " + batchExecutionResult);
        };
    }

    /**
     * Returns the appropriate batch execution result constant value.
     *
     * @return the appropriate batch execution result constant value
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link BatchExecutionResult} appropriate for the supplied {@code batchExecutionResult} constant, which
     * must be either {@link Statement#EXECUTE_FAILED} or {@link Statement#SUCCESS_NO_INFO}.
     *
     * @param batchExecutionResult a batch execution result constant
     * @return a non-{@code null} {@link BatchExecutionResult} appropriate for the supplied {@code batchExecutionResult}
     * constant
     * @exception IllegalArgumentException if {@code batchExecutionResult} is not a valid value
     * @see Statement#executeBatch()
     * @see Statement#executeLargeBatch()
     * @see Statement#EXECUTE_FAILED
     * @see Statement#SUCCESS_NO_INFO
     */
    public static BatchExecutionResult of(int batchExecutionResult) {
        return switch (batchExecutionResult) {
        case Statement.EXECUTE_FAILED -> EXECUTION_FAILURE;
        case Statement.SUCCESS_NO_INFO -> SUCCESS_NO_INFO;
        default -> throw new IllegalArgumentException("batchExecutionResult: " + batchExecutionResult);
        };
    }

}
