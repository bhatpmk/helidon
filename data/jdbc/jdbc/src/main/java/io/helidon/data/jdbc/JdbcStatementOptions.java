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

import java.sql.ResultSet;

record JdbcStatementOptions(int fetchSize,
                            long maxRows,
                            int queryTimeoutSeconds,
                            int resultSetType,
                            int resultSetConcurrency,
                            int resultSetHoldability) {

    private static final int UNSPECIFIED = -1;

    static final JdbcStatementOptions DEFAULT = new JdbcStatementOptions(0, 0, 0, UNSPECIFIED, UNSPECIFIED, UNSPECIFIED);

    JdbcStatementOptions {
        if (fetchSize < 0) {
            throw new IllegalArgumentException("Fetch size must not be negative");
        }
        if (maxRows < 0) {
            throw new IllegalArgumentException("Max rows must not be negative");
        }
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Query timeout must not be negative");
        }
        if (resultSetType != UNSPECIFIED) {
            validateResultSetType(resultSetType);
            validateResultSetConcurrency(resultSetConcurrency);
        } else if (resultSetConcurrency != UNSPECIFIED || resultSetHoldability != UNSPECIFIED) {
            throw new IllegalArgumentException("Result set type must be configured with concurrency and holdability");
        }
        if (resultSetHoldability != UNSPECIFIED) {
            validateResultSetHoldability(resultSetHoldability);
        }
    }

    JdbcStatementOptions withFetchSize(int fetchSize) {
        return new JdbcStatementOptions(fetchSize,
                                        maxRows,
                                        queryTimeoutSeconds,
                                        resultSetType,
                                        resultSetConcurrency,
                                        resultSetHoldability);
    }

    JdbcStatementOptions withMaxRows(long maxRows) {
        return new JdbcStatementOptions(fetchSize,
                                        maxRows,
                                        queryTimeoutSeconds,
                                        resultSetType,
                                        resultSetConcurrency,
                                        resultSetHoldability);
    }

    JdbcStatementOptions withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return new JdbcStatementOptions(fetchSize,
                                        maxRows,
                                        queryTimeoutSeconds,
                                        resultSetType,
                                        resultSetConcurrency,
                                        resultSetHoldability);
    }

    JdbcStatementOptions withResultSet(int resultSetType, int resultSetConcurrency) {
        return new JdbcStatementOptions(fetchSize,
                                        maxRows,
                                        queryTimeoutSeconds,
                                        resultSetType,
                                        resultSetConcurrency,
                                        UNSPECIFIED);
    }

    JdbcStatementOptions withResultSet(int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
        return new JdbcStatementOptions(fetchSize,
                                        maxRows,
                                        queryTimeoutSeconds,
                                        resultSetType,
                                        resultSetConcurrency,
                                        resultSetHoldability);
    }

    boolean hasResultSetShape() {
        return resultSetType != UNSPECIFIED;
    }

    boolean hasResultSetHoldability() {
        return resultSetHoldability != UNSPECIFIED;
    }

    private static void validateResultSetType(int value) {
        switch (value) {
        case ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.TYPE_SCROLL_SENSITIVE -> {
        }
        default -> throw new IllegalArgumentException("Invalid result set type: " + value);
        }
    }

    private static void validateResultSetConcurrency(int value) {
        switch (value) {
        case ResultSet.CONCUR_READ_ONLY, ResultSet.CONCUR_UPDATABLE -> {
        }
        default -> throw new IllegalArgumentException("Invalid result set concurrency: " + value);
        }
    }

    private static void validateResultSetHoldability(int value) {
        switch (value) {
        case ResultSet.HOLD_CURSORS_OVER_COMMIT, ResultSet.CLOSE_CURSORS_AT_COMMIT -> {
        }
        default -> throw new IllegalArgumentException("Invalid result set holdability: " + value);
        }
    }
}
