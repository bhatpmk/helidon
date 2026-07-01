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

import java.util.Optional;

/**
 * Stable identifier for one step inside a {@link JdbcTranscript}.
 * <p>
 * Steps are ordered by their zero-based index. A name is optional and is intended for future multi-operation plans
 * where a generated repository method or imperative plan may want to refer to a step by a stable logical name.
 *
 * @param index zero-based step index
 * @param name optional logical step name
 */
record StepRef(int index, Optional<String> name) {

    private static final boolean TRACE = Boolean.getBoolean("io.helidon.data.jdbc.trace");

    /**
     * Validate and normalize a step reference created by the runtime.
     */
    StepRef {
        // Reject negative indexes because transcript steps are always ordered from zero upward.
        if (index < 0) {
            throw new IllegalArgumentException("Step index must not be negative");
        }

        // Normalize a null optional to an empty optional so callers never need null checks.
        name = name == null ? Optional.empty() : name;

        // Print the normalized step reference when JDBC tracing is enabled.
        trace("created step reference index=" + index + ", name=" + name.orElse("<none>"));
    }

    /**
     * Create an unnamed step reference for the supplied zero-based step index.
     *
     * @param index zero-based step index
     * @return unnamed step reference
     */
    static StepRef create(int index) {
        // Print the requested index before building the step reference.
        trace("creating unnamed step reference for index=" + index);

        // Build a step reference without a name for the common single-step path.
        return new StepRef(index, Optional.empty());
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
        System.out.println("[StepRef] " + message);
    }
}
