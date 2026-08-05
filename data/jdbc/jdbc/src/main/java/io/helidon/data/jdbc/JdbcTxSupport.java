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
 * Resource-local {@link TxSupport} for JDBC repository methods.
 * <p>
 * This service is intentionally weighted below the Jakarta Persistence resource-local support. If both providers are
 * present without JTA, Jakarta Persistence remains the selected transaction driver and this module participates through
 * {@link TxLifeCycle} notifications.
 */
@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 20)
class JdbcTxSupport implements TxSupport {

    private static final AtomicLong TX_IDENTITY = new AtomicLong();

    private final List<TxLifeCycle> txListeners;

    @Service.Inject
    JdbcTxSupport(List<TxLifeCycle> txListeners) {
        this.txListeners = txListeners;
    }

    @Override
    public String type() {
        return "resource-local";
    }

    @Override
    public <T> T transaction(Tx.Type type, Callable<T> task) {
        Objects.requireNonNull(type, "Missing transaction type");
        Objects.requireNonNull(task, "Missing task to run in transaction");
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

    private <T> T txMandatory(Callable<T> task) {
        if (!JdbcTransactionContext.getInstance().isTransactionActive()) {
            throw new TxException("Starting @Tx.Mandatory transaction outside transaction scope.");
        }
        return runInCurrentTxScope(task);
    }

    private <T> T txNew(Callable<T> task) {
        String previous = JdbcTransactionContext.getInstance().isTransactionActive() ? suspend() : null;
        return runInNewTxScope(previous, task);
    }

    private <T> T txNever(Callable<T> task) {
        if (JdbcTransactionContext.getInstance().isTransactionActive()) {
            throw new TxException("Starting @Tx.Never with active transaction.");
        }
        return runOutsideTxScope(task);
    }

    private <T> T txRequired(Callable<T> task) {
        if (JdbcTransactionContext.getInstance().isTransactionActive()) {
            return runInCurrentTxScope(task);
        }
        return runInNewTxScope(null, task);
    }

    private <T> T txSupported(Callable<T> task) {
        if (JdbcTransactionContext.getInstance().isTransactionActive()) {
            return runInCurrentTxScope(task);
        }
        return runOutsideTxScope(task);
    }

    private <T> T txUnsupported(Callable<T> task) {
        if (JdbcTransactionContext.getInstance().isTransactionActive()) {
            return runInSuspendedTxScope(suspend(), task);
        }
        return runOutsideTxScope(task);
    }

    private <T> T runOutsideTxScope(Callable<T> task) {
        try {
            return task.call();
        } catch (TxException e) {
            throw e;
        } catch (Exception e) {
            throw new TxException("JDBC transaction task failed.", e);
        }
    }

    private <T> T runInSuspendedTxScope(String suspended, Callable<T> task) {
        try {
            return task.call();
        } catch (TxException e) {
            throw e;
        } catch (Exception e) {
            throw new TxException("JDBC transaction task failed.", e);
        } finally {
            resume(suspended);
        }
    }

    private <T> T runInCurrentTxScope(Callable<T> task) {
        try {
            return task.call();
        } catch (TxException e) {
            JdbcTransactionContext.getInstance().rollbackOnly();
            throw e;
        } catch (Exception e) {
            JdbcTransactionContext.getInstance().rollbackOnly();
            throw new TxException("JDBC transaction task failed.", e);
        }
    }

    private <T> T runInNewTxScope(String previous, Callable<T> task) {
        String current = begin();
        T result;
        try {
            try {
                result = task.call();
            } catch (TxException e) {
                rollback(current);
                throw e;
            } catch (Exception e) {
                rollback(current);
                throw new TxException("JDBC transaction task failed.", e);
            }
            commit(current);
            return result;
        } finally {
            if (previous != null) {
                resume(previous);
            }
        }
    }

    private String begin() {
        String identity = Long.toUnsignedString(TX_IDENTITY.incrementAndGet());
        txListeners.forEach(listener -> listener.begin(identity));
        return identity;
    }

    private void commit(String identity) {
        txListeners.forEach(listener -> listener.commit(identity));
    }

    private void rollback(String identity) {
        txListeners.forEach(listener -> listener.rollback(identity));
    }

    private String suspend() {
        String identity = JdbcTransactionContext.getInstance().currentTransactionIdentity();
        txListeners.forEach(listener -> listener.suspend(identity));
        return identity;
    }

    private void resume(String identity) {
        txListeners.forEach(listener -> listener.resume(identity));
    }

    private void start() {
        txListeners.forEach(listener -> listener.start(type()));
    }

    private void end() {
        txListeners.forEach(TxLifeCycle::end);
    }
}
