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

record JdbcTranscript(List<StepTranscript> steps) {

    JdbcTranscript {
        Objects.requireNonNull(steps, "Steps must not be null");
        steps = List.copyOf(steps);
    }

    StepTranscript onlyStep() {
        if (steps.size() != 1) {
            throw new DataException("Expected exactly one JDBC transcript step, but found " + steps.size());
        }
        return steps.getFirst();
    }
}
