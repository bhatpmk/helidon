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
package io.helidon.data.jdbc.plan;

import java.util.List;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.jdbc.GeneratedKeysBehavior;
import io.helidon.data.jdbc.ResultSetConcurrency;
import io.helidon.data.jdbc.ResultSetHoldability;
import io.helidon.data.jdbc.ResultSetType;
import io.helidon.data.jdbc.ResultsAdvancementBehavior;

/**
 * A descriptor for statement execution state.
 */
@Prototype.Blueprint
interface ExecutionPlanConfigBlueprint {

    /**
     * Generated keys behavior of the execution.
     *
     * @return generated keys behavior
     */
    @Option.Default("UNSPECIFIED")
    GeneratedKeysBehavior generatedKeysBehavior();

    /**
     * Generated column indexes returned by the execution.
     *
     * @return generated column indexes
     */
    List<Integer> columnIndexes();

    /**
     * Generated column names returned by the execution.
     *
     * @return generated column names
     */
    List<String> columnNames();

    /**
     * The result set type to use.
     *
     * @return the result set type to use
     */
    @Option.Default("UNSPECIFIED")
    ResultSetType resultSetType();

    /**
     * The result set concurrency to use.
     *
     * @return the result set concurrency to use
     */
    @Option.Default("READ_ONLY")
    ResultSetConcurrency resultSetConcurrency();

    /**
     * The result set holdability to use.
     *
     * @return the result set holdability to use
     */
    @Option.Default("UNSPECIFIED")
    ResultSetHoldability resultSetHoldability();

    /**
     * The results advancement behavior to use.
     *
     * @return the result set advancement behavior
     */
    @Option.Default("UNSPECIFIED")
    ResultsAdvancementBehavior resultsAdvancementBehavior();

    /**
     * Registered {@code OUT} parameter indices containin values.
     *
     * @return registered {@code OUT} parameter indices
     */
    List<Integer> outParameterIndices();

}
