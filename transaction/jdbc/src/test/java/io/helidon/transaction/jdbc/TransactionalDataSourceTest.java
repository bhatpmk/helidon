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
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

class TransactionalDataSourceTest {

    private TransactionalDataSourceTest() {
        super();
    }

    @Test
    void getConnectionDelegatesToConnectionResolver() throws SQLException {
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
    void credentialedGetConnectionDelegatesToConnectionResolver() throws SQLException {
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
