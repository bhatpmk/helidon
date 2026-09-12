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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.helidon.data.DataException;
import io.helidon.data.sql.datasource.spi.XaDataSourceCapability;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.GlobalTransaction;
import io.helidon.transaction.spi.GlobalTransactionDelist;
import io.helidon.transaction.spi.GlobalTransactionParticipant;
import io.helidon.transaction.spi.GlobalTransactionStatus;
import io.helidon.transaction.spi.GlobalTransactionSynchronization;

/**
 * Transaction-scoped owner of every JDBC XA association enlisted by Helidon
 * Data JDBC.
 */
final class JdbcGlobalTransactionAssociation
        implements GlobalTransactionParticipant, GlobalTransactionSynchronization {

    private static final Object RESOURCE_KEY = new Object();
    private static final String PARTICIPANT_FAMILY = "helidon-data-jdbc";

    private final GlobalTransaction transaction;
    private final Object transactionKey;
    private final Map<Object, JdbcGlobalDataSourceAssociation> dataSources = new HashMap<>();
    private JdbcGlobalDataSourceAssociation activeOperation;
    private boolean completed;

    private JdbcGlobalTransactionAssociation(GlobalTransaction transaction) {
        this.transaction = transaction;
        this.transactionKey = transaction.key();
    }

    /**
     * Returns the state associated with the actual global transaction, creating
     * and registering it on first use.
     *
     * @param transaction current global transaction
     * @return transaction-scoped JDBC association
     */
    static JdbcGlobalTransactionAssociation getOrCreate(GlobalTransaction transaction) {
        transaction.claimParticipantFamily(PARTICIPANT_FAMILY);
        synchronized (RESOURCE_KEY) {
            Object existing = transaction.resource(RESOURCE_KEY).orElse(null);
            if (existing != null) {
                if (!(existing instanceof JdbcGlobalTransactionAssociation association)) {
                    transaction.rollbackOnly();
                    throw new TxException("The global transaction contains incompatible JDBC association state.");
                }
                association.requireTransaction(transaction);
                return association;
            }

            JdbcGlobalTransactionAssociation created = new JdbcGlobalTransactionAssociation(transaction);
            // Register cleanup before publishing state or acquiring resources. A later
            // setup failure still leaves a completion path owned by the transaction.
            transaction.registerSynchronization(created);
            transaction.registerParticipant(created);
            transaction.putResource(RESOURCE_KEY, created);
            return created;
        }
    }

    @Override
    public synchronized void beforeCompletion() {
        if (activeOperation != null) {
            transaction.rollbackOnly();
            throw new TxException("A global transaction cannot complete while a JDBC terminal operation is active.");
        }
    }

    @Override
    public synchronized void afterCompletion(GlobalTransactionStatus status) {
        Objects.requireNonNull(status, "The global transaction completion status must not be null.");
        completed = true;
        activeOperation = null;
        Throwable failure = null;
        for (JdbcGlobalDataSourceAssociation association : dataSources.values()) {
            Throwable closeFailure = association.close();
            if (closeFailure != null) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        dataSources.clear();
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new TxException("The global JDBC transaction completed with status '" + status
                                          + "', but XA connection cleanup failed.",
                                  JdbcExceptionTranslator.sanitize("closing global transaction resources", failure));
        }
    }

    @Override
    public synchronized void beforeSuspend() {
        if (activeOperation == null) {
            return;
        }
        // Suspending an XA branch while its result set or statement remains open
        // is not portable across drivers. Fail before JTA detaches the context.
        transaction.rollbackOnly();
        throw new TxException("A global transaction cannot be suspended during an active JDBC terminal operation.");
    }

    @Override
    public void afterResume() {
        // Terminals delist before ordinary suspension, and active-terminal suspension is rejected above.
    }

    /**
     * Acquires an exclusive operation lease and enlists the datasource resource.
     *
     * @param source resolved global-capable source
     * @return logical operation lease
     */
    synchronized JdbcConnectionLease acquire(JdbcConnectionSource source) {
        if (completed) {
            transaction.rollbackOnly();
            throw new TxException("The global JDBC transaction association is already complete.");
        }
        if (activeOperation != null) {
            transaction.rollbackOnly();
            throw new DataException("Concurrent JDBC terminal operations are not supported in one global transaction.");
        }

        Object identity = source.transactionIdentity();
        JdbcGlobalDataSourceAssociation association = dataSources.get(identity);
        boolean created = false;
        try {
            if (association == null) {
                XaDataSourceCapability xa = source.xa().orElseThrow(() ->
                        new DataException("The JDBC data source is not XA-capable."));
                association = JdbcGlobalDataSourceAssociation.create(xa);
                created = true;
            } else if (association.state() == JdbcGlobalDataSourceAssociation.State.FAILED
                    || association.state() == JdbcGlobalDataSourceAssociation.State.CLOSED) {
                throw new DataException("The JDBC XA association cannot be reused after a failure.");
            }

            transaction.enlist(association.xaResource());
            association.enlisted();
            if (created) {
                dataSources.put(identity, association);
            }
            activeOperation = association;
            return new JdbcGlobalTransactionLease(this, association);
        } catch (RuntimeException | Error failure) {
            if (created && association != null) {
                association.failed();
                Throwable closeFailure = association.close();
                if (closeFailure != null && closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            markRollbackOnly(failure);
            throw failure;
        }
    }

    /**
     * Delists one terminal operation while retaining its XA connection until
     * global transaction completion.
     *
     * @param association datasource association
     * @param operationFailed whether the terminal operation failed
     */
    synchronized void release(JdbcGlobalDataSourceAssociation association, boolean operationFailed) {
        if (activeOperation != association) {
            markRollbackOnly(null);
            throw new IllegalStateException("The global JDBC connection lease is not the active operation.");
        }

        Throwable releaseFailure = null;
        try {
            GlobalTransactionDelist flag = operationFailed
                    ? GlobalTransactionDelist.FAIL
                    : GlobalTransactionDelist.SUCCESS;
            transaction.delist(association.xaResource(), flag);
            if (operationFailed) {
                association.failed();
            } else {
                association.delisted();
            }
        } catch (RuntimeException | Error failure) {
            association.failed();
            releaseFailure = failure;
        } finally {
            activeOperation = null;
        }

        if (operationFailed || releaseFailure != null) {
            try {
                transaction.rollbackOnly();
            } catch (RuntimeException | Error rollbackFailure) {
                if (releaseFailure == null) {
                    releaseFailure = rollbackFailure;
                } else if (releaseFailure != rollbackFailure) {
                    releaseFailure.addSuppressed(rollbackFailure);
                }
            }
        }
        if (releaseFailure instanceof Error error) {
            throw error;
        }
        if (releaseFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    private void requireTransaction(GlobalTransaction current) {
        if (!Objects.equals(transactionKey, current.key())) {
            current.rollbackOnly();
            throw new TxException("The JDBC association does not belong to the current global transaction.");
        }
    }

    private void markRollbackOnly(Throwable primary) {
        try {
            transaction.rollbackOnly();
        } catch (RuntimeException | Error rollbackFailure) {
            if (primary == null) {
                throw rollbackFailure;
            }
            if (primary != rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
            }
        }
    }
}
