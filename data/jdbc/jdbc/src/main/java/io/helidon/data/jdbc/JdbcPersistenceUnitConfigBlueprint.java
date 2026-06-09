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

import java.nio.file.Path;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.sql.common.SqlConfig;
import io.helidon.service.registry.Service;

/**
 * Configuration of Helidon Data JDBC persistence units.
 * <p>
 * A JDBC persistence unit binds generated repositories to a configured datasource. The datasource itself is
 * created by existing {@code /data/sql} modules; this provider only consumes it.
 * </p>
 */
@Prototype.Blueprint
@Prototype.Configured(value = JdbcRepositoryExecutorFactory.JDBC_PU_CONFIG_KEY)
interface JdbcPersistenceUnitConfigBlueprint extends SqlConfig {

    /**
     * Name of this JDBC persistence unit.
     *
     * @return persistence unit name
     */
    @Option.Default(Service.Named.DEFAULT_NAME)
    String name();

    /**
     * Optional schema/data initialization script.
     * <p>
     * This is intentionally minimal POC support. A production provider should define idempotency,
     * ordering, and failure semantics before exposing this as a supported feature.
     *
     * @return classpath script path
     */
    @Option.Configured
    Optional<Path> initScript();
}
