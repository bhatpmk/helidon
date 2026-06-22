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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;

final class JdbcStatementExecutor {

    PreparedStatement prepare(Connection connection, JdbcStatementPlan plan) throws SQLException {
        requireNonNull(connection, "connection");
        requireNonNull(plan, "plan");
        PreparedStatement statement = prepareStatement(connection, plan);
        plan.options().apply(statement);
        return statement;
    }

    void bind(PreparedStatement statement, JdbcBinder binder) throws SQLException {
        requireNonNull(statement, "statement");
        requireNonNull(binder, "binder");
        binder.bind(new JdbcPreparedStatementBindingViewImpl(statement));
    }

    private PreparedStatement prepareStatement(Connection connection, JdbcStatementPlan plan) throws SQLException {
        JdbcGeneratedKeys generatedKeys = plan.generatedKeys();
        if (!generatedKeys.requested()) {
            return connection.prepareStatement(plan.sql());
        }
        if (generatedKeys.defaultColumns()) {
            return connection.prepareStatement(plan.sql(), Statement.RETURN_GENERATED_KEYS);
        }
        return connection.prepareStatement(plan.sql(), generatedKeys.columnNames());
    }

}
