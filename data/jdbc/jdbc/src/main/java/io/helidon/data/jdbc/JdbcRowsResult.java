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

/**
 * Detached rows returned as one direct JDBC result.
 *
 * The result is a small immutable value object with explicit accessors so its role in the direct-result sequence is
 * easy to see when reading the execution model.
 */
final class JdbcRowsResult implements JdbcDirectResult {
    private final int ordinal;
    private final RowSet rows;

    JdbcRowsResult(int ordinal, RowSet rows) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Direct-result ordinal must not be negative");
        }
        Objects.requireNonNull(rows, "Rows must not be null");
        this.ordinal = ordinal;
        this.rows = rows;
    }

    @Override
    public int ordinal() {
        return ordinal;
    }

    RowSet rows() {
        return rows;
    }
}
