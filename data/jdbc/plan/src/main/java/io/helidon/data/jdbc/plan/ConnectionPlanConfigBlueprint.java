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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import javax.sql.DataSource;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.jdbc.ResultSetHoldability;
import io.helidon.data.jdbc.TransactionIsolation;

/**
 * A descriptor of how to get and configure a {@link java.sql.Connection}.
 */
@Prototype.Blueprint
interface ConnectionPlanConfigBlueprint {

    /**
     * Data source to use.
     *
     * @return data source to use
     */
    DataSource dataSource();

    /**
     * Catalog to install.
     *
     * @return catalog to install
     */
    Optional<CatalogConfig> catalog();

    /**
     * Client info to install.
     *
     * @return client info to install
     */
    Optional<Properties> clientInfo();

    /**
     * Result set holdability to install.
     *
     * @return result set holdability to install
     */
    @Option.Default("UNSPECIFIED")
    ResultSetHoldability resultSetHoldability();

    /**
     * Read-only status.
     *
     * @return read-only status
     */
    boolean readOnly();

    /**
     * Schema to install.
     *
     * @return schema to install
     */
    Optional<SchemaConfig> schema();

    /**
     * Transaction isolation to use.
     *
     * @return transaction isolation to use
     */
    Optional<TransactionIsolation> transactionIsolation();

    /**
     * Type map to use.
     *
     * @return type map to use
     */
    Optional<Map<String, Class<?>>> typeMap();

    /**
     * Statement plans for creating statements.
     *
     * @return statement plans for creating statements
     */
    @Option.Singular
    List<StatementPlanConfig> statementPlans();

}
