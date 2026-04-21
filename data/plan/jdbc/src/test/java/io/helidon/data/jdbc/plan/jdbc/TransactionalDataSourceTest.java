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
package io.helidon.data.plan.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.spi.TxSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.helidon.transaction.Tx.Type.REQUIRED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

final class TransactionalDataSourceTest {

    private static ServiceRegistryManager serviceRegistryManager;

    private ServiceRegistry serviceRegistry;

    private TransactionalDataSourceTest() {
        super();
    }

    @BeforeAll
    static void createServiceRegistryManager() {
        serviceRegistryManager = ServiceRegistryManager.create();
    }

    @BeforeEach
    void acquireServiceRegistry() {
        this.serviceRegistry = serviceRegistryManager.registry();
    }

    @AfterAll
    static void shutdownServiceRegistryManager() {
        serviceRegistryManager.shutdown();
    }

    @DisplayName("Proves helidon-transaction-jdbc works and properly fronts other data source suppliers")
    @Test
    void testSpike() throws SQLException {
        DataSource ds = this.serviceRegistry.get(DataSource.class);
        this.serviceRegistry.get(TxSupport.class).transaction(REQUIRED, () -> {
            try (Connection c = ds.getConnection();
                 Statement statement = c.createStatement()) {
                // autoCommit is false if we're "in" a transaction, i.e. helidon-transaction-jdbc is in effect
                assertThat(c.getAutoCommit(), is(false));
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS EXAMPLE (ID INT PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO EXAMPLE (ID) VALUES (1)");
            }
            return null;
            });
        
        try (Connection c = ds.getConnection();
             Statement statement = c.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM EXAMPLE")) {
            // autoCommit is true if we're *not* "in" a transaction
            assertThat(c.getAutoCommit(), is(true));
            assertThat(rs.next(), is(true));
            assertThat(rs.getInt(1), is(1));
            assertThat(rs.next(), is(false));
        }
    }

}
