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

import io.helidon.service.registry.Service;

/**
 * Access to the global transaction associated with the current execution context.
 */
@Service.Contract
public interface GlobalTransactionSupport {

    /**
     * Returns the associated global transaction.
     * <p>
     * An unusable transaction state is returned as a handle with that state and is not treated as transaction absence.
     *
     * @return current global transaction, or empty when none is associated
     */
    Optional<GlobalTransaction> current();
}
