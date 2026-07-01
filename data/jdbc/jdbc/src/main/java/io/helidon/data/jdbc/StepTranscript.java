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
import java.util.Optional;

/**
 * Detached transcript for one executed JDBC operation.
 * <p>
 * A step corresponds to a single operation in a {@link JdbcPlan}. Most repository methods currently produce one step,
 * while a multi-operation plan can produce several steps in order. Each step keeps the operation kind, the ordered
 * result events returned by JDBC, warnings reported by the statement, and optional failure metadata captured before a
 * runtime exception is raised.
 * <p>
 * Events preserve JDBC result order within the step. For example, a callable statement may produce OUT parameter
 * values, cursor rows, direct result sets, and update counts. Reducers use that ordered event list to choose the event
 * that matches the requested repository or imperative return value.
 *
 * @param ref stable reference to this step within the containing transcript
 * @param kind operation kind used for diagnostics and reducer validation
 * @param events ordered JDBC outcomes captured for this step
 * @param warnings SQL warnings reported while the JDBC statement was open
 * @param failure optional failure metadata when execution failed after producing a partial transcript
 */
record StepTranscript(StepRef ref,
                      SqlKind kind,
                      List<JdbcEvent> events,
                      List<JdbcWarningInfo> warnings,
                      Optional<JdbcFailure> failure) {

    private static final boolean TRACE = Boolean.getBoolean("io.helidon.data.jdbc.trace");

    /**
     * Validate and detach the step data from mutable caller-owned collections.
     */
    StepTranscript {
        // Validate the step reference because every transcript step must be identifiable.
        Objects.requireNonNull(ref, "Step reference must not be null");

        // Validate the SQL kind because reducers use it for diagnostics and shape checks.
        Objects.requireNonNull(kind, "SQL kind must not be null");

        // Validate the event list before making an immutable copy.
        Objects.requireNonNull(events, "Events must not be null");

        // Validate the warning list before making an immutable copy.
        Objects.requireNonNull(warnings, "Warnings must not be null");

        // Detach the event list so later caller changes cannot mutate the transcript.
        events = List.copyOf(events);

        // Detach the warning list so later caller changes cannot mutate the transcript.
        warnings = List.copyOf(warnings);

        // Normalize a null failure optional to empty so reducers never need null checks.
        failure = failure == null ? Optional.empty() : failure;

        // Print a compact step summary when JDBC tracing is enabled.
        trace("created step index=" + ref.index()
                      + ", kind=" + kind
                      + ", events=" + events.size()
                      + ", warnings=" + warnings.size()
                      + ", failure=" + failure.isPresent());
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
        System.out.println("[StepTranscript] " + message);
    }
}
