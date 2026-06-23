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

import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.helidon.transaction.Tx.Type.NEW;
import static io.helidon.transaction.Tx.Type.REQUIRED;
import static io.helidon.transaction.Tx.Type.UNSUPPORTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalDataSourceFactoryTest {

    private ServiceRegistryManager serviceRegistryManager;

    private TransactionalDataSourceFactory factory;

    private TransactionalDataSourceFactoryTest() {
        super();
    }

    @BeforeEach
    void createFactory() {
        this.serviceRegistryManager = ServiceRegistryManager.create();
        this.factory = new TransactionalDataSourceFactory(this.serviceRegistryManager.registry());
    }

    @AfterEach
    void shutdownServiceRegistryManager() {
        if (this.serviceRegistryManager != null) {
            this.serviceRegistryManager.shutdown();
        }
    }

    @DisplayName("A REQUIRED transaction reuses one physical connection")
    @Test
    void requiredTransactionReusesOneConnection() throws SQLException {
        RecordingConnectionStub physicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(physicalConnection);
        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(this.factory, delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection();
            assertThat(first, instanceOf(TransactionScopedConnection.class));

            Connection second = transactionalDataSource.getConnection();
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
        RecordingConnectionStub outerPhysicalConnection = new RecordingConnectionStub();
        RecordingConnectionStub unsupportedPhysicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(outerPhysicalConnection, unsupportedPhysicalConnection);
        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(this.factory, delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> {
            Connection outerFirst = transactionalDataSource.getConnection();
            assertThat(outerFirst, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            Connection unsupported = txSupport.transaction(UNSUPPORTED, transactionalDataSource::getConnection);
            assertThat(unsupported, is(unsupportedPhysicalConnection));
            assertThat(delegate.connectionRequestCount(), is(2));

            unsupported.close();
            assertThat(unsupportedPhysicalConnection.isClosed(), is(true));
            assertThat(unsupportedPhysicalConnection.committed(), is(false));

            Connection outerSecond = transactionalDataSource.getConnection();
            assertThat(outerSecond, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(2));

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

    @DisplayName("A nested NEW transaction uses its own connection and restores the outer one")
    @Test
    void newTransactionUsesInnerConnectionAndRestoresOuterConnection() throws SQLException {
        RecordingConnectionStub outerPhysicalConnection = new RecordingConnectionStub();
        RecordingConnectionStub innerPhysicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(outerPhysicalConnection, innerPhysicalConnection);
        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(this.factory, delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

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
        RecordingConnectionStub physicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(physicalConnection);
        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(this.factory, delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection("scott", "tiger");
            assertThat(first, instanceOf(TransactionScopedConnection.class));

            Connection second = transactionalDataSource.getConnection("scott", "tiger");
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
        RecordingConnectionStub physicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(physicalConnection);
        TransactionalDataSource transactionalDataSource = new TransactionalDataSource(this.factory, delegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = transactionalDataSource.getConnection();
            assertThat(first, instanceOf(TransactionScopedConnection.class));
            assertThat(delegate.connectionRequestCount(), is(1));

            assertThrows(SQLException.class,
                         () -> transactionalDataSource.getConnection("scott", "tiger"));

            assertThat(delegate.connectionRequestCount(), is(1));

            first.close();
            return null;
        });

        assertThat(physicalConnection.committed(), is(true));
        assertThat(physicalConnection.isClosed(), is(true));
    }

    @DisplayName("A transaction with no actual DataSource use still completes")
    @Test
    void transactionWithoutDatasourceUseStillCompletes() {
        RecordingConnectionStub physicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub delegate = new RecordingDataSourceStub(physicalConnection);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> null);

        assertThat(delegate.connectionRequestCount(), is(0));
        assertThat(physicalConnection.committed(), is(false));
        assertThat(physicalConnection.rolledBack(), is(false));
        assertThat(physicalConnection.isClosed(), is(false));
    }

    @DisplayName("A transaction cannot use a second DataSource")
    @Test
    void secondDatasourceInOneTransactionFails() throws SQLException {
        RecordingConnectionStub firstPhysicalConnection = new RecordingConnectionStub();
        RecordingConnectionStub secondPhysicalConnection = new RecordingConnectionStub();
        RecordingDataSourceStub firstDelegate = new RecordingDataSourceStub(firstPhysicalConnection);
        RecordingDataSourceStub secondDelegate = new RecordingDataSourceStub(secondPhysicalConnection);
        TransactionalDataSource firstDataSource = new TransactionalDataSource(this.factory, firstDelegate);
        TransactionalDataSource secondDataSource = new TransactionalDataSource(this.factory, secondDelegate);
        JdbcTxSupport txSupport = new JdbcTxSupport(this.factory);

        txSupport.transaction(REQUIRED, () -> {
            Connection first = firstDataSource.getConnection();
            assertThat(first, instanceOf(TransactionScopedConnection.class));
            assertThat(firstDelegate.connectionRequestCount(), is(1));
            assertThat(secondDelegate.connectionRequestCount(), is(0));

            assertThrows(SQLException.class, secondDataSource::getConnection);

            assertThat(firstDelegate.connectionRequestCount(), is(1));
            assertThat(secondDelegate.connectionRequestCount(), is(0));

            first.close();
            return null;
        });

        assertThat(firstPhysicalConnection.committed(), is(true));
        assertThat(firstPhysicalConnection.isClosed(), is(true));
        assertThat(secondPhysicalConnection.committed(), is(false));
        assertThat(secondPhysicalConnection.rolledBack(), is(false));
        assertThat(secondPhysicalConnection.isClosed(), is(false));
    }
}
