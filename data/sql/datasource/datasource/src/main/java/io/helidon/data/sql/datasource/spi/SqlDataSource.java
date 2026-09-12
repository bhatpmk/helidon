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
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.service.registry.Service;

/**
 * Explicit capabilities of a registered SQL data source.
 * <p>
 * The descriptor separates ordinary JDBC connection acquisition from XA
 * acquisition. Consumers must not infer XA or transaction-manager integration
 * from the implementation class of {@link #dataSource()}.
 */
@Service.Contract
public interface SqlDataSource {

    /**
     * Creates a local-only descriptor.
     *
     * @param dataSource JDBC data source
     * @param transactionIdentity stable in-process identity shared by descriptors for the same source
     * @return SQL data source descriptor
     * @throws NullPointerException if any argument is {@code null}
     */
    static SqlDataSource create(DataSource dataSource, Object transactionIdentity) {
        Objects.requireNonNull(dataSource, "The JDBC data source must not be null.");
        Objects.requireNonNull(transactionIdentity, "The data source transaction identity must not be null.");
        return new SqlDataSourceImpl(dataSource, transactionIdentity, Optional.empty());
    }

    /**
     * Creates an XA-capable descriptor.
     *
     * @param dataSource JDBC data source
     * @param transactionIdentity stable in-process identity shared by descriptors for the same source
     * @param xa XA capability
     * @return SQL data source descriptor
     * @throws NullPointerException if any argument is {@code null}
     */
    static SqlDataSource create(DataSource dataSource,
                                Object transactionIdentity,
                                XaDataSourceCapability xa) {
        Objects.requireNonNull(dataSource, "The JDBC data source must not be null.");
        Objects.requireNonNull(transactionIdentity, "The data source transaction identity must not be null.");
        Objects.requireNonNull(xa, "The XA data source capability must not be null.");
        return new SqlDataSourceImpl(dataSource, transactionIdentity, Optional.of(xa));
    }

    /**
     * Returns the ordinary JDBC data source.
     *
     * @return JDBC data source
     */
    DataSource dataSource();

    /**
     * Returns the stable in-process resource identity.
     * <p>
     * Multiple clients that resolve the same registered SQL source receive the
     * same identity and therefore share one transaction-scoped association.
     *
     * @return transaction identity
     */
    Object transactionIdentity();

    /**
     * Returns the explicit XA capability, when configured.
     *
     * @return XA capability
     */
    Optional<XaDataSourceCapability> xa();
}
