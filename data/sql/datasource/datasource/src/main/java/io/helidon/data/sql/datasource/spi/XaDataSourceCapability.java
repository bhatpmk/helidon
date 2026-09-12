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

import java.util.Objects;

import javax.sql.XADataSource;

import io.helidon.transaction.spi.RecoverableXaResourceFactory;

/**
 * XA and recovery capability of a SQL data source.
 */
public interface XaDataSourceCapability {

    /**
     * Creates an XA capability whose recovery connections are obtained from
     * the supplied XA data source.
     *
     * @param dataSource XA data source
     * @param recoveryName stable, non-secret recovery resource name
     * @return XA capability
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the recovery name is blank
     */
    static XaDataSourceCapability create(XADataSource dataSource, String recoveryName) {
        Objects.requireNonNull(dataSource, "The XA data source must not be null.");
        Objects.requireNonNull(recoveryName, "The XA recovery name must not be null.");
        if (recoveryName.isBlank()) {
            throw new IllegalArgumentException("The XA recovery name must not be blank.");
        }
        return new XaDataSourceCapabilityImpl(dataSource, recoveryName);
    }

    /**
     * Returns the XA data source used for live transaction associations.
     *
     * @return XA data source
     */
    XADataSource dataSource();

    /**
     * Returns the stable, non-secret recovery resource name.
     *
     * @return recovery name
     */
    String recoveryName();

    /**
     * Returns a factory that opens an independent XA handle for recovery.
     *
     * @return recovery resource factory
     */
    RecoverableXaResourceFactory recoveryFactory();

}
