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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.helidon.data.sql.datasource.DataSourceConfig;
import io.helidon.data.sql.datasource.spi.SqlDataSource;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;

/**
 * Creates each configured Hikari data source once and exposes its registry
 * service views.
 */
@Service.Singleton
final class HikariDataSources {
    private final List<Service.QualifiedInstance<SqlDataSource>> descriptors;

    @Service.Inject
    HikariDataSources(Supplier<List<DataSourceConfig>> configurations) {
        List<Service.QualifiedInstance<SqlDataSource>> created = new ArrayList<>();
        for (DataSourceConfig config : configurations.get()) {
            if (config.provider() instanceof HikariDataSourceConfig hikariConfig) {
                DataSource dataSource = HikariDataSourceFactory.create(hikariConfig);
                SqlDataSource descriptor = SqlDataSource.create(dataSource, new Object());
                created.add(Service.QualifiedInstance.create(descriptor, Qualifier.createNamed(config.name())));
            }
        }
        descriptors = List.copyOf(created);
    }

    List<Service.QualifiedInstance<SqlDataSource>> descriptors() {
        return descriptors;
    }
}
