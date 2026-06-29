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

record GeneratedKeysRequest(boolean requested, List<String> columnNames) {

    private static final GeneratedKeysRequest NONE = new GeneratedKeysRequest(false, List.of());
    private static final GeneratedKeysRequest ANY = new GeneratedKeysRequest(true, List.of());

    GeneratedKeysRequest {
        Objects.requireNonNull(columnNames, "Generated-key column names must not be null");
        columnNames.forEach(columnName -> {
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("Generated-key column name must not be blank");
            }
        });
        columnNames = List.copyOf(columnNames);
    }

    static GeneratedKeysRequest none() {
        return NONE;
    }

    static GeneratedKeysRequest any() {
        return ANY;
    }

    static GeneratedKeysRequest columns(List<String> columnNames) {
        return new GeneratedKeysRequest(true, columnNames);
    }
}
