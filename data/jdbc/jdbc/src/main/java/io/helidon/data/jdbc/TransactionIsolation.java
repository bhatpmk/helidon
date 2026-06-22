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

/**
 * An {@code enum} whose constants correspond to the defined transaction isolation {@code int} constants of the {@link Connection}
 * class.
 */
@io.helidon.common.Api.Internal
public enum TransactionIsolation {

    /**
     * Represents {@link Connection#TRANSACTION_NONE}.
     */
    NONE(Connection.TRANSACTION_NONE),

    /**
     * Represents {@link Connection#TRANSACTION_READ_COMMITTED}.
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /**
     * Represents {@link Connection#TRANSACTION_READ_UNCOMMITTED}.
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /**
     * Represents {@link Connection#TRANSACTION_REPEATABLE_READ}.
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /**
     * Represents {@link Connection#TRANSACTION_SERIALIZABLE}.
     */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int value;

    TransactionIsolation(int transactionIsolation) {
        this.value = switch (transactionIsolation) {
        case Connection.TRANSACTION_NONE,
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_READ_UNCOMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE -> transactionIsolation;
        default -> throw new IllegalArgumentException("transactionIsolation: " + transactionIsolation);
        };
    }

    /**
     * Returns the appropriate transaction isolation constant value.
     *
     * @return the appropriate transaction isolation constant value
     * @see Connection#TRANSACTION_NONE
     * @see Connection#TRANSACTION_READ_COMMITTED
     * @see Connection#TRANSACTION_READ_UNCOMMITTED
     * @see Connection#TRANSACTION_REPEATABLE_READ
     * @see Connection#TRANSACTION_SERIALIZABLE
     */
    public int value() {
        return this.value;
    }

    /**
     * Returns a {@link TransactionIsolation} appropriate for the supplied {@code transactionIsolation} constant, which
     * must be either {@link Connection#TRANSACTION_NONE}, {@link Connection#TRANSACTION_READ_COMMITTED}, {@link
     * Connection#TRANSACTION_READ_UNCOMMITTED}, {@link Connection#TRANSACTION_REPEATABLE_READ}, or {@link
     * Connection#TRANSACTION_SERIALIZABLE}.
     *
     * @param transactionIsolation a transaction isolation constant
     * @return a non-{@code null} {@link TransactionIsolation} appropriate for the supplied {@code transactionIsolation}
     * constant
     * @exception IllegalArgumentException if {@code transactionIsolation} is not a valid value
     * @see Connection#TRANSACTION_NONE
     * @see Connection#TRANSACTION_READ_COMMITTED
     * @see Connection#TRANSACTION_READ_UNCOMMITTED
     * @see Connection#TRANSACTION_REPEATABLE_READ
     * @see Connection#TRANSACTION_SERIALIZABLE
     */
    public static TransactionIsolation of(int transactionIsolation) {
        return switch (transactionIsolation) {
        case Connection.TRANSACTION_NONE -> NONE;
        case Connection.TRANSACTION_READ_COMMITTED -> READ_COMMITTED;
        case Connection.TRANSACTION_READ_UNCOMMITTED -> READ_UNCOMMITTED;
        case Connection.TRANSACTION_REPEATABLE_READ -> REPEATABLE_READ;
        case Connection.TRANSACTION_SERIALIZABLE -> SERIALIZABLE;
        default -> throw new IllegalArgumentException("transactionIsolation: " + transactionIsolation);
        };
    }

}
