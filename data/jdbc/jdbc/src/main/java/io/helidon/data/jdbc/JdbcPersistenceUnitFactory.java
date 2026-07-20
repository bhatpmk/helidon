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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

/**
 * Creates qualified JDBC clients from configured persistence units.
 */
@Service.Singleton
final class JdbcPersistenceUnitFactory implements Service.ServicesFactory<JdbcClient> {
    static final String CONFIG_KEY = "data.persistence-units.jdbc";
    private static final String PROVIDER = "jdbc";
    private static final Qualifier PROVIDER_QUALIFIER = Qualifier.builder()
            .typeName(Data.ProviderType.TYPE)
            .value(PROVIDER)
            .build();

    private final Supplier<List<ServiceInstance<DataSource>>> dataSources;
    private final Supplier<Config> config;
    private final JdbcTransactionConnectionManager connectionManager;

    @Service.Inject
    JdbcPersistenceUnitFactory(Supplier<List<ServiceInstance<DataSource>>> dataSources,
                               Supplier<Config> config,
                               JdbcTransactionConnectionManager connectionManager) {
        this.dataSources = Objects.requireNonNull(dataSources, "Datasource supplier must not be null");
        this.config = Objects.requireNonNull(config, "Config supplier must not be null");
        this.connectionManager = Objects.requireNonNull(connectionManager, "Connection manager must not be null");
    }

    @Override
    public List<Service.QualifiedInstance<JdbcClient>> services() {
        List<Config> units = config.get().get(CONFIG_KEY).asNodeList().orElse(List.of());
        List<Service.QualifiedInstance<JdbcClient>> result = new ArrayList<>(units.size());
        Set<String> names = new HashSet<>();
        for (Config unitConfig : units) {
            JdbcPersistenceUnitConfig unit = JdbcPersistenceUnitConfig.create(unitConfig);
            if (unit.name().isBlank()) {
                throw new DataException("JDBC persistence-unit name must not be blank");
            }
            if (!names.add(unit.name())) {
                throw new DataException("Duplicate JDBC persistence-unit name: " + unit.name());
            }
            JdbcClient client = createClient(unit);
            Qualifier named = Qualifier.createNamed(unit.name());
            // A single view with both qualifiers avoids duplicate candidates for named lookups.
            result.add(Service.QualifiedInstance.create(client, named, PROVIDER_QUALIFIER));
        }
        return List.copyOf(result);
    }

    private JdbcClient createClient(JdbcPersistenceUnitConfig unit) {
        DataSource dataSource = unit.dataSource()
                .map(this::namedDataSource)
                .orElseGet(() -> directDataSource(unit.connection()
                                                          .orElseThrow(() -> new DataException(
                                                                  "JDBC persistence unit '" + unit.name()
                                                                          + "' has neither data-source nor connection"))));
        unit.initScript().ifPresent(script -> {
            // Initialization deliberately bypasses an ambient application transaction and owns one setup connection.
            JdbcRunner setupRunner = new JdbcRunner(dataSource, JdbcConnectionLease.ownedProvider());
            new JdbcInitScriptRunner(setupRunner).run(script);
        });
        return new JdbcClientImpl(dataSource, connectionManager);
    }

    private DataSource namedDataSource(String name) {
        Qualifier named = Qualifier.createNamed(name);
        List<ServiceInstance<DataSource>> matches = dataSources.get()
                .stream()
                .filter(instance -> instance.qualifiers().contains(named))
                .toList();
        if (matches.isEmpty()) {
            throw new DataException("No SQL datasource service is named '" + name + "'");
        }
        if (matches.size() > 1) {
            throw new DataException("Multiple SQL datasource services are named '" + name + "'");
        }
        return matches.getFirst().get();
    }

    private static DataSource directDataSource(ConnectionConfig config) {
        return new DirectDataSource(config, SqlDriver.create(config).driver());
    }

    /**
     * Minimal DataSource adaptation for the existing direct SQL connection configuration.
     */
    private static final class DirectDataSource implements DataSource, JdbcTransactionConnectionManager.IdentitySource {
        private final String url;
        private final Driver driver;
        private final Properties defaults;
        private final DirectIdentity transactionIdentity;
        private volatile PrintWriter logWriter;
        private volatile int loginTimeout;

        private DirectDataSource(ConnectionConfig config, Driver driver) {
            this.url = config.url();
            this.driver = driver;
            this.defaults = new Properties();
            String username = config.username().orElse(null);
            char[] passwordChars = config.password().map(value -> value.clone()).orElse(null);
            String password = passwordChars == null ? null : new String(passwordChars);
            if (username != null) {
                defaults.setProperty("user", username);
            }
            if (password != null) {
                defaults.setProperty("password", password);
            }
            this.transactionIdentity = new DirectIdentity(url,
                                                          driver.getClass().getName(),
                                                          username,
                                                          passwordChars);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connect(defaults);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Properties properties = new Properties(defaults);
            if (username == null) {
                properties.remove("user");
            } else {
                properties.setProperty("user", username);
            }
            if (password == null) {
                properties.remove("password");
            } else {
                properties.setProperty("password", password);
            }
            return connect(properties);
        }

        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            logWriter = out;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            if (seconds < 0) {
                throw new IllegalArgumentException("Login timeout must not be negative");
            }
            loginTimeout = seconds;
        }

        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            Objects.requireNonNull(iface, "Unwrap type must not be null");
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            if (iface.isInstance(driver)) {
                return iface.cast(driver);
            }
            throw new SQLException("Direct JDBC datasource cannot unwrap to " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this) || iface.isInstance(driver);
        }

        @Override
        public DirectIdentity transactionIdentity() {
            return transactionIdentity;
        }

        private Connection connect(Properties properties) throws SQLException {
            Connection connection = driver.connect(url, properties);
            if (connection == null) {
                throw new SQLException("Configured JDBC driver does not accept URL: " + url);
            }
            return connection;
        }
    }

    private static final class DirectIdentity implements JdbcTransactionConnectionManager.StableIdentity {
        private final String url;
        private final String driverClass;
        private final String username;
        private final char[] password;

        private DirectIdentity(String url, String driverClass, String username, char[] password) {
            this.url = url;
            this.driverClass = driverClass;
            this.username = username;
            this.password = password == null ? null : password.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof DirectIdentity that
                    && url.equals(that.url)
                    && driverClass.equals(that.driverClass)
                    && Objects.equals(username, that.username)
                    && Arrays.equals(password, that.password);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(url, driverClass, username);
            return 31 * result + Arrays.hashCode(password);
        }
    }
}
