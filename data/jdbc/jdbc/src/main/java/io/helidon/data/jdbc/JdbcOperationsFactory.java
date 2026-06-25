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
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.helidon.config.Config;
import io.helidon.data.Data;
import io.helidon.data.DataException;
import io.helidon.data.sql.common.ConnectionConfig;
import io.helidon.data.sql.common.SqlDriver;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceInstance;

import static java.util.Objects.requireNonNull;

@Service.Singleton
@Service.Named(Service.Named.WILDCARD_NAME)
class JdbcOperationsFactory implements Service.ServicesFactory<JdbcOperations> {

    static final String JDBC_PU_CONFIG_KEY = "data.persistence-units.jdbc";

    private static final String PROVIDER_TYPE = "jdbc";
    private static final Qualifier PROVIDER_QUALIFIER = Qualifier.builder()
            .typeName(Data.ProviderType.TYPE)
            .value(PROVIDER_TYPE)
            .build();

    private final Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier;
    private final Supplier<Config> configSupplier;

    @Service.Inject
    JdbcOperationsFactory(Supplier<List<ServiceInstance<DataSource>>> dataSourcesSupplier,
                          Supplier<Config> configSupplier) {
        this.dataSourcesSupplier = requireNonNull(dataSourcesSupplier, "dataSourcesSupplier");
        this.configSupplier = requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public List<Service.QualifiedInstance<JdbcOperations>> services() {
        Config config = configSupplier.get()
                .get(JDBC_PU_CONFIG_KEY);

        List<Config> units = config.asNodeList()
                .stream()
                .flatMap(List::stream)
                .toList();
        List<Service.QualifiedInstance<JdbcOperations>> result = new ArrayList<>();
        units.forEach(unitConfig -> result.addAll(create(unitConfig, units.size() == 1)));
        return List.copyOf(result);
    }

    private List<Service.QualifiedInstance<JdbcOperations>> create(Config config, boolean singlePersistenceUnit) {
        JdbcPersistenceUnitConfig jdbcConfig = JdbcPersistenceUnitConfig.builder()
                .name(config.get("name").asString().orElse(Service.Named.DEFAULT_NAME))
                .config(config)
                .build();
        DataSource dataSource = dataSource(jdbcConfig);
        String name = jdbcConfig.name();

        jdbcConfig.dropScript()
                .ifPresent(script -> JdbcScriptRunner.run(dataSource, script));
        jdbcConfig.initScript()
                .ifPresent(script -> JdbcScriptRunner.run(dataSource, script));

        return qualified(name, JdbcOperations.create(dataSource),
                         singlePersistenceUnit || Service.Named.DEFAULT_NAME.equals(name));
    }

    private DataSource dataSource(JdbcPersistenceUnitConfig config) {
        return config.dataSource()
                .map(name -> dataSource(config.name(), name))
                .orElseGet(() -> dataSource(config.connection()
                                                    .orElseThrow(() -> new DataException("JDBC persistence unit \""
                                                                                                 + config.name()
                                                                                                 + "\" is missing "
                                                                                                 + "connection "
                                                                                                 + "configuration.")),
                                            config.properties()));
    }

    private DataSource dataSource(String persistenceUnitName, String dataSourceName) {
        Qualifier dataSourceQualifier = Qualifier.createNamed(dataSourceName);

        return dataSourcesSupplier.get()
                .stream()
                .filter(it -> it.qualifiers().contains(dataSourceQualifier))
                .findFirst()
                .orElseThrow(() -> new DataException("Configured data source for JDBC persistence unit \""
                                                             + persistenceUnitName + "\" named \""
                                                             + dataSourceName
                                                             + "\" is not available in ServiceRegistry."))
                .get();
    }

    private static DataSource dataSource(ConnectionConfig config, Map<String, String> properties) {
        return new ConnectionConfigDataSource(config, properties);
    }

    private static List<Service.QualifiedInstance<JdbcOperations>> qualified(String name,
                                                                             JdbcOperations jdbc,
                                                                             boolean defaultAlias) {
        Qualifier namedQualifier = Qualifier.createNamed(name);
        List<Service.QualifiedInstance<JdbcOperations>> instances = new ArrayList<>();
        if (defaultAlias) {
            instances.add(Service.QualifiedInstance.create(jdbc));
        }
        instances.add(Service.QualifiedInstance.create(jdbc, namedQualifier));
        instances.add(Service.QualifiedInstance.create(jdbc, Set.of(namedQualifier, PROVIDER_QUALIFIER)));
        return List.copyOf(instances);
    }

    private static final class ConnectionConfigDataSource implements DataSource {
        private final Driver driver;
        private final String url;
        private final Properties properties;
        private PrintWriter logWriter;
        private int loginTimeout;

        private ConnectionConfigDataSource(ConnectionConfig config, Map<String, String> properties) {
            SqlDriver sqlDriver = SqlDriver.create(config);
            this.driver = sqlDriver.driver();
            this.url = config.url();
            this.properties = new Properties();
            this.properties.putAll(properties);
            config.username().ifPresent(username -> this.properties.put("user", username));
            config.password().ifPresent(password -> this.properties.put("password", new String(password)));
        }

        @Override
        public Connection getConnection() throws SQLException {
            return getConnection(properties);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Properties properties = new Properties(this.properties);
            if (username == null) {
                properties.remove("user");
            } else {
                properties.put("user", username);
            }
            if (password == null) {
                properties.remove("password");
            } else {
                properties.put("password", password);
            }
            return getConnection(properties);
        }

        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            this.logWriter = out;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            this.loginTimeout = seconds;
        }

        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("Parent logger is not available.");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            if (iface.isInstance(driver)) {
                return iface.cast(driver);
            }
            throw new SQLException("Cannot unwrap to " + iface.getName()
                                           + ", current driver class: " + driver.getClass().getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this) || iface.isInstance(driver);
        }

        private Connection getConnection(Properties properties) throws SQLException {
            Connection connection = driver.connect(url, properties);
            if (connection == null) {
                throw new SQLException("JDBC driver " + driver.getClass().getName()
                                               + " did not accept URL " + url);
            }
            return connection;
        }
    }
}
