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

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;
import io.helidon.data.sql.common.SqlConfig;
import io.helidon.data.sql.datasource.spi.SqlDataSource;
import io.helidon.service.registry.Service;

/**
 * Configuration for a JDBC client.
 * <p>
 * A client can use an existing application-owned data source or SQL datasource
 * descriptor, a named data source, or direct connection settings. Existing
 * instances can be supplied only through a programmatic builder. Registry
 * managed clients are configured under {@code data.clients.jdbc}.
 */
@Api.Preview
@Prototype.Blueprint(createEmptyPublic = false, decorator = JdbcClientConfigSupport.Decorator.class)
@Prototype.Configured(JdbcClientConfigFactory.CONFIG_KEY)
interface JdbcClientConfigBlueprint extends SqlConfig, Prototype.Factory<JdbcClient> {

    /**
     * Logical name of this client.
     *
     * @return client name
     */
    @Option.Configured
    @Option.Default(Service.Named.DEFAULT_NAME)
    String name();

    /**
     * Explicit transaction participation policy.
     * <p>
     * An omitted policy defaults to {@link TransactionParticipation#LOCAL}
     * for registry-managed clients and {@link TransactionParticipation#NONE}
     * for clients created directly with {@link JdbcClient#create(JdbcClientConfig)}.
     *
     * @return configured transaction participation policy
     */
    @Option.Configured
    Optional<TransactionParticipation> transactionParticipation();

    /**
     * Explicit SQL datasource capability descriptor.
     * <p>
     * This option is available only through programmatic builders. Use it for
     * a directly constructed globally enabled client so XA acquisition,
     * transaction identity, and recovery capability are explicit. The
     * application retains ownership of the datasource lifecycle.
     *
     * @return configured SQL datasource descriptor
     */
    @Option.Confidential
    Optional<SqlDataSource> sqlDataSource();

    /**
     * The maximum number of SQL marker counts retained by this client must be between zero and 4096 inclusive,
     * where zero disables retention while marker validation continues.
     * This value is owned by Helidon Data JDBC and is not passed to JDBC.
     *
     * @return parameter count cache capacity
     */
    @Option.Configured("properties.jdbc.parameter-count-cache.capacity")
    @Option.DefaultInt(256)
    int parameterCountCacheCapacity();

    /**
     * The maximum SQL string length admitted to the parameter count cache must be a positive number of UTF-16 code
     * units and its product with the cache capacity must not exceed 16,777,216 code units, but SQL longer than this
     * value remains executable and is scanned without being retained.
     * This value is owned by Helidon Data JDBC and is not passed to JDBC.
     *
     * @return maximum cacheable SQL length
     */
    @Option.Configured("properties.jdbc.parameter-count-cache.max-sql-length")
    @Option.DefaultInt(4096)
    int parameterCountCacheMaxSqlLength();
}
