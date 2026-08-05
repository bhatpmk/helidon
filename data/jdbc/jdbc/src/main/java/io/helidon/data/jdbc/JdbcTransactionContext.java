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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.service.registry.Service;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;

/**
 * Thread-local transaction context for JDBC repository calls.
 * <p>
 * The connection lifecycle is adapted from DbClient JDBC transaction handling: a transaction connection is created
 * lazily on first use, auto-commit is disabled, and the connection is committed or rolled back when the Helidon
 * transaction lifecycle completes.
 */
final class JdbcTransactionContext {

    private static final LocalContext INSTANCE = new LocalContext();

    private final Context context;
    private final LocalTransactionStorage localStorage;

    private JdbcTransactionContext() {
        this.context = new Context();
        this.localStorage = new LocalTransactionStorage();
    }

    static JdbcTransactionContext getInstance() {
        return INSTANCE.get();
    }

    JdbcConnectionHandle connection(DataSource dataSource) throws SQLException {
        if (!context.isTxMethodRunning()) {
            return JdbcConnectionHandle.standalone(dataSource.getConnection());
        }
        if (context.txType() == TransactionType.JTA && context.txDepth() > 0) {
            throw new DataException("Helidon Data JDBC does not support JTA transaction enlistment for plain JDBC "
                                            + "connections. Use resource-local JDBC transactions.");
        }
        if (context.txDepth() == 0 || localStorage.suspended()) {
            return JdbcConnectionHandle.standalone(dataSource.getConnection());
        }
        return JdbcConnectionHandle.transactional(localStorage.connection(dataSource));
    }

    boolean isTransactionActive() {
        return context.isTxMethodRunning() && context.txDepth() > 0 && !localStorage.suspended();
    }

    String currentTransactionIdentity() {
        return localStorage.currentIdentity();
    }

    void rollbackOnly() {
        if (context.isTxMethodRunning() && context.txDepth() > 0 && context.txType() == TransactionType.RESOURCE_LOCAL) {
            localStorage.rollbackOnly();
        }
    }

    private enum TransactionType {
        JTA,
        RESOURCE_LOCAL
    }

    private static final class Context {

        private int txMethodsDepth;
        private int txDepth;
        private final List<Integer> initialTxDepth;
        private TransactionType txType;

        private Context() {
            this.txMethodsDepth = 0;
            this.txDepth = 0;
            this.initialTxDepth = new ArrayList<>();
            this.txType = null;
        }

        int txMethodsDepth() {
            return txMethodsDepth;
        }

        int txDepth() {
            return txDepth;
        }

        boolean isTxMethodRunning() {
            return txMethodsDepth > 0;
        }

        TransactionType txType() {
            if (!isTxMethodRunning()) {
                throw new IllegalStateException("Transaction type requested outside transaction method execution");
            }
            return txType;
        }

        void start(String type) {
            TransactionType requestedType = "jta".equalsIgnoreCase(type)
                    ? TransactionType.JTA
                    : TransactionType.RESOURCE_LOCAL;
            if (txMethodsDepth == 0) {
                txType = requestedType;
            } else if (txType != requestedType) {
                throw new IllegalStateException("Transaction type changed while a transaction method is executing");
            }
            txMethodsDepth++;
            initialTxDepth.addLast(txDepth);
        }

        void end() {
            txMethodsDepth--;
            if (txMethodsDepth < 0) {
                throw new DataException("Closing non existent JDBC transaction method level");
            }

            int initialDepth = initialTxDepth.removeLast();
            if (initialDepth != txDepth) {
                txDepth = initialDepth;
            }
            if (txMethodsDepth == 0) {
                txType = null;
            }
        }

        void begin() {
            txDepth++;
        }

        void complete() {
            txDepth--;
            if (txDepth < 0) {
                throw new DataException("Closing non existent JDBC transaction level");
            }
        }
    }

    private static final class LocalTransactionStorage {

        private final List<Integer> initialStackSize;
        private final List<Stack> stack;

        private LocalTransactionStorage() {
            this.initialStackSize = new ArrayList<>();
            this.stack = new ArrayList<>();
        }

        void start() {
            initialStackSize.addLast(stack.size());
        }

        void end(Context context) {
            if (context.txMethodsDepth() == 0) {
                stack.forEach(Stack::close);
                stack.clear();
            }
            int stackSize = initialStackSize.removeLast();
            if (stack.size() > stackSize) {
                throw new DataException("New JDBC transaction was started but never finished");
            }
        }

        void begin(String identity) {
            stack.addLast(new Stack(identity));
        }

        void commit(String identity) {
            Stack current = stack(identity);
            try {
                if (current.isRollbackOnly()) {
                    current.rollback();
                    throw new TxException("JDBC transaction was marked for rollback only.");
                }
                current.commit();
            } finally {
                current.close();
                stack.removeLast();
            }
        }

        void rollback(String identity) {
            Stack current = stack(identity);
            try {
                current.rollback();
            } finally {
                current.close();
                stack.removeLast();
            }
        }

        void suspend(String identity) {
            stack(identity).suspend();
        }

        void resume(String identity) {
            stack(identity).resume();
        }

        Connection connection(DataSource dataSource) throws SQLException {
            return stack().connection(dataSource);
        }

        boolean suspended() {
            return !stack.isEmpty() && stack().suspended();
        }

        String currentIdentity() {
            if (stack.isEmpty()) {
                throw new TxException("No JDBC transaction instance is available.");
            }
            return stack().identity();
        }

        void rollbackOnly() {
            if (!stack.isEmpty()) {
                stack().markRollbackOnly();
            }
        }

        private Stack stack() {
            return stack.getLast();
        }

        private Stack stack(String identity) {
            Stack current = stack();
            if (!current.identity().equals(identity)) {
                throw new IllegalStateException("JDBC transaction identity does not match the active transaction");
            }
            return current;
        }
    }

    private static final class Stack {

        private final String identity;
        private final Map<DataSource, Connection> connections;
        private boolean suspended;
        private boolean rollbackOnly;

        private Stack(String identity) {
            this.identity = identity;
            this.connections = new IdentityHashMap<>();
            this.suspended = false;
            this.rollbackOnly = false;
        }

        String identity() {
            return identity;
        }

        Connection connection(DataSource dataSource) throws SQLException {
            Connection connection = connections.get(dataSource);
            if (connection == null) {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                connections.put(dataSource, connection);
            }
            return connection;
        }

        void commit() {
            connections.values().forEach(JdbcTransactionContext::commit);
        }

        void rollback() {
            connections.values().forEach(JdbcTransactionContext::rollback);
        }

        void close() {
            connections.values().forEach(JdbcTransactionContext::close);
            connections.clear();
        }

        void suspend() {
            if (suspended) {
                throw new TxException("Cannot suspend already suspended JDBC transaction");
            }
            suspended = true;
        }

        void resume() {
            if (!suspended) {
                throw new TxException("Cannot resume active JDBC transaction");
            }
            suspended = false;
        }

        boolean suspended() {
            return suspended;
        }

        void markRollbackOnly() {
            rollbackOnly = true;
        }

        boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }

    @Service.Singleton
    static class LifeCycle implements TxLifeCycle {

        @Override
        public void start(String type) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            transactionContext.context.start(type);
            if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                transactionContext.localStorage.start();
            }
        }

        @Override
        public void end() {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            TransactionType txType = transactionContext.context.txType();
            transactionContext.context.end();
            if (txType == TransactionType.RESOURCE_LOCAL) {
                transactionContext.localStorage.end(transactionContext.context);
            }
        }

        @Override
        public void begin(String txIdentity) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            transactionContext.context.begin();
            if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                transactionContext.localStorage.begin(txIdentity);
            }
        }

        @Override
        public void commit(String txIdentity) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            try {
                if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                    transactionContext.localStorage.commit(txIdentity);
                }
            } finally {
                transactionContext.context.complete();
            }
        }

        @Override
        public void rollback(String txIdentity) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            try {
                if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                    transactionContext.localStorage.rollback(txIdentity);
                }
            } finally {
                transactionContext.context.complete();
            }
        }

        @Override
        public void suspend(String txIdentity) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                transactionContext.localStorage.suspend(txIdentity);
            }
        }

        @Override
        public void resume(String txIdentity) {
            JdbcTransactionContext transactionContext = JdbcTransactionContext.getInstance();
            if (transactionContext.context.txType() == TransactionType.RESOURCE_LOCAL) {
                transactionContext.localStorage.resume(txIdentity);
            }
        }
    }

    private static void commit(Connection connection) {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new TxException("JDBC transaction commit failed.", e);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new TxException("JDBC transaction rollback failed.", e);
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new TxException("JDBC transaction connection close failed.", e);
        }
    }

    private static final class LocalContext extends ThreadLocal<JdbcTransactionContext> {

        @Override
        public JdbcTransactionContext initialValue() {
            return new JdbcTransactionContext();
        }
    }
}
