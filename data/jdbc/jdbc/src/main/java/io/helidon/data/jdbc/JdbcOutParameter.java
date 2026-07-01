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

import java.util.Objects;

record JdbcOutParameter(int index, String name, int sqlType, boolean cursor) {

    static JdbcOutParameter scalar(int index, String name, int sqlType) {
        return new JdbcOutParameter(index, name, sqlType, false);
    }

    static JdbcOutParameter cursor(int index, String name, int sqlType) {
        return new JdbcOutParameter(index, name, sqlType, true);
    }

    JdbcOutParameter {
        if (index < 1) {
            throw new IllegalArgumentException("OUT parameter index must be positive");
        }
        Objects.requireNonNull(name, "OUT parameter name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("OUT parameter name must not be blank");
        }
    }
}
