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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.helidon.data.jdbc.UncheckedSQLException;
import io.helidon.transaction.spi.TxLifeCycle;

import static java.util.Objects.requireNonNull;

final class TransactionalDataSource implements DataSource, TxLifeCycle {

    // Are we in a matched start("jdbc")/end() situation? We only need this separate stack because end() does not
    // receive a type argument.
    private final ThreadLocal<Deque<Boolean>> starts;

    // Invariant: if there is an ACTIVE association, then it is always at the top of the stack. (The top of the stack
    // may be an Association that is SUSPENDED.)
    private final ThreadLocal<Deque<Association>> associations;

    private final DataSource delegate;

    TransactionalDataSource(DataSource delegate) {
        super();
        this.starts = ThreadLocal.withInitial(ArrayDeque::new);
        this.associations = ThreadLocal.withInitial(ArrayDeque::new);
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override // DataSource
    public Connection getConnection() throws SQLException {
        return this.getConnection(Association.Username.NONE);
    }

    @Override // DataSource
    public Connection getConnection(String username, String password) throws SQLException {
        return this.getConnection(new Association.Username(username), password);
    }

    private Connection getConnection(Association.Username username) throws SQLException {
        return this.getConnection(username, null);
    }

    private Connection getConnection(Association.Username username, String password) throws SQLException {
        requireNonNull(username, "username");
        Association association = this.associations.get().peek();
        if (association == null || association.status() == Association.Status.SUSPENDED) {
            // No transaction association at all, or a suspended one.
            return
                username == Association.Username.NONE
                ? this.delegate.getConnection()
                : this.delegate.getConnection(username.value(), password);
        }
        Connection c = association.connection();
        if (c == null) {
            // Transaction association exists, but no connection has yet been established.
            c =
                username == Association.Username.NONE
                ? this.delegate.getConnection()
                : this.delegate.getConnection(username.value(), password);
            association.connection(c);
            association.username(username);
        } else {
            // Transaction-bound connection already established
            Association.Username associationUsername = association.username();
            if (username == Association.Username.NONE) {
                if (associationUsername != username) {
                    // It may? be OK? to just return the delegate's connection here? but for now be strict.
                    throw new IllegalStateException("Unexpected bound connection: " + c);
                }
            } else if (!username.equals(associationUsername)) {
                // It may? be OK? to just return the delegate's connection here? but for now be strict.
                throw new IllegalStateException("Unexpected bound connection: " + c);
            }
        }
        return new TransactionScopedConnection(c);
    }

    @Override // DataSource
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return this.delegate.unwrap(iface);
    }

    @Override // DataSource
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.delegate.isWrapperFor(iface);
    }

    @Override // DataSource
    public PrintWriter getLogWriter() throws SQLException {
        return this.delegate.getLogWriter();
    }

    @Override // DataSource
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.delegate.setLogWriter(out);
    }

    @Override // DataSource
    public void setLoginTimeout(int seconds) throws SQLException {
        this.delegate.setLoginTimeout(seconds);
    }

    @Override // DataSource
    public int getLoginTimeout() throws SQLException {
        return this.delegate.getLoginTimeout();
    }

    @Override // DataSource
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return this.delegate.getParentLogger();
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

    private void complete(String txId, boolean commit) {
        requireNonNull(txId, "txId");
        if (!Boolean.TRUE.equals(this.starts.get().peek())) {
            return;
        }
        Deque<Association> dq = this.associations.get();
        Association association = dq.peek();
        if (association == null || !txId.equals(association.id())) {
            throw new IllegalStateException();
        }
        Connection c = association.connection();
        if (c == null) {
            dq.pop();
            return;
        }
        try {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
            c.setAutoCommit(true);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
        try {
            c.close();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
        dq.pop();
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

    private static final class Association {

        private final String txId;

        private Status status;

        private Username username;

        private Connection connection;

        private Association(String txId) {
            this(txId, null, null);
        }

        private Association(String txId, Connection connection) {
            this(txId, connection, null);
        }

        private Association(String txId, Connection connection, Username username) {
            super();
            this.txId = requireNonNull(txId, "txId");
            this.status = Status.ACTIVE;
            this.connection = connection;
            this.username = username;
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
