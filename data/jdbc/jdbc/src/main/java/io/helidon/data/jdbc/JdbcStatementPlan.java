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

import static java.util.Objects.requireNonNull;

/**
 * Internal JDBC statement execution plan.
 *
 * @param sql SQL statement
 * @param kind statement kind
 * @param generatedKeys generated-key request
 * @param options statement options
 */
@Api.Internal
public record JdbcStatementPlan(String sql,
                                JdbcStatementKind kind,
                                JdbcGeneratedKeys generatedKeys,
                                JdbcStatementOptions options) {

    /**
     * Create a statement plan.
     *
     * @param sql SQL statement
     * @param kind statement kind
     * @param generatedKeys generated-key request
     * @param options statement options
     */
    public JdbcStatementPlan {
        requireNonNull(sql, "sql");
        requireNonNull(kind, "kind");
        requireNonNull(generatedKeys, "generatedKeys");
        requireNonNull(options, "options");
    }

    /**
     * Create a query statement plan.
     *
     * @param sql SQL statement
     * @return statement plan
     */
    public static JdbcStatementPlan query(String sql) {
        return new JdbcStatementPlan(sql,
                                     JdbcStatementKind.QUERY,
                                     JdbcGeneratedKeys.NONE,
                                     JdbcStatementOptions.DEFAULT);
    }

    /**
     * Create an update statement plan.
     *
     * @param sql SQL statement
     * @return statement plan
     */
    public static JdbcStatementPlan update(String sql) {
        return new JdbcStatementPlan(sql,
                                     JdbcStatementKind.UPDATE,
                                     JdbcGeneratedKeys.NONE,
                                     JdbcStatementOptions.DEFAULT);
    }

    /**
     * Create an update statement plan that requests generated keys.
     *
     * @param sql SQL statement
     * @param columnNames generated-key column names
     * @return statement plan
     */
    public static JdbcStatementPlan generatedKeys(String sql, String... columnNames) {
        return new JdbcStatementPlan(sql,
                                     JdbcStatementKind.UPDATE,
                                     generatedKeys(columnNames),
                                     JdbcStatementOptions.DEFAULT);
    }

    /**
     * Create a future callable statement plan.
     *
     * @param sql SQL statement
     * @return statement plan
     */
    public static JdbcStatementPlan call(String sql) {
        return future(sql, JdbcStatementKind.CALL);
    }

    /**
     * Create a future batch statement plan.
     *
     * @param sql SQL statement
     * @return statement plan
     */
    public static JdbcStatementPlan batch(String sql) {
        return future(sql, JdbcStatementKind.BATCH);
    }

    /**
     * Create a copy of this statement plan with different statement options.
     *
     * @param options statement options
     * @return statement plan
     */
    public JdbcStatementPlan withOptions(JdbcStatementOptions options) {
        return new JdbcStatementPlan(sql, kind, generatedKeys, options);
    }

    private static JdbcStatementPlan future(String sql, JdbcStatementKind kind) {
        return new JdbcStatementPlan(sql,
                                     kind,
                                     JdbcGeneratedKeys.NONE,
                                     JdbcStatementOptions.DEFAULT);
    }

    private static JdbcGeneratedKeys generatedKeys(String... columnNames) {
        if (columnNames == null || columnNames.length == 0) {
            return JdbcGeneratedKeys.DEFAULT;
        }
        return new JdbcGeneratedKeys(columnNames);
    }

}
