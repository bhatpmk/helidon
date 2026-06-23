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
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.data.sql.common.SqlConfig;
import io.helidon.service.registry.Service;

/**
 * Configuration of Helidon Data for JDBC persistence units.
 */
@Prototype.Blueprint
@Prototype.Configured(value = JdbcOperationsFactory.JDBC_PU_CONFIG_KEY)
interface JdbcPersistenceUnitConfigBlueprint extends SqlConfig {

    /**
     * Name of this persistence unit.
     *
     * @return the persistence unit name
     */
    @Option.Default(Service.Named.DEFAULT_NAME)
    String name();

    /**
     * Path to database initialization script on classpath or file system.
     *
     * @return database initialization script path
     */
    @Option.Configured
    Optional<Path> initScript();

    /**
     * Path to database cleanup script on classpath or file system.
     *
     * @return database cleanup script path
     */
    @Option.Configured
    Optional<Path> dropScript();

    /**
     * Additional persistence unit properties.
     *
     * @return additional persistence unit properties
     */
    @Option.Configured
    @Option.Singular("property")
    Map<String, String> properties();
}
