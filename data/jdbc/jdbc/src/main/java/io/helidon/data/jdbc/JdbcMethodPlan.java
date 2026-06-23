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

import io.helidon.common.Api;

import static java.util.Objects.requireNonNull;

/**
 * Internal JDBC repository method execution plan.
 *
 * @param statements ordered statement plans
 */
@Api.Internal
public record JdbcMethodPlan(List<JdbcStatementPlan> statements) {

    /**
     * Create a method plan.
     *
     * @param statements ordered statement plans
     */
    public JdbcMethodPlan {
        requireNonNull(statements, "statements");
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("statements must not be empty");
        }
        for (JdbcStatementPlan statement : statements) {
            requireNonNull(statement, "statement");
        }
        statements = List.copyOf(statements);
    }

    /**
     * Create a method plan.
     *
     * @param statements ordered statement plans
     * @return method plan
     */
    public static JdbcMethodPlan of(List<JdbcStatementPlan> statements) {
        return new JdbcMethodPlan(statements);
    }

}
