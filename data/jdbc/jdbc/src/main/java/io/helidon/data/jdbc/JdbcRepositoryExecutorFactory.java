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

import javax.sql.DataSource;

import io.helidon.config.Config;
import io.helidon.data.Data;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceInstance;

/**
 * Creates JDBC repository executors from {@code data.persistence-units.jdbc}.
 * <p>
 * This is the runtime bridge between Helidon configuration, datasource services, and generated repositories.
 * The factory does not create connection pools. It resolves the named {@link DataSource} service that existing
 * {@code /data/sql} modules publish, then exposes a JDBC executor qualified with the configured persistence unit
 * name and provider type.
 */
@Service.Singleton
class JdbcRepositoryExecutorFactory implements Service.ServicesFactory<JdbcRepositoryExecutor> {

    static final String JDBC_PU_CONFIG_KEY = "data.persistence-units.jdbc";
    static final String PROVIDER_TYPE = "jdbc";

    private static final Qualifier PROVIDER_QUALIFIER = Qualifier.builder()
            .typeName(Data.ProviderType.TYPE)
            .value(PROVIDER_TYPE)
            .build();

    private final Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier;
    private final Supplier<Config> configSupplier;

    @Service.Inject
    JdbcRepositoryExecutorFactory(Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier,
                                  Supplier<Config> configSupplier) {
        this.dataSourcesSupplier = dataSourcesSupplier;
        this.configSupplier = configSupplier;
    }

    @Override
    public List<Service.QualifiedInstance<JdbcRepositoryExecutor>> services() {
        Config config = configSupplier.get().get(JDBC_PU_CONFIG_KEY);

        // Each configured JDBC persistence unit becomes one executor service instance.
        return config.asNodeList()
                .stream()
                .flatMap(List::stream)
                .map(this::mapSingleConfig)
                .toList();
    }

    private Service.QualifiedInstance<JdbcRepositoryExecutor> mapSingleConfig(Config config) {
        JdbcPersistenceUnitConfig jdbcConfig = JdbcPersistenceUnitConfig.create(config);
        DataSource dataSource = dataSource(jdbcConfig.name(), jdbcConfig);
        JdbcRepositoryExecutor executor = new JdbcRepositoryExecutorImpl(jdbcConfig.name(),
                                                                         dataSource,
                                                                         jdbcConfig.parameters());

        jdbcConfig.initScript()
                .ifPresent(script -> JdbcScriptRunner.run(dataSource, script));

        // Generated repositories can resolve the executor by persistence unit name and by provider type.
        return Service.QualifiedInstance.create(executor,
                                                Qualifier.createNamed(jdbcConfig.name()),
                                                PROVIDER_QUALIFIER);
    }

    private DataSource dataSource(String persistenceUnitName, JdbcPersistenceUnitConfig jdbcConfig) {
        String dataSourceName = jdbcConfig.dataSource()
                .orElseThrow(() -> new IllegalStateException("JDBC persistence unit \""
                                                                     + persistenceUnitName
                                                                     + "\" must configure data-source."));
        Qualifier dataSourceQualifier = Qualifier.createNamed(dataSourceName);

        return dataSourcesSupplier.get()
                .stream()
                .filter(it -> it.qualifiers().contains(dataSourceQualifier))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Configured data source for JDBC persistence unit \""
                                                                     + persistenceUnitName
                                                                     + "\" named \""
                                                                     + dataSourceName
                                                                     + "\" is not available in ServiceRegistry."))
                .get();
    }
}
