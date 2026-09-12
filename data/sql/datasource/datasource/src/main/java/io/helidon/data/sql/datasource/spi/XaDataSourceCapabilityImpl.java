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
package io.helidon.data.sql.datasource.spi;

import java.sql.SQLException;
import java.util.Objects;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;

import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.RecoverableXaResource;
import io.helidon.transaction.spi.RecoverableXaResourceFactory;

/**
 * Immutable XA data source capability implementation.
 */
final class XaDataSourceCapabilityImpl implements XaDataSourceCapability {
    private final XADataSource dataSource;
    private final String recoveryName;
    private final RecoverableXaResourceFactory recoveryFactory;

    XaDataSourceCapabilityImpl(XADataSource dataSource, String recoveryName) {
        this.dataSource = Objects.requireNonNull(dataSource, "The XA data source must not be null.");
        this.recoveryName = Objects.requireNonNull(recoveryName, "The XA recovery name must not be null.");
        this.recoveryFactory = new RecoveryFactory(dataSource, recoveryName);
    }

    @Override
    public XADataSource dataSource() {
        return dataSource;
    }

    @Override
    public String recoveryName() {
        return recoveryName;
    }

    @Override
    public RecoverableXaResourceFactory recoveryFactory() {
        return recoveryFactory;
    }

    /**
     * Recovery factory backed by one configured XA data source.
     */
    private static final class RecoveryFactory implements RecoverableXaResourceFactory {
        private final XADataSource dataSource;
        private final String name;

        private RecoveryFactory(XADataSource dataSource, String name) {
            this.dataSource = dataSource;
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public RecoverableXaResource open() {
            XAConnection connection;
            try {
                connection = dataSource.getXAConnection();
            } catch (SQLException | RuntimeException failure) {
                throw new TxException("Failed to acquire XA recovery resource '" + name + "'.", failure);
            }
            try {
                XAResource resource = Objects.requireNonNull(connection.getXAResource(),
                                                             "The XA recovery resource must not be null.");
                return new RecoveryResource(connection, resource);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw new TxException("Failed to initialize XA recovery resource '" + name + "'.", failure);
            }
        }
    }

    /**
     * Recovery handle retaining the XA connection that owns the XA resource.
     */
    private static final class RecoveryResource implements RecoverableXaResource {
        private final XAConnection connection;
        private final XAResource resource;

        private RecoveryResource(XAConnection connection, XAResource resource) {
            this.connection = connection;
            this.resource = resource;
        }

        @Override
        public XAResource resource() {
            return resource;
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                throw new TxException("Failed to close an XA recovery connection.", failure);
            }
        }
    }
}
