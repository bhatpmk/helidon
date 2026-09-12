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
package io.helidon.transaction.spi;

import java.util.Optional;

import javax.transaction.xa.XAResource;

/**
 * Provider-neutral access to a global transaction associated with the current execution context.
 * <p>
 * A handle is valid only while its transaction remains associated with the caller, except that registered
 * synchronization and participant callbacks may retain the handle for their defined lifecycle operations.
 */
public interface GlobalTransaction {

    /**
     * Returns the opaque transaction identity.
     *
     * @return transaction identity
     */
    Object key();

    /**
     * Returns the current transaction status.
     *
     * @return transaction status
     */
    GlobalTransactionStatus status();

    /**
     * Returns a provider value associated with this transaction.
     *
     * @param key provider-private key
     * @return associated value, or empty if there is none
     * @throws NullPointerException if {@code key} is {@code null}
     */
    Optional<Object> resource(Object key);

    /**
     * Associates a provider value with this transaction.
     *
     * @param key provider-private key
     * @param value non-null value
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    void putResource(Object key, Object value);

    /**
     * Enlists an XA resource in this transaction.
     *
     * @param resource XA resource
     * @throws NullPointerException if {@code resource} is {@code null}
     * @throws io.helidon.transaction.TxException if enlistment fails or is rejected
     */
    void enlist(XAResource resource);

    /**
     * Delists an XA resource from this transaction.
     *
     * @param resource XA resource
     * @param reason delist reason
     * @throws NullPointerException if {@code resource} or {@code reason} is {@code null}
     * @throws io.helidon.transaction.TxException if delistment fails or is rejected
     */
    void delist(XAResource resource, GlobalTransactionDelist reason);

    /**
     * Marks this transaction so it cannot commit.
     *
     * @throws io.helidon.transaction.TxException if the transaction manager rejects the operation
     */
    void rollbackOnly();

    /**
     * Registers an interposed completion synchronization.
     *
     * @param synchronization synchronization callback
     * @throws NullPointerException if {@code synchronization} is {@code null}
     * @throws io.helidon.transaction.TxException if registration fails
     */
    void registerSynchronization(GlobalTransactionSynchronization synchronization);

    /**
     * Registers resource association callbacks used by Helidon-managed suspension and resumption.
     *
     * @param participant transaction participant
     * @throws NullPointerException if {@code participant} is {@code null}
     * @throws io.helidon.transaction.TxException if registration fails
     */
    void registerParticipant(GlobalTransactionParticipant participant);

    /**
     * Claims exclusive use by one product participant family.
     * <p>
     * Repeated claims by the same family are allowed. A different claim marks the transaction rollback-only and fails.
     *
     * @param family stable participant family name
     * @throws NullPointerException if {@code family} is {@code null}
     * @throws IllegalArgumentException if {@code family} is blank
     * @throws io.helidon.transaction.TxException if another participant family already claimed the transaction
     */
    void claimParticipantFamily(String family);
}
