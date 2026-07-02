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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Detached callable scalar and cursor outputs.
 *
 * The explicit fields make it clear that callable outputs are attachments, rather than entries in the ordered direct
 * result sequence.
 */
final class JdbcCallableOutputs {

    static final JdbcCallableOutputs EMPTY = new JdbcCallableOutputs(Map.of(), Map.of());

    private final Map<String, Object> values;
    private final Map<String, RowSet> cursors;

    JdbcCallableOutputs(Map<String, Object> values, Map<String, RowSet> cursors) {
        Objects.requireNonNull(values, "Callable output values must not be null");
        Objects.requireNonNull(cursors, "Callable cursor outputs must not be null");
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.cursors = Collections.unmodifiableMap(new LinkedHashMap<>(cursors));
    }

    Map<String, Object> values() {
        return values;
    }

    Map<String, RowSet> cursors() {
        return cursors;
    }

    Optional<RowSet> cursor(String name) {
        return Optional.ofNullable(cursors.get(name));
    }
}
