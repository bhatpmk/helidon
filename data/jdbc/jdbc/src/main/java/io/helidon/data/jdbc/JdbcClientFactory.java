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

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import io.helidon.config.Config;
import io.helidon.data.Data;
import io.helidon.data.DataException;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceInstance;

@Service.Singleton
class JdbcClientFactory implements Service.ServicesFactory<JdbcClient> {

    static final String JDBC_PU_CONFIG_KEY = "data.persistence-units.jdbc";
    static final String PROVIDER_TYPE = "jdbc";

    private static final Qualifier PROVIDER_QUALIFIER = Qualifier.builder()
            .typeName(Data.ProviderType.TYPE)
            .value(PROVIDER_TYPE)
            .build();

    private final Supplier<Config> configSupplier;
    private final Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier;

    @Service.Inject
    JdbcClientFactory(Supplier<Config> configSupplier,
                      Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier) {
        this.configSupplier = configSupplier;
        this.dataSourcesSupplier = dataSourcesSupplier;
    }

    @Override
    public List<Service.QualifiedInstance<JdbcClient>> services() {
        Config config = configSupplier.get().get(JDBC_PU_CONFIG_KEY);
        if (!config.exists()) {
            return List.of();
        }
        return config.asNodeList()
                .stream()
                .flatMap(List::stream)
                .flatMap(this::mapSingleConfig)
                .toList();
    }

    private Stream<Service.QualifiedInstance<JdbcClient>> mapSingleConfig(Config config) {
        JdbcPersistenceUnitConfig jdbcConfig = JdbcPersistenceUnitConfig.create(config);
        String name = jdbcConfig.name();
        String dataSourceName = jdbcConfig.dataSource()
                .orElseThrow(() -> new DataException("JDBC persistence unit \"" + name
                                                              + "\" is missing required data-source value."));
        DataSource dataSource = dataSource(name, dataSourceName);
        JdbcClient client = new JdbcClientImpl(name, dataSource, jdbcConfig.initScript());
        Qualifier namedQualifier = Qualifier.createNamed(name);
        return Stream.of(Service.QualifiedInstance.create(client, namedQualifier),
                         Service.QualifiedInstance.create(client, namedQualifier, PROVIDER_QUALIFIER));
    }

    private DataSource dataSource(String persistenceUnitName, String dataSourceName) {
        Qualifier dataSourceQualifier = Qualifier.createNamed(dataSourceName);
        return dataSourcesSupplier.get()
                .stream()
                .filter(it -> it.qualifiers().contains(dataSourceQualifier))
                .findFirst()
                .orElseThrow(() -> new DataException("Configured data source for JDBC persistence unit \""
                                                              + persistenceUnitName
                                                              + "\" named \""
                                                              + dataSourceName
                                                              + "\" is not available in ServiceRegistry."))
                .get();
    }
}
