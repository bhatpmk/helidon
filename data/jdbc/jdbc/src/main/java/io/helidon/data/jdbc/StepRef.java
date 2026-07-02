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
 * Stable identifier for one step inside a {@link JdbcExecutionResult}.
 * <p>
 * Steps are ordered by their zero-based index. A name is optional and is intended for future multi-operation plans
 * where a generated repository method or imperative plan may want to refer to a step by a stable logical name.
 *
 * The explicit fields and accessors make this identifier easy to follow while inspecting a multi-operation result.
 */
final class StepRef {
    private final int index;
    private final Optional<String> name;

    /**
     * Validate and normalize a step reference created by the runtime.
     */
    StepRef(int index, Optional<String> name) {
        // Reject negative indexes because operation results are always ordered from zero upward.
        if (index < 0) {
            throw new IllegalArgumentException("Step index must not be negative");
        }

        // Normalize a null optional to an empty optional so callers never need null checks.
        this.index = index;
        this.name = name == null ? Optional.empty() : name;
    }

    int index() {
        return index;
    }

    Optional<String> name() {
        return name;
    }

    /**
     * Create an unnamed step reference for the supplied zero-based step index.
     *
     * @param index zero-based step index
     * @return unnamed step reference
     */
    static StepRef create(int index) {
        // Build a step reference without a name for the common single-step path.
        return new StepRef(index, Optional.empty());
    }
}
