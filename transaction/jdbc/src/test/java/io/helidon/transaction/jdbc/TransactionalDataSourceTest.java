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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.helidon.transaction.Tx.Type.NEW;
import static io.helidon.transaction.Tx.Type.REQUIRED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalDataSourceTest {

    private TransactionalDataSourceTest() {
        super();
    }

    @DisplayName("A REQUIRED transaction reuses one physical connection")
    @Test
    void requiredTransactionReusesOneConnection() throws SQLException {
        RecordingConnection physicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(physicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection();
            Connection second = transactionalDataSource.getConnection();

            assertThat(first, instanceOf(TransactionScopedConnection.class));
            assertThat(second, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));
            assertThat(physicalConnection.isClosed(), is(false));

            first.close();
            second.close();

            assertThat(physicalConnection.isClosed(), is(false));
            return null;
        });

        assertThat(delegate.connectionRequestCount(), is(1));
        assertThat(physicalConnection.committed(), is(true));
        assertThat(physicalConnection.isClosed(), is(true));
        assertThat(physicalConnection.getAutoCommit(), is(true));
    }

    @DisplayName("An UNSUPPORTED block bypasses the transaction-bound connection")
    @Test
    void unsupportedTransactionDoesNotReuseTransactionBoundConnection() throws SQLException {
        RecordingConnection outerPhysicalConnection = new RecordingConnection();
        RecordingConnection unsupportedPhysicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(outerPhysicalConnection, unsupportedPhysicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> {

            Connection outerFirst = transactionalDataSource.getConnection();
            assertThat(outerFirst, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            Connection unsupported = txSupport.transaction(io.helidon.transaction.Tx.Type.UNSUPPORTED,
                                                           transactionalDataSource::getConnection);
            assertThat(unsupported, is((Connection) unsupportedPhysicalConnection));
            assertThat(delegate.connectionRequestCount(), is(2));

            unsupported.close();
            assertThat(unsupportedPhysicalConnection.isClosed(), is(true));
            assertThat(unsupportedPhysicalConnection.committed(), is(false));

            Connection outerSecond = transactionalDataSource.getConnection();
            assertThat(outerSecond, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(2)); // note
            outerSecond.close();

            outerFirst.close();
            assertThat(outerPhysicalConnection.isClosed(), is(false));

            return null;
        });

        assertThat(delegate.connectionRequestCount(), is(2));
        assertThat(outerPhysicalConnection.committed(), is(true));
        assertThat(outerPhysicalConnection.isClosed(), is(true));
        assertThat(unsupportedPhysicalConnection.committed(), is(false));
    }

    @DisplayName("A NEW transaction uses its own connection and then restores the outer one")
    @Test
    void newTransactionUsesInnerConnectionAndRestoresOuterConnection() throws SQLException {
        RecordingConnection outerPhysicalConnection = new RecordingConnection();
        RecordingConnection innerPhysicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(outerPhysicalConnection, innerPhysicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> {
            Connection outerFirst = transactionalDataSource.getConnection();

            assertThat(outerFirst, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            txSupport.transaction(NEW, () -> {
                Connection inner = transactionalDataSource.getConnection();

                assertThat(inner, instanceOf(TransactionScopedConnection.class));
                assertThat(delegate.connectionRequestCount(), is(2));

                inner.close();
                assertThat(innerPhysicalConnection.isClosed(), is(false));
                return null;
            });

            assertThat(innerPhysicalConnection.committed(), is(true));
            assertThat(innerPhysicalConnection.isClosed(), is(true));
            assertThat(outerPhysicalConnection.isClosed(), is(false));

            Connection outerSecond = transactionalDataSource.getConnection();

            assertThat(outerSecond, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(2));

            outerFirst.close();
            outerSecond.close();
            return null;
        });

        assertThat(delegate.connectionRequestCount(), is(2));
        assertThat(outerPhysicalConnection.committed(), is(true));
        assertThat(outerPhysicalConnection.isClosed(), is(true));
    }

    @DisplayName("A credentialed transaction reuses one physical connection")
    @Test
    void credentialedConnectionIsReusedWithinTransaction() throws SQLException {
        RecordingConnection physicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(physicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection("scott", "tiger");
            Connection second = transactionalDataSource.getConnection("scott", "tiger");

            assertThat(first, instanceOf(TransactionScopedConnection.class));
            assertThat(second, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            first.close();
            second.close();

            assertThat(physicalConnection.isClosed(), is(false));
            return null;
        });

        assertThat(delegate.connectionRequestCount(), is(1));
        assertThat(physicalConnection.committed(), is(true));
        assertThat(physicalConnection.isClosed(), is(true));
    }

    @DisplayName("Mixed credential modes in one transaction fail")
    @Test
    void mixingCredentialModesInOneTransactionFails() throws SQLException {
        RecordingConnection physicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(physicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection();

            assertThat(first, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            assertThrows(IllegalStateException.class,
                         () -> transactionalDataSource.getConnection("scott", "tiger"));

            assertThat(delegate.connectionRequestCount(), is(1));

            first.close();
            return null;
        });

        assertThat(physicalConnection.committed(), is(true));
        assertThat(physicalConnection.isClosed(), is(true));
    }

    @DisplayName("A transaction with no datasource use still completes")
    @Test
    void transactionWithoutDatasourceUseStillCompletes() {
        RecordingConnection physicalConnection = new RecordingConnection();
        RecordingDataSource delegate = new RecordingDataSource(physicalConnection);

        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(transactionalDataSource);

        txSupport.transaction(REQUIRED, () -> null);

        assertThat(delegate.connectionRequestCount(), is(0));
        assertThat(physicalConnection.committed(), is(false));
        assertThat(physicalConnection.rolledBack(), is(false));
        assertThat(physicalConnection.isClosed(), is(false));
    }


    /*
     * Inner and nested classes.
     */


    private static final class RecordingDataSource extends DataSourceStub {

        private final RecordingConnection[] connections;

        private final AtomicInteger connectionRequestCount;

        private RecordingDataSource(RecordingConnection... connections) {
            super();
            this.connections = connections.clone();
            this.connectionRequestCount = new AtomicInteger();
        }

        @Override
        public Connection getConnection() throws SQLException {
            int index = this.connectionRequestCount.getAndIncrement();
            if (index >= this.connections.length) {
                throw new SQLException("No connection configured for request index " + index);
            }
            return this.connections[index];
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return this.getConnection();
        }

        int connectionRequestCount() {
            return this.connectionRequestCount.get();
        }

    }

    private static final class RecordingConnection extends ConnectionStub {

        private boolean autoCommit;

        private boolean committed;

        private boolean rolledBack;

        private boolean closed;

        private RecordingConnection() {
            super();
            this.autoCommit = true;
            this.committed = false;
            this.rolledBack = false;
            this.closed = false;
        }

        @Override
        public boolean getAutoCommit() {
            return this.autoCommit;
        }

        @Override
        public void setAutoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
        }

        @Override
        public void commit() {
            this.committed = true;
        }

        @Override
        public void rollback() {
            this.rolledBack = true;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        boolean committed() {
            return this.committed;
        }

        boolean rolledBack() {
            return this.rolledBack;
        }

    }

}
