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

import java.sql.SQLException;
import java.sql.Statement;

import io.helidon.common.Api;

/**
 * Statement options used by the internal JDBC execution plan.
 *
 * @param queryTimeoutSeconds query timeout in seconds, or {@code 0} for driver default
 * @param fetchSize fetch size, or {@code 0} for driver default
 * @param maxRows maximum rows, or {@code 0} for driver default
 */
@Api.Internal
public record JdbcStatementOptions(int queryTimeoutSeconds, int fetchSize, long maxRows) {

    /**
     * Default statement options.
     */
    public static final JdbcStatementOptions DEFAULT = new JdbcStatementOptions(0, 0, 0);

    /**
     * Create statement options.
     *
     * @param queryTimeoutSeconds query timeout in seconds, or {@code 0} for driver default
     * @param fetchSize fetch size, or {@code 0} for driver default
     * @param maxRows maximum rows, or {@code 0} for driver default
     */
    public JdbcStatementOptions {
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must not be negative");
        }
        if (fetchSize < 0) {
            throw new IllegalArgumentException("fetchSize must not be negative");
        }
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows must not be negative");
        }
    }

    void apply(Statement statement) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            statement.setQueryTimeout(queryTimeoutSeconds);
        }
        if (fetchSize > 0) {
            statement.setFetchSize(fetchSize);
        }
        if (maxRows > 0) {
            statement.setLargeMaxRows(maxRows);
        }
    }

}
