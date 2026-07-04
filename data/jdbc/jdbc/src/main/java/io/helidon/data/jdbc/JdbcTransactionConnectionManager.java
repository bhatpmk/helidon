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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.service.registry.Service;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;

/**
 * Binds a lazily acquired datasource connection to JDBC transaction lifecycle events.
 */
@Service.Singleton
final class JdbcTransactionConnectionManager implements TxLifeCycle, JdbcConnectionLease.Provider {
    private static final String JDBC = "jdbc";

    private final ThreadLocal<State> local = ThreadLocal.withInitial(State::new);

    @Override
    public JdbcConnectionLease acquire(DataSource dataSource) throws SQLException {
        State state = local.get();
        if (state.activeForeign != null) {
            throw new DataException("A local JDBC connection cannot join active transaction type '"
                                            + state.foreignTransactions.get(state.activeForeign) + "'");
        }
        if (state.activeJdbc == null) {
            return new JdbcConnectionLease.Owned(dataSource.getConnection());
        }

        Association association = state.jdbcTransactions.get(state.activeJdbc);
        if (association == null) {
            throw new IllegalStateException("Active JDBC transaction has no lifecycle association");
        }
        Object identity = transactionIdentity(dataSource);
        if (association.dataSourceIdentity != null && !sameIdentity(association.dataSourceIdentity, identity)) {
            throw new DataException("One local JDBC transaction cannot use more than one datasource identity");
        }
        if (association.connection == null) {
            Connection connection = dataSource.getConnection();
            try {
                if (!connection.getAutoCommit()) {
                    throw new SQLException("A datasource used for local JDBC transactions must supply auto-commit connections");
                }
                association.originalReadOnly = connection.isReadOnly();
                association.originalIsolation = connection.getTransactionIsolation();
                connection.setAutoCommit(false);
                association.dataSourceIdentity = identity;
                association.connection = connection;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
        return new TransactionLease(association.connection);
    }

    @Override
    public void start(String type) {
        local.get().invocationTypes.push(type);
    }

    @Override
    public void end() {
        State state = local.get();
        if (state.invocationTypes.isEmpty()) {
            throw new IllegalStateException("Transaction lifecycle end has no matching start");
        }
        state.invocationTypes.pop();
        removeIfEmpty(state);
    }

    @Override
    public void begin(String txIdentity) {
        State state = local.get();
        String type = currentType(state);
        if (JDBC.equals(type)) {
            if (state.jdbcTransactions.putIfAbsent(txIdentity, new Association()) != null) {
                throw new IllegalStateException("Duplicate JDBC transaction identity: " + txIdentity);
            }
            state.activeJdbc = txIdentity;
        } else {
            if (state.foreignTransactions.putIfAbsent(txIdentity, type) != null) {
                throw new IllegalStateException("Duplicate foreign transaction identity: " + txIdentity);
            }
            state.activeForeign = txIdentity;
        }
    }

    @Override
    public void commit(String txIdentity) {
        complete(txIdentity, true);
    }

    @Override
    public void rollback(String txIdentity) {
        complete(txIdentity, false);
    }

    @Override
    public void suspend(String txIdentity) {
        State state = local.get();
        if (txIdentity.equals(state.activeJdbc)) {
            state.activeJdbc = null;
        } else if (txIdentity.equals(state.activeForeign)) {
            state.activeForeign = null;
        } else {
            throw new IllegalStateException("Cannot suspend inactive transaction identity: " + txIdentity);
        }
    }

    @Override
    public void resume(String txIdentity) {
        State state = local.get();
        if (state.jdbcTransactions.containsKey(txIdentity)) {
            state.activeJdbc = txIdentity;
        } else if (state.foreignTransactions.containsKey(txIdentity)) {
            state.activeForeign = txIdentity;
        } else {
            throw new IllegalStateException("Cannot resume unknown transaction identity: " + txIdentity);
        }
    }

    private void complete(String txIdentity, boolean commit) {
        State state = local.get();
        Association association = state.jdbcTransactions.remove(txIdentity);
        if (association == null) {
            String foreignType = state.foreignTransactions.remove(txIdentity);
            if (foreignType == null) {
                throw new IllegalStateException("Cannot complete unknown transaction identity: " + txIdentity);
            }
            if (txIdentity.equals(state.activeForeign)) {
                state.activeForeign = null;
            }
            removeIfEmpty(state);
            return;
        }
        if (txIdentity.equals(state.activeJdbc)) {
            state.activeJdbc = null;
        }
        try {
            completeConnection(association, commit);
        } finally {
            removeIfEmpty(state);
        }
    }

    private static void completeConnection(Association association, boolean commit) {
        Connection connection = association.connection;
        if (connection == null) {
            return;
        }

        Throwable failure = null;
        try {
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
        } catch (SQLException completionFailure) {
            failure = completionFailure;
            if (commit) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
        }

        // Restore pool-visible state even after completion fails, and always return/close the physical connection.
        try {
            if (connection.isReadOnly() != association.originalReadOnly) {
                connection.setReadOnly(association.originalReadOnly);
            }
        } catch (SQLException restoreFailure) {
            failure = merge(failure, restoreFailure);
        }
        try {
            if (connection.getTransactionIsolation() != association.originalIsolation) {
                connection.setTransactionIsolation(association.originalIsolation);
            }
        } catch (SQLException restoreFailure) {
            failure = merge(failure, restoreFailure);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
            failure = merge(failure, restoreFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure = merge(failure, closeFailure);
        }
        if (failure != null) {
            throw new TxException(commit
                                          ? "Local JDBC transaction commit failed"
                                          : "Local JDBC transaction rollback failed",
                                  failure);
        }
    }

    private static Throwable merge(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    private static Object transactionIdentity(DataSource dataSource) {
        return dataSource instanceof IdentitySource source ? source.transactionIdentity() : dataSource;
    }

    private static boolean sameIdentity(Object first, Object second) {
        if (first instanceof StableIdentity || second instanceof StableIdentity) {
            return Objects.equals(first, second);
        }
        return first == second;
    }

    private static String currentType(State state) {
        if (state.invocationTypes.isEmpty()) {
            throw new IllegalStateException("Transaction begin has no active transaction support");
        }
        return state.invocationTypes.peek();
    }

    private void removeIfEmpty(State state) {
        if (state.invocationTypes.isEmpty()
                && state.jdbcTransactions.isEmpty()
                && state.foreignTransactions.isEmpty()) {
            local.remove();
        }
    }

    private static final class State {
        private final ArrayDeque<String> invocationTypes = new ArrayDeque<>();
        private final Map<String, Association> jdbcTransactions = new HashMap<>();
        private final Map<String, String> foreignTransactions = new HashMap<>();
        private String activeJdbc;
        private String activeForeign;
    }

    private static final class Association {
        private Object dataSourceIdentity;
        private Connection connection;
        private boolean originalReadOnly;
        private int originalIsolation;
    }

    /**
     * Implemented only by internal datasource adapters whose configuration defines a stable transaction identity.
     */
    interface IdentitySource {
        StableIdentity transactionIdentity();
    }

    /**
     * Marker for immutable value identities; ordinary pooled datasources continue to use object identity.
     */
    interface StableIdentity {
    }

    /**
     * Logical operation lease. Its close is intentionally a no-op; transaction completion owns the physical connection.
     */
    private static final class TransactionLease implements JdbcConnectionLease {
        private final Connection connection;
        private boolean closed;

        private TransactionLease(Connection connection) {
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
        public void close() {
            closed = true;
        }
    }
}
