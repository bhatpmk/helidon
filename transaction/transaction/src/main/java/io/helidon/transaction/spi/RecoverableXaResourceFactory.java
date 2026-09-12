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
 * Recreates access to an XA resource manager for transaction recovery.
 */
public interface RecoverableXaResourceFactory {

    /**
     * Returns the stable name identifying this resource manager in the transaction-manager domain.
     *
     * @return stable recovery name
     */
    String name();

    /**
     * Opens a provider-owned resource for recovery.
     *
     * @return recovery resource
     * @throws io.helidon.transaction.TxException if the resource cannot be opened
     */
    RecoverableXaResource open();
}
