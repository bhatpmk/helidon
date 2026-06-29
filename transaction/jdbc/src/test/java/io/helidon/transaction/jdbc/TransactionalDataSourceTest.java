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
package io.helidon.transaction.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import io.helidon.service.registry.Services;
import io.helidon.transaction.Tx;

import org.junit.jupiter.api.Test;

import static io.helidon.transaction.Tx.Type.REQUIRED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalDataSourceTest {

    private TransactionalDataSourceTest() {
        super();
    }

    @Test
    void testGetConnectionDelegatesToConnectionResolver() throws SQLException {
        Connection expectedConnection = new ConnectionStub();
        RecordingConnectionResolver connectionResolver = new RecordingConnectionResolver(expectedConnection);
        DataSource delegate = new DataSourceStub();
        TransactionalDataSource dataSource = new TransactionalDataSource(connectionResolver, delegate);
        Connection actualConnection = dataSource.getConnection();
        assertThat(actualConnection, sameInstance(expectedConnection));
        assertThat(connectionResolver.dataSource(), sameInstance(delegate));
        assertThat(connectionResolver.username(), is((String) null));
        assertThat(connectionResolver.password(), is((String) null));
    }

    @Test
    void testGetConnectionWithUsernamePasswordUsesConnectionResolver() throws SQLException {
        Connection expectedConnection = new ConnectionStub();
        RecordingConnectionResolver connectionResolver = new RecordingConnectionResolver(expectedConnection);
        DataSource delegate = new DataSourceStub();
        TransactionalDataSource dataSource = new TransactionalDataSource(connectionResolver, delegate);
        Connection actualConnection = dataSource.getConnection("scott", "tiger");
        assertThat(actualConnection, sameInstance(expectedConnection));
        assertThat(connectionResolver.dataSource(), sameInstance(delegate));
        assertThat(connectionResolver.username(), is("scott"));
        assertThat(connectionResolver.password(), is("tiger"));
    }

    @Test
    void testLightweightIntegrationScenario() throws SQLException {
        DataSource ds = Services.get(DataSource.class);
        Tx.transaction(REQUIRED, () -> {
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

    @Test
    void testAnnotatedRequiredCommitsJdbcWork() throws SQLException {
        DataSource dataSource = Services.get(DataSource.class);
        String tableName = tableName("TX_ANNOTATED_COMMIT");
        createTable(dataSource, tableName);

        Services.get(AnnotatedJdbcService.class).insert(tableName, 1);

        assertThat(countRows(dataSource, tableName), is(1));
    }

    @Test
    void testAnnotatedRequiredRollsBackJdbcWork() throws SQLException {
        DataSource dataSource = Services.get(DataSource.class);
        String tableName = tableName("TX_ANNOTATED_ROLLBACK");
        createTable(dataSource, tableName);

        assertThrows(IllegalStateException.class,
                     () -> Services.get(AnnotatedJdbcService.class).insertAndFail(tableName, 1));

        assertThat(countRows(dataSource, tableName), is(0));
    }

    private static String tableName(String prefix) {
        return prefix + "_" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static void createTable(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + tableName + " (ID INT PRIMARY KEY)");
        }
    }

    private static int countRows(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            assertThat(resultSet.next(), is(true));
            int count = resultSet.getInt(1);
            assertThat(resultSet.next(), is(false));
            return count;
        }
    }

    private static final class RecordingConnectionResolver implements ConnectionResolver {

        private final Connection connection;

        private DataSource dataSource;

        private String username;

        private String password;

        private RecordingConnectionResolver(Connection connection) {
            super();
            this.connection = connection;
        }

        @Override
        public Connection connection(DataSource dataSource) {
            this.dataSource = dataSource;
            this.username = null;
            this.password = null;
            return this.connection;
        }

        @Override
        public Connection connection(DataSource dataSource, String username, String password) {
            this.dataSource = dataSource;
            this.username = username;
            this.password = password;
            return this.connection;
        }

        private DataSource dataSource() {
            return this.dataSource;
        }

        private String username() {
            return this.username;
        }

        private String password() {
            return this.password;
        }

    }

}
