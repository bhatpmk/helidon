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

import io.helidon.common.Api;

/**
 * JDBC statement kind.
 */
@Api.Internal
public enum JdbcStatementKind {

    /**
     * Query statement.
     */
    QUERY(false),

    /**
     * Update statement.
     */
    UPDATE(false),

    /**
     * Stored procedure or function call.
     */
    CALL(true),

    /**
     * Batch statement.
     */
    BATCH(true);

    private final boolean future;

    JdbcStatementKind(boolean future) {
        this.future = future;
    }

    /**
     * Whether this statement kind is reserved for future implementation.
     *
     * @return {@code true} when this kind is reserved for future implementation
     */
    public boolean future() {
        return future;
    }

}
