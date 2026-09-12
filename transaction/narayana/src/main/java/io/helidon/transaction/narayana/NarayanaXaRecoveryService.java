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
package io.helidon.transaction.narayana;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.transaction.xa.XAResource;

import io.helidon.service.registry.Service;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.GlobalTransactionRecovery;
import io.helidon.transaction.spi.RecoverableXaResource;
import io.helidon.transaction.spi.RecoverableXaResourceFactory;

import com.arjuna.ats.internal.jta.recovery.arjunacore.XARecoveryModule;
import com.arjuna.ats.jta.recovery.XAResourceRecoveryHelper;

/**
 * Narayana adapter for provider-owned XA recovery resources.
 */
@Service.Singleton
final class NarayanaXaRecoveryService implements GlobalTransactionRecovery {
    private final Map<String, RegisteredFactory> factories = new HashMap<>();
    private XARecoveryModule recoveryModule;

    NarayanaXaRecoveryService() {
    }

    @Override
    public synchronized Registration register(RecoverableXaResourceFactory factory) {
        Objects.requireNonNull(factory, "The recoverable XA resource factory must not be null.");
        String name = Objects.requireNonNull(factory.name(), "The XA recovery resource name must not be null.");
        if (name.isBlank()) {
            throw new IllegalArgumentException("The XA recovery resource name must not be blank.");
        }

        XARecoveryModule module = recoveryModule();
        RegisteredFactory registered = factories.get(name);
        if (registered == null) {
            registered = new RegisteredFactory(factory);
            module.addXAResourceRecoveryHelper(registered);
            factories.put(name, registered);
        } else if (registered.factory() != factory) {
            throw new TxException("XA recovery resource name '" + name + "' is already registered by another data source.");
        }
        registered.retain();
        RegisteredFactory retained = registered;
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (NarayanaXaRecoveryService.this) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    if (retained.release()) {
                        factories.remove(name);
                        module.removeXAResourceRecoveryHelper(retained);
                        retained.close();
                    }
                }
            }
        };
    }

    /**
     * Deregisters recovery helpers before their configured data sources are
     * released.
     */
    @Service.PreDestroy
    synchronized void shutdown() {
        Throwable failure = null;
        for (RegisteredFactory registered : factories.values()) {
            try {
                if (recoveryModule != null) {
                    recoveryModule.removeXAResourceRecoveryHelper(registered);
                }
                registered.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        factories.clear();
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    private XARecoveryModule recoveryModule() {
        if (recoveryModule == null) {
            recoveryModule = XARecoveryModule.getRegisteredXARecoveryModule();
            if (recoveryModule == null) {
                throw new TxException("Narayana XA recovery is not configured with an XA recovery module.");
            }
        }
        return recoveryModule;
    }

    /**
     * One registered recovery factory and its scan-lifetime resource.
     */
    private static final class RegisteredFactory implements XAResourceRecoveryHelper {
        private final RecoverableXaResourceFactory factory;
        private RecoverableXaResource handle;
        private int references;

        private RegisteredFactory(RecoverableXaResourceFactory factory) {
            this.factory = factory;
        }

        @Override
        public boolean initialise(String configuration) {
            // Registration is programmatic, so Narayana has no textual helper configuration to apply.
            return true;
        }

        @Override
        public synchronized XAResource[] getXAResources() {
            if (handle != null) {
                try {
                    handle.close();
                } finally {
                    handle = null;
                }
            }
            // A fresh handle for each recovery scan prevents a failed physical
            // recovery connection from poisoning every later scan.
            RecoverableXaResource opened = Objects.requireNonNull(factory.open(),
                                                                  "The XA recovery handle must not be null.");
            XAResource resource;
            try {
                resource = Objects.requireNonNull(opened.resource(), "The XA recovery resource must not be null.");
            } catch (RuntimeException | Error failure) {
                try {
                    opened.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
            handle = opened;
            return new XAResource[] {resource};
        }

        private RecoverableXaResourceFactory factory() {
            return factory;
        }

        private void retain() {
            references++;
        }

        private boolean release() {
            references--;
            return references == 0;
        }

        private synchronized void close() {
            if (handle == null) {
                return;
            }
            try {
                handle.close();
            } finally {
                handle = null;
            }
        }
    }
}
