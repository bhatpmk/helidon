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
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import io.helidon.common.Weighted;
import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.data.DataException;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceInstance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcPersistenceUnitFactoryTest {

    @Test
    void createsDistinctNamedAndProviderQualifiedClients() throws Exception {
        JdbcDataSource contacts = dataSource("contacts", "CONTACTS");
        JdbcDataSource audit = dataSource("audit", "AUDIT");
        Config config = Config.just(ConfigSources.create(Map.of(
                "data.persistence-units.jdbc.0.name", "contacts",
                "data.persistence-units.jdbc.0.data-source", "contacts-source",
                "data.persistence-units.jdbc.1.name", "audit",
                "data.persistence-units.jdbc.1.data-source", "audit-source")));
        List<ServiceInstance<DataSource>> sources = List.of(instance("contacts-source", contacts),
                                                            instance("audit-source", audit));
        JdbcPersistenceUnitFactory factory = new JdbcPersistenceUnitFactory(() -> sources,
                                                                            () -> config,
                                                                            new JdbcTransactionConnectionManager());

        List<Service.QualifiedInstance<JdbcClient>> clients = factory.services();

        assertEquals(2, clients.size());
        JdbcClient contactsClient = namedProviderClient(clients, "contacts");
        JdbcClient auditClient = namedProviderClient(clients, "audit");
        assertEquals("CONTACTS", contactsClient.create("SELECT NAME FROM UNIT_NAME").map(String.class).one());
        assertEquals("AUDIT", auditClient.create("SELECT NAME FROM UNIT_NAME").map(String.class).one());
    }

    @Test
    void rejectsMissingAndDuplicatePersistenceUnitConfiguration() {
        Config missing = Config.just(ConfigSources.create(Map.of(
                "data.persistence-units.jdbc.0.name", "missing",
                "data.persistence-units.jdbc.0.data-source", "does-not-exist")));
        JdbcPersistenceUnitFactory missingFactory = new JdbcPersistenceUnitFactory(List::of,
                                                                                   () -> missing,
                                                                                   new JdbcTransactionConnectionManager());
        assertThrows(DataException.class, missingFactory::services);

        Config duplicate = Config.just(ConfigSources.create(Map.of(
                "data.persistence-units.jdbc.0.name", "same",
                "data.persistence-units.jdbc.0.data-source", "source",
                "data.persistence-units.jdbc.1.name", "same",
                "data.persistence-units.jdbc.1.data-source", "source")));
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:duplicate;DB_CLOSE_DELAY=-1");
        JdbcPersistenceUnitFactory duplicateFactory = new JdbcPersistenceUnitFactory(
                () -> List.of(instance("source", source)),
                () -> duplicate,
                new JdbcTransactionConnectionManager());
        assertThrows(DataException.class, duplicateFactory::services);
    }

    @Test
    void createsDefaultDirectClientOnlyAfterInitScriptSucceeds() {
        Config config = Config.just(ConfigSources.create(Map.of(
                "data.persistence-units.jdbc.0.connection.url",
                "jdbc:h2:mem:direct_unit;DB_CLOSE_DELAY=-1",
                "data.persistence-units.jdbc.0.connection.jdbc-driver-class-name",
                "org.h2.Driver",
                "data.persistence-units.jdbc.0.init-script",
                "jdbc-init.sql")));
        JdbcPersistenceUnitFactory factory = new JdbcPersistenceUnitFactory(List::of,
                                                                            () -> config,
                                                                            new JdbcTransactionConnectionManager());

        List<Service.QualifiedInstance<JdbcClient>> clients = factory.services();

        assertEquals(1, clients.size());
        JdbcClient client = namedProviderClient(clients, Service.Named.DEFAULT_NAME);
        assertEquals(2L, client.create("SELECT COUNT(*) FROM INIT_CONTACT").map(Long.class).one());
    }

    private static JdbcClient namedProviderClient(List<Service.QualifiedInstance<JdbcClient>> clients, String name) {
        Qualifier named = Qualifier.createNamed(name);
        return clients.stream()
                .filter(client -> client.qualifiers().contains(named))
                .filter(client -> client.qualifiers().size() == 2)
                .findFirst()
                .orElseThrow()
                .get();
    }

    private static JdbcDataSource dataSource(String database, String value) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE UNIT_NAME (NAME VARCHAR(20))");
            statement.execute("INSERT INTO UNIT_NAME VALUES ('" + value + "')");
        }
        return dataSource;
    }

    private static ServiceInstance<DataSource> instance(String name, DataSource dataSource) {
        return new TestServiceInstance(dataSource, Set.of(Qualifier.createNamed(name)));
    }

    private record TestServiceInstance(DataSource value,
                                       Set<Qualifier> qualifiers) implements ServiceInstance<DataSource> {
        @Override
        public DataSource get() {
            return value;
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of(ResolvedType.create(TypeName.create(DataSource.class)));
        }

        @Override
        public TypeName scope() {
            return TypeName.create(Service.Singleton.class);
        }

        @Override
        public double weight() {
            return Weighted.DEFAULT_WEIGHT;
        }

        @Override
        public TypeName serviceType() {
            return TypeName.create(JdbcPersistenceUnitFactoryTest.class);
        }
    }
}
