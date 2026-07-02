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

/**
 * One update count returned in the direct JDBC result sequence.
 *
 * The result is a small immutable value object with explicit accessors so its role in the direct-result sequence is
 * easy to see when reading the execution model.
 */
final class JdbcUpdateCountResult implements JdbcDirectResult {
    private final int ordinal;
    private final long count;

    JdbcUpdateCountResult(int ordinal, long count) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Direct-result ordinal must not be negative");
        }
        this.ordinal = ordinal;
        this.count = count;
    }

    @Override
    public int ordinal() {
        return ordinal;
    }

    long count() {
        return count;
    }
}
