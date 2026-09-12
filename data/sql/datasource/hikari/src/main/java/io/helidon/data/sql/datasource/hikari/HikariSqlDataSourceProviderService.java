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
package io.helidon.data.sql.datasource.hikari;

import java.util.List;

import io.helidon.data.sql.datasource.spi.SqlDataSource;
import io.helidon.service.registry.Service;

/**
 * Publishes explicit local-only SQL capabilities for Hikari data sources.
 */
@Service.Singleton
@Service.Named(Service.Named.WILDCARD_NAME)
final class HikariSqlDataSourceProviderService implements Service.ServicesFactory<SqlDataSource> {
    private final HikariDataSources dataSources;

    @Service.Inject
    HikariSqlDataSourceProviderService(HikariDataSources dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public List<Service.QualifiedInstance<SqlDataSource>> services() {
        return dataSources.descriptors();
    }
}
