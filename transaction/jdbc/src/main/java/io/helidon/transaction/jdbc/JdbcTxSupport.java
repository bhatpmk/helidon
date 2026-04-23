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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import io.helidon.common.Weight;
import io.helidon.service.registry.Service.Inject;
import io.helidon.service.registry.Service.Singleton;
import io.helidon.transaction.Tx.Type;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;
import io.helidon.transaction.spi.TxSupport;

import static io.helidon.common.Weighted.DEFAULT_WEIGHT;
import static java.util.Objects.requireNonNull;

@Singleton
@Weight(DEFAULT_WEIGHT - 20)
final class JdbcTxSupport implements TxSupport {


    /*
     * Instance fields.
     */


    private final ThreadLocal<Deque<Transaction>> transactions;

    private final List<TxLifeCycle> listeners;


    /*
     * Constructors.
     */


    @Inject
    JdbcTxSupport(List<TxLifeCycle> listeners) {
        super();
        this.transactions = ThreadLocal.withInitial(ArrayDeque::new);
        this.listeners = List.copyOf(listeners);
    }

    // Convenience for testing
    JdbcTxSupport(TxLifeCycle... listeners) {
        this(List.of(listeners));
    }


    /*
     * Instance methods.
     */


    @Override // TxSupport
    public <T> T transaction(Type type, Callable<T> task) {
        requireNonNull(type, "type");
        requireNonNull(task, "task");
        this.start();
        try {
            return switch (type) {
                case MANDATORY -> txMandatory(task);
                case NEW -> txNew(task);
                case NEVER -> txNever(task);
                case REQUIRED -> txRequired(task);
                case SUPPORTED -> txSupported(task);
                case UNSUPPORTED -> txUnsupported(task);
            };
        } finally {
            this.end();
        }
    }

    @Override // TxSupport
    public String type() {
        return "jdbc";
    }

    boolean currentActiveTransactionRollbackOnly() {
        return currentActiveTransaction()
            .map(Transaction::rollbackOnly)
            .orElse(false);
    }


    Optional<String> currentTransactionId() {
        return currentActiveTransaction().map(Transaction::id);
    }

    int depth() {
        return this.transactions.get().size();
    }

    boolean transactionActive() {
        return currentActiveTransaction().isPresent();
    }


    /*
     * Private methods.
     */


    private <T> T txMandatory(Callable<T> task) {
        if (!transactionActive()) {
            throw new TxException("Mandatory check failed; no current active transaction exists");
        }
        return runInCurrent(task);
    }

    private <T> T txNew(Callable<T> task) {
        String previous = currentTransactionId().orElse(null);
        if (previous != null) {
            suspend(previous);
        }
        try {
            return runInNew(task);
        } finally {
            if (previous != null) {
                resume(previous);
            }
        }
    }

    private <T> T txNever(Callable<T> task) {
        if (transactionActive()) {
            throw new TxException("Never check failed; current active transaction exists");
        }
        return runWithout(task);
    }

    private <T> T txRequired(Callable<T> task) {
        return transactionActive() ? runInCurrent(task) : runInNew(task);
    }

    private <T> T txSupported(Callable<T> task) {
        return transactionActive() ? runInCurrent(task) : runWithout(task);
    }

    private <T> T txUnsupported(Callable<T> task) {
        String previousId = currentTransactionId().orElse(null);
        if (previousId == null) {
            return runWithout(task);
        }
        suspend(previousId);
        try {
            return runWithout(task);
        } finally {
            resume(previousId);
        }
    }

    private <T> T runWithout(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new TxException(e.getMessage(), e);
        }
    }

    private <T> T runInCurrent(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            markCurrentActiveTransactionForRollback();
            throw e;
        } catch (Exception e) {
            markCurrentActiveTransactionForRollback();
            throw new TxException(e.getMessage(), e);
        }
    }

    private <T> T runInNew(Callable<T> task) {
        String txId = newTxId();
        begin(txId);
        try {
            T result = task.call();
            complete(txId);
            return result;
        } catch (RuntimeException e) {
            rollbackActiveTransactionIfCurrent(txId);
            throw e;
        } catch (Exception e) {
            rollbackActiveTransactionIfCurrent(txId);
            throw new TxException(e.getMessage(), e);
        }
    }

    private void complete(String txId) {
        Transaction t = currentActiveTransaction()
            .filter(tx -> tx.id().equals(txId))
            .orElseThrow(() -> new TxException("No current active transaction to complete: " + txId));
        if (t.rollbackOnly()) {
            this.transactions.get().pop();
            this.listeners.forEach(listener -> listener.rollback(txId));
            throw new TxException("Current active transaction is marked rollback only: " + txId);
        }
        commit(txId);
    }

    private void start() {
        this.listeners.forEach(listener -> listener.start(type()));
    }

    private void end() {
        this.listeners.forEach(TxLifeCycle::end);
    }

    private void begin(String txId) {
        this.transactions.get().push(new Transaction(txId));
        try {
            this.listeners.forEach(listener -> listener.begin(txId));
        } catch (RuntimeException | Error e) {
            rollbackActiveTransactionIfCurrent(txId);
            throw e;
        }
    }

    private void commit(String txId) {
        requireCurrentTransaction(txId, Status.ACTIVE, "commit");
        this.transactions.get().pop();
        this.listeners.forEach(listener -> listener.commit(txId));
    }

    private void markCurrentActiveTransactionForRollback() {
        currentActiveTransaction()
            .orElseThrow(() -> new IllegalStateException("Cannot mark rollback only: no current active transaction"))
            .setRollbackOnly();
    }

    private void rollbackActiveTransactionIfCurrent(String txId) {
        if (isCurrentActiveTransaction(txId)) {
            this.transactions.get().pop();
            this.listeners.forEach(listener -> listener.rollback(txId));
        }
    }

    private void suspend(String txId) {
        suspendActiveTransaction(txId);
        try {
            this.listeners.forEach(listener -> listener.suspend(txId));
        } catch (RuntimeException | Error e) {
            resumeSuspendedTransaction(txId);
            throw e;
        }
    }

    private void suspendActiveTransaction(String txId) {
        requireCurrentTransaction(txId, Status.ACTIVE, "suspend").status(Status.SUSPENDED);
    }

    private void resume(String txId) {
        resumeSuspendedTransaction(txId);
        try {
            this.listeners.forEach(listener -> listener.resume(txId));
        } catch (RuntimeException | Error e) {
            suspendActiveTransaction(txId);
            throw e;
        }
    }

    private void resumeSuspendedTransaction(String txId) {
        requireCurrentTransaction(txId, Status.SUSPENDED, "resume").status(Status.ACTIVE);
    }

    private boolean isCurrentActiveTransaction(String txId) {
        return currentActiveTransaction()
            .map(t -> t.id().equals(txId))
            .isPresent();
    }

    private Optional<Transaction> currentActiveTransaction() {
        return Optional.ofNullable(this.transactions.get().peek())
            .filter(t -> t.status() == Status.ACTIVE);
    }

    private Transaction requireCurrentTransaction(String txId, Status status, String action) {
        Transaction t = Optional.ofNullable(this.transactions.get().peek())
            .orElseThrow(() -> new IllegalStateException("Cannot " + action + ": no current transaction"));
        if (!t.id().equals(txId)) {
            throw new IllegalStateException("Cannot " + action + " transaction " + txId
                                            + ": current transaction is " + t.id());
        } else if (t.status() != status) {
            throw new IllegalStateException("Cannot " + action + " transaction " + txId
                                            + " while in status " + t.status()
                                            + ": expected " + status);
        }
        return t;
    }


    /*
     * Static methods.
     */


    private static String newTxId() {
        return UUID.randomUUID().toString();
    }


    /*
     * Inner and nested classes.
     */


    private enum Status {
        ACTIVE,
        SUSPENDED
    }

    private static final class Transaction {

        private final String id;

        private Status status;

        private boolean rollbackOnly;

        private Transaction(String id) {
            super();
            this.id = requireNonNull(id, "id");
            this.status = Status.ACTIVE;
            this.rollbackOnly = false;
        }

        private String id() {
            return this.id;
        }

        private Status status() {
            return this.status;
        }

        private void status(Status status) {
            this.status = requireNonNull(status, "status");
        }

        private boolean rollbackOnly() {
            return this.rollbackOnly;
        }

        private void setRollbackOnly() {
            this.rollbackOnly = true;
        }

    }

}
