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

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Logical ownership of a connection for one JDBC terminal operation.
 *
 * When the terminal operation finishes, should closing this operation also close the physical connection?
 *
 * There are two cases.
 *
 *   Outside a transaction:
 *
 *   DataSource.getConnection()
 *           → Owned JdbcConnectionLease
 *           → terminal completes
 *           → ResultSet closes
 *           → PreparedStatement closes
 *           → lease closes the physical connection
 *
 *   For a pooled datasource, closing the physical connection normally returns it to the pool.
 *
 *   Inside a local JDBC transaction:
 *
 *   transaction manager acquires the physical connection
 *           → runner receives a TransactionLease
 *           → terminal closes ResultSet and PreparedStatement
 *           → TransactionLease.close() only ends the logical operation lease
 *           → transaction completion commits or rolls back
 *           → transaction manager closes the physical connection
 *
 *   The lease therefore separates:
 *
 *   - logical ownership for one JDBC operation; and
 *   - physical connection ownership for the transaction lifecycle.
 *
 */
interface JdbcConnectionLease extends AutoCloseable {

    Connection connection();

    @Override
    void close() throws SQLException;

    @FunctionalInterface
    interface Provider {
        JdbcConnectionLease acquire(DataSource dataSource) throws SQLException;
    }

    static Provider ownedProvider() {
        return dataSource -> new Owned(dataSource.getConnection());
    }

    final class Owned implements JdbcConnectionLease {
        private final Connection connection;
        private boolean closed;

        Owned(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connection() {
            if (closed) {
                throw new IllegalStateException("Connection lease is closed");
            }
            return connection;
        }

        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                connection.close();
            }
        }
    }
}
