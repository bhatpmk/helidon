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

import java.util.List;
import java.util.Objects;

import io.helidon.data.DataException;

/**
 * Detached record of everything observed while executing one JDBC operation or a small ordered JDBC plan.
 * <p>
 * JDBC can produce several different result shapes from the same execution API: result sets, update counts, generated
 * keys, scalar OUT parameters, cursor OUT parameters, warnings, and partial failure details. This transcript normalizes
 * those JDBC outcomes into ordered {@link StepTranscript steps}. The rest of the provider can then reduce the same
 * detached model to the Java return type requested by a generated repository method or by an imperative
 * {@link JdbcClient} caller.
 * <p>
 * A transcript must not retain live JDBC resources. {@code JdbcRunner} copies the information it needs while the
 * connection, statement, and result sets are open; reducers read only this object graph after those resources have been
 * closed.
 *
 * @param steps ordered execution steps, one per operation in the executed plan
 */
record JdbcTranscript(List<StepTranscript> steps) {

    private static final boolean TRACE = Boolean.getBoolean("io.helidon.data.jdbc.trace");

    /**
     * Validate and detach transcript steps from mutable caller-owned collections.
     */
    JdbcTranscript {
        // Validate the step list before making an immutable copy.
        Objects.requireNonNull(steps, "Steps must not be null");

        // Detach the step list so later caller changes cannot mutate the transcript.
        steps = List.copyOf(steps);

        // Print the transcript size when JDBC tracing is enabled.
        trace("created transcript with steps=" + steps.size());
    }

    /**
     * Return the only step when a reducer expects a single-step transcript.
     *
     * @return the single step in this transcript
     * @throws DataException if the transcript contains zero or multiple steps
     */
    StepTranscript onlyStep() {
        // Reject ambiguous transcript shapes before a reducer accidentally reads the wrong step.
        if (steps.size() != 1) {
            throw new DataException("Expected exactly one JDBC transcript step, but found " + steps.size());
        }

        // Print the step selection when JDBC tracing is enabled.
        trace("selected the only transcript step");

        // Return the first and only step for single-step reducers.
        return steps.getFirst();
    }

    /**
     * Print a trace message when JDBC tracing is enabled.
     *
     * @param message message to print
     */
    private static void trace(String message) {
        // Skip printing unless the user explicitly enables JDBC tracing.
        if (!TRACE) {
            return;
        }

        // Print the trace message with the class name for easier reading.
        System.out.println("[JdbcTranscript] " + message);
    }
}
