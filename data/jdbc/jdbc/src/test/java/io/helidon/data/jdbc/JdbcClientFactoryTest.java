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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.data.Data;
import io.helidon.data.DataException;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceInstance;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcClientFactoryTest {

    @Test
    void returnsNoClientsWhenJdbcPersistenceUnitsAreNotConfigured() {
        JdbcClientFactory factory = new JdbcClientFactory(
                () -> Config.just(ConfigSources.create(Map.of())),
                List::of);

        assertThat(factory.services(), is(empty()));
    }

    @Test
    void createsNamedAndProviderQualifiedClientFromConfiguredDataSource() {
        DataSource dataSource = new NoopDataSource();
        JdbcClientFactory factory = new JdbcClientFactory(
                () -> Config.create(ConfigSources.create(Map.of(
                        "data.persistence-units.jdbc.0.name", "pokemon",
                        "data.persistence-units.jdbc.0.data-source", "pokemon"))),
                () -> List.of(new TestServiceInstance<>(dataSource,
                                                        Set.of(Qualifier.createNamed("pokemon")))));

        List<Service.QualifiedInstance<JdbcClient>> services = factory.services();

        assertThat(services, hasSize(2));
        assertThat(services.stream().map(Service.QualifiedInstance::qualifiers).toList(),
                   containsInAnyOrder(Set.of(Qualifier.createNamed("pokemon")),
                                      Set.of(Qualifier.createNamed("pokemon"),
                                             Qualifier.builder()
                                                     .typeName(Data.ProviderType.TYPE)
                                                     .value("jdbc")
                                                     .build())));
    }

    @Test
    void failsWhenConfiguredDataSourceIsMissing() {
        JdbcClientFactory factory = new JdbcClientFactory(
                () -> Config.create(ConfigSources.create(Map.of(
                        "data.persistence-units.jdbc.0.name", "pokemon",
                        "data.persistence-units.jdbc.0.data-source", "pokemon"))),
                List::of);

        assertThrows(DataException.class, factory::services);
    }

    private record TestServiceInstance<T>(T instance, Set<Qualifier> qualifiers) implements ServiceInstance<T> {

        @Override
        public T get() {
            return instance;
        }

        @Override
        public Set<ResolvedType> contracts() {
            return Set.of();
        }

        @Override
        public TypeName scope() {
            return TypeName.create(Service.Singleton.class);
        }

        @Override
        public double weight() {
            return 0;
        }

        @Override
        public TypeName serviceType() {
            return TypeName.create(instance.getClass());
        }
    }

    private static final class NoopDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
