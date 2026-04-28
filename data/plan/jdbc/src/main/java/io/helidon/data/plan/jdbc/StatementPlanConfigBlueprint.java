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
package io.helidon.data.plan.jdbc;

import java.sql.Statement;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;
import io.helidon.data.jdbc.ResultSetFetchDirection;
import io.helidon.data.jdbc.function.JdbcConsumer;

/**
 * A descriptor of a statement.
 */
@Prototype.Blueprint
interface StatementPlanConfigBlueprint {

    /**
     * The statement type.
     *
     * @return the statement type
     */
    @Option.Default("java.sql.PreparedStatement.class")
    Class<? extends Statement> type();

    /**
     * The JDBC statement text.
     *
     * @return the statement text
     */
    String statement();

    /**
     * Whether to close the statement on completion.
     *
     * @return whether to close the statement on completion
     */
    boolean closeOnCompletion();

    /**
     * The fetch direction of this statement's result sets by default.
     *
     * @return the fetch direction
     */
    @Option.Default("UNKNOWN")
    ResultSetFetchDirection fetchDirection();

    /**
     * The fetch size to use.
     *
     * @return the fetch size to use
     */
    @Option.DefaultInt(-1)
    int fetchSize();

    /**
     * The maximum number of rows to return for result-set-typed results.
     *
     * @return the maximum number of rows
     */
    @Option.DefaultLong(-1L)
    long maxRows();

    /**
     * The maximum field size to use.
     *
     * @return the maximum field size
     */
    @Option.DefaultInt(-1)
    int maxFieldSize();

    // no poolable for now

    /**
     * The query timeout, in seconds, to use.
     *
     * @return the query timeout
     */
    @Option.DefaultInt(-1)
    int queryTimeout();

    /**
     * The statement execution plan to use.
     *
     * @return the statement execution plan
     */
    Optional<ExecutionPlanConfig> executionPlan();

    /**
     * An installer (binder) of statement arguments.
     *
     * @return the arguments binder
     */
    JdbcConsumer<? super JdbcPreparedStatementBindingView> argumentsBinder();

}
