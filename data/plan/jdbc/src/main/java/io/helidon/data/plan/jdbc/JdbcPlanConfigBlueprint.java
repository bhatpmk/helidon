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

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.jdbc.JdbcResults;
import io.helidon.data.jdbc.ResultsAdvancementBehavior;
import io.helidon.data.jdbc.function.JdbcFunction;

/**
 * A prototype for an {@linkplain JdbcPlan executable plan} based on JDBC {@link java.sql.PreparedStatement}s.
 *
 * @param <T> the results type
 */
@Prototype.Blueprint
interface JdbcPlanConfigBlueprint<T> extends Prototype.Factory<JdbcPlan<T>> {

    String statement();

    Optional<ConnectionStateConfig> connectionState();

    Optional<StatementStateConfig> statementState();

    Optional<ExecutionStateConfig> executionState();

    @Option.Default("CLOSE_CURRENT_RESULT")
    ResultsAdvancementBehavior resultsAdvancementBehavior();

    JdbcFunction<? super JdbcResults, ? extends T> transformer();

}
