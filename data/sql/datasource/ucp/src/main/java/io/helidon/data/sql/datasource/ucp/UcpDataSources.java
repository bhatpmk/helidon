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
package io.helidon.data.sql.datasource.ucp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;
import javax.sql.XADataSource;

import io.helidon.data.DataException;
import io.helidon.data.sql.datasource.DataSourceConfig;
import io.helidon.data.sql.datasource.spi.SqlDataSource;
import io.helidon.data.sql.datasource.spi.XaDataSourceCapability;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;

/**
 * Creates each configured UCP pool once and exposes both of its registry
 * service views.
 */
@Service.Singleton
final class UcpDataSources {
    private final List<Service.QualifiedInstance<SqlDataSource>> descriptors;

    @Service.Inject
    UcpDataSources(Supplier<List<DataSourceConfig>> configurations) {
        List<Service.QualifiedInstance<SqlDataSource>> created = new ArrayList<>();
        Set<String> recoveryNames = new HashSet<>();
        for (DataSourceConfig config : configurations.get()) {
            if (!(config.provider() instanceof UcpDataSourceConfig ucpConfig)) {
                continue;
            }

            String name = config.name();
            DataSource dataSource = UcpDataSourceFactory.create(ucpConfig);
            SqlDataSource descriptor;
            if (ucpConfig.xaDataSource().orElse(false)) {
                String recoveryName = ucpConfig.recoveryName().orElse(name);
                if (recoveryName.isBlank()) {
                    throw new DataException("The UCP XA recovery name for SQL data source '" + name + "' must not be blank.");
                }
                if (!recoveryNames.add(recoveryName)) {
                    throw new DataException("The UCP XA recovery name '" + recoveryName + "' is configured more than once.");
                }
                // PoolXADataSource implements both DataSource and XADataSource; keep the one pool instance in both views.
                XaDataSourceCapability xa = XaDataSourceCapability.create((XADataSource) dataSource, recoveryName);
                descriptor = SqlDataSource.create(dataSource, new Object(), xa);
            } else {
                if (ucpConfig.recoveryName().isPresent()) {
                    throw new DataException("SQL data source '" + name
                                                    + "' configures an XA recovery name without enabling xa-data-source.");
                }
                descriptor = SqlDataSource.create(dataSource, new Object());
            }
            created.add(Service.QualifiedInstance.create(descriptor, Qualifier.createNamed(name)));
        }
        descriptors = List.copyOf(created);
    }

    List<Service.QualifiedInstance<SqlDataSource>> descriptors() {
        return descriptors;
    }
}
