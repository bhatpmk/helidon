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
import io.helidon.common.Weighted;
import io.helidon.service.registry.Service.Inject;
import io.helidon.service.registry.Service.Singleton;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;
import io.helidon.transaction.spi.TxSupport;

import static java.util.Objects.requireNonNull;

@Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 20) // ...in case there are more sophisticated TxSupport implementations
final class JdbcTxSupport implements TxSupport {

    private final ThreadLocal<Deque<Transaction>> transactions;

    private final List<TxLifeCycle> listeners;

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

    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public <T> T transaction(Tx.Type type, Callable<T> task) {
        requireNonNull(type, "type");
        requireNonNull(task, "task");
        start();
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
            end();
        }
    }

    Optional<String> currentTransactionId() {
        return currentActiveTransaction().map(Transaction::id);
    }

    boolean currentActiveTransactionRollbackOnly() {
        return currentActiveTransaction()
            .map(Transaction::rollbackOnly)
            .orElse(false);
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
        return runOutside(task);
    }

    private <T> T txRequired(Callable<T> task) {
        return transactionActive() ? runInCurrent(task) : runInNew(task);
    }

    private <T> T txSupported(Callable<T> task) {
        return transactionActive() ? runInCurrent(task) : runOutside(task);
    }

    private <T> T txUnsupported(Callable<T> task) {
        String previousId = currentTransactionId().orElse(null);
        if (previousId == null) {
            return runOutside(task);
        }
        suspend(previousId);
        try {
            return runOutside(task);
        } finally {
            resume(previousId);
        }
    }

    private <T> T runOutside(Callable<T> task) {
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
        String txid = newTxId();
        begin(txid);
        try {
            T result = task.call();
            complete(txid);
            return result;
        } catch (RuntimeException e) {
            rollbackActiveTransactionIfCurrent(txid);
            throw e;
        } catch (Exception e) {
            rollbackActiveTransactionIfCurrent(txid);
            throw new TxException(e.getMessage(), e);
        }
    }

    private void complete(String txid) {
        if (!isCurrentActiveTransaction(txid)) {
            throw new TxException("No current transaction to complete: " + txid);
        }
        if (currentActiveTransactionRollbackOnly()) {
            rollback(txid);
            throw new TxException("Transaction is marked rollback only: " + txid);
        }
        commit(txid);
    }

    private void start() {
        this.listeners.forEach(listener -> listener.start(type()));
    }

    private void end() {
        this.listeners.forEach(TxLifeCycle::end);
    }

    private void begin(String txid) {
        pushNewActiveTransaction(txid);
        try {
            this.listeners.forEach(listener -> listener.begin(txid));
        } catch (RuntimeException | Error e) {
            rollbackActiveTransactionIfCurrent(txid);
            throw e;
        }
    }

    private void commit(String txid) {
        commitActiveTransaction(txid);
        this.listeners.forEach(listener -> listener.commit(txid));
    }

    private void rollback(String txid) {
        rollbackActiveTransaction(txid);
        this.listeners.forEach(listener -> listener.rollback(txid));
    }

    private void suspend(String txid) {
        suspendActiveTransaction(txid);
        try {
            this.listeners.forEach(listener -> listener.suspend(txid));
        } catch (RuntimeException | Error e) {
            resumeSuspendedTransaction(txid);
            throw e;
        }
    }

    private void resume(String txid) {
        resumeSuspendedTransaction(txid);
        try {
            this.listeners.forEach(listener -> listener.resume(txid));
        } catch (RuntimeException | Error e) {
            suspendActiveTransaction(txid);
            throw e;
        }
    }

    private void pushNewActiveTransaction(String txid) {
        this.transactions.get().push(new Transaction(txid));
    }

    private void commitActiveTransaction(String txid) {
        requireCurrentTransaction(txid, Status.ACTIVE, "commit");
        this.transactions.get().pop();
    }

    private void markCurrentActiveTransactionForRollback() {
        currentActiveTransaction()
            .orElseThrow(() -> new IllegalStateException("Cannot mark rollback only: no current active transaction"))
            .setRollbackOnly();
    }

    private void rollbackActiveTransaction(String txid) {
        requireCurrentTransaction(txid, Status.ACTIVE, "rollback");
        this.transactions.get().pop();
    }

    private void rollbackActiveTransactionIfCurrent(String txid) {
        if (isCurrentActiveTransaction(txid)) {
            rollback(txid);
        }
    }

    private void suspendActiveTransaction(String txid) {
        requireCurrentTransaction(txid, Status.ACTIVE, "suspend").status(Status.SUSPENDED);
    }

    private void resumeSuspendedTransaction(String txid) {
        requireCurrentTransaction(txid, Status.SUSPENDED, "resume").status(Status.ACTIVE);
    }

    private boolean isCurrentActiveTransaction(String txid) {
        return currentActiveTransaction()
            .map(transaction -> transaction.id().equals(txid))
            .isPresent();
    }

    private Optional<Transaction> currentActiveTransaction() {
        return currentTransaction(Status.ACTIVE);
    }

    private Optional<Transaction> currentTransaction(Status status) {
        return currentTransaction().filter(transaction -> transaction.status() == status);
    }

    private Optional<Transaction> currentTransaction() {
        return Optional.ofNullable(this.transactions.get().peek());
    }

    private Transaction requireCurrentTransaction(String txid, Status status, String action) {
        Transaction transaction = currentTransaction()
            .orElseThrow(() -> new IllegalStateException("Cannot " + action + ": no current transaction"));
        if (!transaction.id().equals(txid)) {
            throw new IllegalStateException("Cannot " + action + " transaction " + txid
                                            + ": current transaction is " + transaction.id());
        }
        if (transaction.status() != status) {
            throw new IllegalStateException("Cannot " + action + " transaction " + txid
                                            + " while in status " + transaction.status()
                                            + ": expected " + status);
        }
        return transaction;
    }

    private static String newTxId() {
        return UUID.randomUUID().toString();
    }

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
