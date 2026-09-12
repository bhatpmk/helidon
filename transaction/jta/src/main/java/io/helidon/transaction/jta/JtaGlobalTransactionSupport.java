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
package io.helidon.transaction.jta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.transaction.xa.XAResource;

import io.helidon.service.registry.Service;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.GlobalTransaction;
import io.helidon.transaction.spi.GlobalTransactionDelist;
import io.helidon.transaction.spi.GlobalTransactionParticipant;
import io.helidon.transaction.spi.GlobalTransactionStatus;
import io.helidon.transaction.spi.GlobalTransactionSupport;
import io.helidon.transaction.spi.GlobalTransactionSynchronization;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Jakarta Transactions implementation of the provider-neutral global transaction SPI.
 */
@Service.Singleton
final class JtaGlobalTransactionSupport implements GlobalTransactionSupport {

    private static final Object PARTICIPANTS_KEY = new Object();
    private static final Object PARTICIPANT_FAMILY_KEY = new Object();
    private static final Object TRANSACTION_RESOURCE_LOCK = new Object();

    private final TransactionManager transactionManager;
    private final TransactionSynchronizationRegistry synchronizationRegistry;

    /**
     * Creates global transaction access backed by the selected JTA provider.
     *
     * @param provider JTA provider
     */
    @Service.Inject
    JtaGlobalTransactionSupport(JtaProvider provider) {
        this.transactionManager = provider.transactionManager();
        this.synchronizationRegistry = provider.transactionSynchronizationRegistry();
    }

    @Override
    public Optional<GlobalTransaction> current() {
        Transaction transaction;
        Transaction verifiedTransaction;
        try {
            transaction = transactionManager.getTransaction();
            Object key = synchronizationRegistry.getTransactionKey();
            int status = synchronizationRegistry.getTransactionStatus();
            verifiedTransaction = transactionManager.getTransaction();
            Object verifiedKey = synchronizationRegistry.getTransactionKey();
            if (!Objects.equals(transaction, verifiedTransaction) || !Objects.equals(key, verifiedKey)) {
                throw new TxException("The global transaction context changed while it was being resolved.");
            }
            if (key == null && transaction == null && status == Status.STATUS_NO_TRANSACTION) {
                return Optional.empty();
            }
            if (key == null || transaction == null) {
                throw new TxException("The global transaction manager and synchronization registry disagree about the "
                                              + "transaction associated with the current execution context.");
            }
            return Optional.of(new JtaGlobalTransaction(key,
                                                        transaction,
                                                        synchronizationRegistry,
                                                        status(status)));
        } catch (SystemException failure) {
            throw new TxException("Global transaction retrieval failed.", failure);
        }
    }

    /**
     * Delists resources that remain associated when Helidon suspends the current transaction.
     */
    void beforeSuspend() {
        current().filter(JtaGlobalTransactionSupport::canSuspend)
                .ifPresent(transaction -> transaction.resource(PARTICIPANTS_KEY)
                        .map(JtaGlobalTransactionSupport::participants)
                        .ifPresent(Participants::beforeSuspend));
    }

    /**
     * Re-enlists resources after Helidon resumes a transaction.
     */
    void afterResume() {
        current().filter(JtaGlobalTransactionSupport::canSuspend)
                .ifPresent(transaction -> transaction.resource(PARTICIPANTS_KEY)
                        .map(JtaGlobalTransactionSupport::participants)
                        .ifPresent(Participants::afterResume));
    }

    private static boolean canSuspend(GlobalTransaction transaction) {
        GlobalTransactionStatus status = transaction.status();
        return status == GlobalTransactionStatus.ACTIVE || status == GlobalTransactionStatus.MARKED_ROLLBACK;
    }

    private static Participants participants(Object value) {
        if (value instanceof Participants participants) {
            return participants;
        }
        throw new TxException("The global transaction contains incompatible participant state.");
    }

    private static GlobalTransactionStatus status(int status) {
        return switch (status) {
            case Status.STATUS_ACTIVE -> GlobalTransactionStatus.ACTIVE;
            case Status.STATUS_MARKED_ROLLBACK -> GlobalTransactionStatus.MARKED_ROLLBACK;
            case Status.STATUS_PREPARING -> GlobalTransactionStatus.PREPARING;
            case Status.STATUS_PREPARED -> GlobalTransactionStatus.PREPARED;
            case Status.STATUS_COMMITTING -> GlobalTransactionStatus.COMMITTING;
            case Status.STATUS_COMMITTED -> GlobalTransactionStatus.COMMITTED;
            case Status.STATUS_ROLLING_BACK -> GlobalTransactionStatus.ROLLING_BACK;
            case Status.STATUS_ROLLEDBACK -> GlobalTransactionStatus.ROLLED_BACK;
            case Status.STATUS_UNKNOWN, Status.STATUS_NO_TRANSACTION -> GlobalTransactionStatus.UNKNOWN;
            default -> throw new TxException("Unknown Jakarta Transactions status code " + status + ".");
        };
    }

    private static int flag(GlobalTransactionDelist reason) {
        return switch (reason) {
            case SUCCESS -> XAResource.TMSUCCESS;
            case FAIL -> XAResource.TMFAIL;
            case SUSPEND -> XAResource.TMSUSPEND;
        };
    }

    /**
     * Provider-neutral view of one Jakarta transaction.
     */
    private static final class JtaGlobalTransaction implements GlobalTransaction {

        private final Object key;
        private final Transaction transaction;
        private final TransactionSynchronizationRegistry synchronizationRegistry;
        private final GlobalTransactionStatus initialStatus;

        private JtaGlobalTransaction(Object key,
                                     Transaction transaction,
                                     TransactionSynchronizationRegistry synchronizationRegistry,
                                     GlobalTransactionStatus initialStatus) {
            this.key = key;
            this.transaction = transaction;
            this.synchronizationRegistry = synchronizationRegistry;
            this.initialStatus = initialStatus;
        }

        @Override
        public Object key() {
            return key;
        }

        @Override
        public GlobalTransactionStatus status() {
            try {
                return JtaGlobalTransactionSupport.status(transaction.getStatus());
            } catch (SystemException failure) {
                // Preserve the status that proved a transaction was present so a transient status failure cannot be
                // mistaken for transaction absence.
                if (initialStatus != GlobalTransactionStatus.ACTIVE) {
                    return initialStatus;
                }
                throw new TxException("Global transaction status retrieval failed.", failure);
            }
        }

        @Override
        public Optional<Object> resource(Object key) {
            Objects.requireNonNull(key, "The global transaction resource key must not be null.");
            try {
                return Optional.ofNullable(synchronizationRegistry.getResource(key));
            } catch (IllegalStateException failure) {
                throw new TxException("Global transaction resource retrieval failed.", failure);
            }
        }

        @Override
        public void putResource(Object key, Object value) {
            Objects.requireNonNull(key, "The global transaction resource key must not be null.");
            Objects.requireNonNull(value, "The global transaction resource value must not be null.");
            try {
                synchronizationRegistry.putResource(key, value);
            } catch (IllegalStateException failure) {
                throw new TxException("Global transaction resource association failed.", failure);
            }
        }

        @Override
        public void enlist(XAResource resource) {
            Objects.requireNonNull(resource, "The XA resource must not be null.");
            try {
                if (!transaction.enlistResource(resource)) {
                    throw new TxException("The global transaction rejected XA resource enlistment.");
                }
            } catch (RollbackException | SystemException | IllegalStateException failure) {
                throw new TxException("XA resource enlistment failed.", failure);
            }
        }

        @Override
        public void delist(XAResource resource, GlobalTransactionDelist reason) {
            Objects.requireNonNull(resource, "The XA resource must not be null.");
            Objects.requireNonNull(reason, "The XA resource delist reason must not be null.");
            try {
                if (!transaction.delistResource(resource, flag(reason))) {
                    throw new TxException("The global transaction rejected XA resource delistment.");
                }
            } catch (SystemException | IllegalStateException failure) {
                throw new TxException("XA resource delistment failed.", failure);
            }
        }

        @Override
        public void rollbackOnly() {
            try {
                transaction.setRollbackOnly();
            } catch (SystemException | IllegalStateException failure) {
                throw new TxException("Marking the global transaction for rollback failed.", failure);
            }
        }

        @Override
        public void registerSynchronization(GlobalTransactionSynchronization synchronization) {
            Objects.requireNonNull(synchronization, "The global transaction synchronization must not be null.");
            try {
                synchronizationRegistry.registerInterposedSynchronization(new Synchronization() {
                    @Override
                    public void beforeCompletion() {
                        synchronization.beforeCompletion();
                    }

                    @Override
                    public void afterCompletion(int status) {
                        synchronization.afterCompletion(JtaGlobalTransactionSupport.status(status));
                    }
                });
            } catch (IllegalStateException failure) {
                throw new TxException("Global transaction synchronization registration failed.", failure);
            }
        }

        @Override
        public void registerParticipant(GlobalTransactionParticipant participant) {
            Objects.requireNonNull(participant, "The global transaction participant must not be null.");
            synchronized (TRANSACTION_RESOURCE_LOCK) {
                Participants participants = resource(PARTICIPANTS_KEY)
                        .map(JtaGlobalTransactionSupport::participants)
                        .orElseGet(() -> {
                            Participants created = new Participants();
                            putResource(PARTICIPANTS_KEY, created);
                            return created;
                        });
                participants.add(participant);
            }
        }

        @Override
        public void claimParticipantFamily(String family) {
            Objects.requireNonNull(family, "The global transaction participant family must not be null.");
            if (family.isBlank()) {
                throw new IllegalArgumentException("The global transaction participant family must not be blank.");
            }
            synchronized (TRANSACTION_RESOURCE_LOCK) {
                Optional<Object> claimed = resource(PARTICIPANT_FAMILY_KEY);
                if (claimed.isEmpty()) {
                    putResource(PARTICIPANT_FAMILY_KEY, family);
                    return;
                }
                if (family.equals(claimed.get())) {
                    return;
                }
                TxException failure = new TxException("The global transaction is already used by participant family '"
                                                              + claimed.get() + "' and cannot also use '" + family + "'.");
                try {
                    rollbackOnly();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    /**
     * Transaction-scoped participant collection.
     */
    private static final class Participants {

        private final List<GlobalTransactionParticipant> participants = new ArrayList<>();

        private synchronized void add(GlobalTransactionParticipant participant) {
            if (!participants.contains(participant)) {
                participants.add(participant);
            }
        }

        private synchronized void beforeSuspend() {
            notifyParticipants(true);
        }

        private synchronized void afterResume() {
            notifyParticipants(false);
        }

        private void notifyParticipants(boolean suspend) {
            Throwable failure = null;
            for (GlobalTransactionParticipant participant : participants) {
                try {
                    if (suspend) {
                        participant.beforeSuspend();
                    } else {
                        participant.afterResume();
                    }
                } catch (RuntimeException | Error callbackFailure) {
                    if (failure == null) {
                        failure = callbackFailure;
                    } else if (failure != callbackFailure) {
                        failure.addSuppressed(callbackFailure);
                    }
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
        }
    }
}
