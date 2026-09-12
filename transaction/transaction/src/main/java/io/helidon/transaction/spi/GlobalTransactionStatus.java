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

/**
 * Status of a global transaction associated with the current execution context.
 */
public enum GlobalTransactionStatus {
    /**
     * The transaction can accept work.
     */
    ACTIVE,
    /**
     * The transaction can no longer commit.
     */
    MARKED_ROLLBACK,
    /**
     * The transaction is preparing its resources.
     */
    PREPARING,
    /**
     * The transaction resources are prepared.
     */
    PREPARED,
    /**
     * The transaction is committing.
     */
    COMMITTING,
    /**
     * The transaction committed.
     */
    COMMITTED,
    /**
     * The transaction is rolling back.
     */
    ROLLING_BACK,
    /**
     * The transaction rolled back.
     */
    ROLLED_BACK,
    /**
     * The transaction manager cannot currently determine the status.
     */
    UNKNOWN
}
