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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.service.registry.Service;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;
import io.helidon.transaction.spi.TxSupport;

/**
 * Local JDBC implementation of Helidon's transaction propagation contract.
 * <p>
 * This service owns propagation and lifecycle notification only. JDBC connections are lazily associated by a
 * {@link TxLifeCycle} listener in the data JDBC provider, avoiding a transaction-to-data module dependency.
 */
@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 20)
final class JdbcTxSupport implements TxSupport {
    private static final String TYPE = "jdbc";
    private static final AtomicLong IDS = new AtomicLong();

    private final List<TxLifeCycle> listeners;
    private final ThreadLocal<ArrayDeque<Transaction>> transactions = ThreadLocal.withInitial(ArrayDeque::new);

    @Service.Inject
    JdbcTxSupport(List<TxLifeCycle> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public <T> T transaction(Tx.Type type, Callable<T> task) {
        Objects.requireNonNull(type, "Missing transaction type");
        Objects.requireNonNull(task, "Missing task to run in transaction");
        try {
            notifyListeners(listener -> listener.start(TYPE), "start");
        } catch (RuntimeException | Error startFailure) {
            try {
                notifyListeners(TxLifeCycle::end, "start cleanup");
            } catch (RuntimeException | Error cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            throw startFailure;
        }
        T result;
        try {
            result = switch (type) {
                case MANDATORY -> mandatory(task);
                case NEW -> requiresNew(task);
                case NEVER -> never(task);
                case REQUIRED -> required(task);
                case SUPPORTED -> supported(task);
                case UNSUPPORTED -> unsupported(task);
            };
        } catch (RuntimeException | Error failure) {
            try {
                notifyListeners(TxLifeCycle::end, "end");
            } catch (RuntimeException | Error endFailure) {
                failure.addSuppressed(endFailure);
            }
            throw failure;
        }
        notifyListeners(TxLifeCycle::end, "end");
        return result;
    }

    private <T> T mandatory(Callable<T> task) {
        Transaction current = current();
        if (current == null) {
            throw new TxException("Starting @Tx.Mandatory outside a local JDBC transaction");
        }
        return callJoined(current, task);
    }

    private <T> T requiresNew(Callable<T> task) {
        Transaction suspended = suspend();
        T result;
        try {
            result = callNew(task);
        } catch (RuntimeException | Error failure) {
            resumeAfterFailure(suspended, failure);
            throw failure;
        }
        resume(suspended);
        return result;
    }

    private <T> T never(Callable<T> task) {
        if (current() != null) {
            throw new TxException("Starting @Tx.Never inside a local JDBC transaction");
        }
        return callOutside(task);
    }

    private <T> T required(Callable<T> task) {
        Transaction current = current();
        return current == null ? callNew(task) : callJoined(current, task);
    }

    private <T> T supported(Callable<T> task) {
        Transaction current = current();
        return current == null ? callOutside(task) : callJoined(current, task);
    }

    private <T> T unsupported(Callable<T> task) {
        Transaction suspended = suspend();
        T result;
        try {
            result = callOutside(task);
        } catch (RuntimeException | Error failure) {
            resumeAfterFailure(suspended, failure);
            throw failure;
        }
        resume(suspended);
        return result;
    }

    private <T> T callOutside(Callable<T> task) {
        try {
            return task.call();
        } catch (TxException e) {
            throw e;
        } catch (Exception e) {
            throw new TxException("Local JDBC transaction task failed", e);
        }
    }

    private <T> T callJoined(Transaction transaction, Callable<T> task) {
        try {
            return task.call();
        } catch (TxException e) {
            transaction.rollbackOnly = true;
            throw e;
        } catch (Exception e) {
            transaction.rollbackOnly = true;
            throw new TxException("Local JDBC transaction task failed", e);
        } catch (Error e) {
            transaction.rollbackOnly = true;
            throw e;
        }
    }

    private <T> T callNew(Callable<T> task) {
        Transaction transaction = begin();
        T result;
        try {
            result = task.call();
        } catch (Throwable taskFailure) {
            rollback(transaction, taskFailure);
            if (taskFailure instanceof Error error) {
                throw error;
            }
            if (taskFailure instanceof TxException txException) {
                throw txException;
            }
            throw new TxException("Local JDBC transaction task failed", taskFailure);
        }

        if (transaction.rollbackOnly) {
            rollback(transaction, null);
            throw new TxException("Local JDBC transaction was marked rollback-only");
        }
        commit(transaction);
        return result;
    }

    private Transaction begin() {
        Transaction transaction = new Transaction(Long.toUnsignedString(IDS.incrementAndGet(), 36));
        transactions.get().push(transaction);
        try {
            notifyListeners(listener -> listener.begin(transaction.identity), "begin");
            return transaction;
        } catch (RuntimeException | Error failure) {
            transactions.get().pop();
            try {
                notifyListeners(listener -> listener.rollback(transaction.identity), "begin cleanup");
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            removeThreadStateIfEmpty();
            throw failure;
        }
    }

    private void commit(Transaction transaction) {
        removeCurrent(transaction);
        notifyListeners(listener -> listener.commit(transaction.identity), "commit");
    }

    private void rollback(Transaction transaction, Throwable primaryFailure) {
        removeCurrent(transaction);
        try {
            notifyListeners(listener -> listener.rollback(transaction.identity), "rollback");
        } catch (RuntimeException | Error rollbackFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(rollbackFailure);
            } else {
                throw rollbackFailure;
            }
        }
    }

    private Transaction suspend() {
        ArrayDeque<Transaction> stack = transactions.get();
        if (stack.isEmpty()) {
            return null;
        }
        Transaction transaction = stack.pop();
        try {
            notifyListeners(listener -> listener.suspend(transaction.identity), "suspend");
        } catch (RuntimeException | Error failure) {
            stack.push(transaction);
            try {
                notifyListeners(listener -> listener.resume(transaction.identity), "suspend cleanup");
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        removeThreadStateIfEmpty();
        return transaction;
    }

    private void resume(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        // Restore the transaction stack before notifying listeners. If a listener fails, the enclosing transaction can
        // still unwind and roll back the suspended transaction deterministically.
        transactions.get().push(transaction);
        try {
            notifyListeners(listener -> listener.resume(transaction.identity), "resume");
        } catch (RuntimeException | Error failure) {
            try {
                notifyListeners(listener -> listener.suspend(transaction.identity), "resume cleanup");
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void resumeAfterFailure(Transaction transaction, Throwable primaryFailure) {
        try {
            resume(transaction);
        } catch (RuntimeException | Error resumeFailure) {
            primaryFailure.addSuppressed(resumeFailure);
        }
    }

    private Transaction current() {
        return transactions.get().peek();
    }

    private void removeCurrent(Transaction expected) {
        ArrayDeque<Transaction> stack = transactions.get();
        Transaction actual = stack.poll();
        if (actual != expected) {
            throw new IllegalStateException("Local JDBC transaction stack is inconsistent");
        }
        removeThreadStateIfEmpty();
    }

    private void removeThreadStateIfEmpty() {
        if (transactions.get().isEmpty()) {
            transactions.remove();
        }
    }

    private void notifyListeners(ListenerAction action, String event) {
        Throwable failure = null;
        for (TxLifeCycle listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException | Error listenerFailure) {
                if (failure == null) {
                    failure = listenerFailure;
                } else {
                    failure.addSuppressed(listenerFailure);
                }
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new TxException("Local JDBC transaction " + event + " notification failed", failure);
        }
    }

    @FunctionalInterface
    private interface ListenerAction {
        void accept(TxLifeCycle listener);
    }

    private static final class Transaction {
        private final String identity;
        private boolean rollbackOnly;

        private Transaction(String identity) {
            this.identity = identity;
        }
    }
}
