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

/**
 * Event containing scalar OUT and INOUT parameter values from a callable statement.
 * <p>
 * Values are keyed by the generated or user-provided OUT parameter name, not by JDBC position. Cursor OUT parameters
 * are represented separately as {@link RowsEvent} instances so row mapping and cursor reduction can use the same path
 * as ordinary result sets.
 *
 * @param step owning transcript step
 * @param ordinal event order within the step
 * @param values detached scalar OUT parameter values keyed by output name
 */
record OutParamsEvent(StepRef step, int ordinal, Map<String, Object> values) implements JdbcEvent {

    OutParamsEvent {
        Objects.requireNonNull(values, "OUT parameter values must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
