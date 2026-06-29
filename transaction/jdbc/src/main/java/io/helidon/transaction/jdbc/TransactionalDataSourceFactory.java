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
import java.sql.SQLNonTransientException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javax.sql.DataSource;

import io.helidon.common.Weight;
import io.helidon.service.registry.Lookup;
import io.helidon.service.registry.Service.Inject;
import io.helidon.service.registry.Service.QualifiedInstance;
import io.helidon.service.registry.Service.ServicesFactory;
import io.helidon.service.registry.Service.Singleton;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.transaction.spi.TxLifeCycle;

import static io.helidon.common.Weighted.DEFAULT_WEIGHT;
import static java.util.Objects.requireNonNull;

@Singleton
@Weight(DEFAULT_WEIGHT + 10)
final class TransactionalDataSourceFactory implements ConnectionResolver, ServicesFactory<DataSource>, TxLifeCycle {

    private final ServiceRegistry sr;

    private final ThreadLocal<Deque<Boolean>> starts;

    private final ThreadLocal<Deque<Association>> associations;

    @Inject
    TransactionalDataSourceFactory(ServiceRegistry sr) {
        super();
        this.starts = ThreadLocal.withInitial(ArrayDeque::new);
        this.associations = ThreadLocal.withInitial(ArrayDeque::new);
        this.sr = requireNonNull(sr, "sr");
    }


    /*
     * ConnectionResolver methods.
     */


    @Override // ConnectionResolver
    public Connection connection(DataSource dataSource) throws SQLException {
        return this.connection(dataSource, Association.Username.NONE, null);
    }

    @Override // ConnectionResolver
    public Connection connection(DataSource dataSource, String username, String password) throws SQLException {
        return this.connection(dataSource, new Association.Username(username), password);
    }


    /*
     * ServicesFactory<DataSource> methods.
     */


    @Override // ServicesFactory<DataSource>
    public List<QualifiedInstance<DataSource>> services() {
        return
            this.sr.<DataSource>lookupInstances(Lookup.builder()
                                                .addContract(DataSource.class)
                                                .weight(DEFAULT_WEIGHT)
                                                .build())
            .stream()
            .map(dsi -> QualifiedInstance.<DataSource>create(new TransactionalDataSource(this, dsi.get()),
                                                             dsi.qualifiers()))
            .toList();
    }


    /*
     * TxLifeCycle (listener) methods.
     */


    @Override // TxLifeCycle
    public void start(String type) {
        // Push the fact that we started, if the TxSupport implementation was a JDBC one. This second stack would not be
        // necessary if end() were richer.
        this.starts.get().push("jdbc".equals(type));
    }

    @Override // TxLifeCycle
    public void end() {
        this.starts.get().pop();
    }

    @Override // TxLifeCycle
    public void begin(String txId) {
        requireNonNull(txId, "txId");
        if (!Boolean.TRUE.equals(this.starts.get().peek())) {
            return;
        }
        this.associations.get().push(new Association(txId));
    }

    @Override // TxLifeCycle
    public void commit(String txId) {
        this.complete(txId, true);
    }

    @Override // TxLifeCycle
    public void rollback(String txId) {
        this.complete(txId, false);
    }

    @Override // TxLifeCycle
    public void suspend(String txId) {
        requireNonNull(txId, "txId");
        if (!Boolean.TRUE.equals(this.starts.get().peek())) {
            return;
        }
        Association a = this.associations.get().peek();
        if (a == null || !txId.equals(a.id()) || a.status() != Association.Status.ACTIVE) {
            throw new IllegalStateException();
        }
        a.status(Association.Status.SUSPENDED);
    }

    @Override // TxLifeCycle
    public void resume(String txId) {
        requireNonNull(txId, "txId");
        if (!Boolean.TRUE.equals(this.starts.get().peek())) {
            return;
        }
        Association a = this.associations.get().peek();
        if (a == null || !txId.equals(a.id()) || a.status() != Association.Status.SUSPENDED) {
            throw new IllegalStateException();
        }
        a.status(Association.Status.ACTIVE);
    }


    /*
     * Private methods.
     */


    private void complete(String txId, boolean commit) {
        requireNonNull(txId, "txId");
        if (!Boolean.TRUE.equals(this.starts.get().peek())) {
            return;
        }
        Deque<Association> dq = this.associations.get();
        Association association = dq.peek();
        if (association == null || !txId.equals(association.id())) {
            throw new IllegalStateException(); // really should be an assertion error
        }
        Connection c = association.connection();
        if (c == null) {
            // No one ever called getConnection().
            dq.pop();
            return;
        }
        RuntimeException re = null;
        try {
            try {
                // Try to complete the transaction.
                if (commit) {
                    c.commit();
                } else {
                    c.rollback();
                }
            } catch (SQLException e) {
                re = new JdbcTransactionException(commit ? "committing" : "rolling back", e);
            }
            try {
                // Try to restore connection state (which was guaranteed to be true at transaction start)
                c.setAutoCommit(true);
            } catch (SQLException e) {
                if (re == null) {
                    re = new JdbcTransactionException("restoring autoCommit", e);
                } else {
                    re.addSuppressed(e);
                }
            }
            try {
                // Try to close the connection
                c.close();
            } catch (SQLException e) {
                if (re == null) {
                    re = new JdbcTransactionException("closing connection", e);
                } else {
                    re.addSuppressed(e);
                }
            }
        } finally {
            // Ensure stack integrity in absolutely all cases
            dq.pop();
        }
        if (re != null) {
            throw re;
        }
    }

    private Connection connection(DataSource dataSource, Association.Username username, String password) throws SQLException {
        requireNonNull(dataSource, "dataSource");
        requireNonNull(username, "username");
        Association association = this.associations.get().peek();
        if (association == null || association.status() == Association.Status.SUSPENDED) {
            return username == Association.Username.NONE
                ? dataSource.getConnection()
                : dataSource.getConnection(username.value(), password);
        }
        DataSource associationDataSource = association.dataSource();
        if (associationDataSource == null) {
            Connection c = username == Association.Username.NONE
                ? dataSource.getConnection()
                : dataSource.getConnection(username.value(), password);
            try {
                association.connection(c);
            } catch (SQLException e) {
                try {
                    c.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            association.dataSource(dataSource);
            association.username(username);
        } else if (associationDataSource != dataSource) {
            throw new SQLNonTransientException(new IllegalStateException("Current transaction already bound "
                                                                         + "to a different DataSource"));
        } else if (username == Association.Username.NONE) {
            if (association.username() != username) {
                throw new SQLNonTransientException(new IllegalStateException("association.username() != NONE: "
                                                                             + association.username()));
            }
        } else if (!username.equals(association.username())) {
            throw new SQLNonTransientException(new IllegalStateException("!association.username().equals(username): "
                                                                         + association.username()
                                                                         + ".equals(" + username + ")"));
        }
        return new TransactionScopedConnection(association.connection());
    }


    /*
     * Inner and nested classes.
     */


    private static final class Association {

        private final String txId;

        private Status status;

        private DataSource dataSource;

        private Connection connection;

        private Username username;

        private Association(String txId) {
            super();
            this.txId = requireNonNull(txId, "txId");
            this.status = Status.ACTIVE;
        }

        private String id() {
            return this.txId;
        }

        private Status status() {
            return this.status;
        }

        private void status(Status status) {
            this.status = requireNonNull(status, "status");
        }

        private DataSource dataSource() {
            return this.dataSource;
        }

        private void dataSource(DataSource dataSource) {
            this.dataSource = requireNonNull(dataSource, "dataSource");
        }

        private Connection connection() {
            return this.connection;
        }

        private void connection(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Unknown connection state; initial autoCommit was false");
            }
            connection.setAutoCommit(false);
            this.connection = connection;
        }

        private Username username() {
            return this.username;
        }

        private void username(Username username) {
            this.username = requireNonNull(username, "username");
        }


        private enum Status {
            ACTIVE, SUSPENDED;
        }

        private record Username(String value) {
            private static final Username NONE = new Username(null);
        }

    }

}
