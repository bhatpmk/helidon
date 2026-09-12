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

/**
 * Immutable SQL data source descriptor.
 */
final class SqlDataSourceImpl implements SqlDataSource {
    private final DataSource delegate;
    private final Object transactionIdentity;
    private final Optional<XaDataSourceCapability> xa;

    SqlDataSourceImpl(DataSource delegate,
                      Object transactionIdentity,
                      Optional<XaDataSourceCapability> xa) {
        this.delegate = Objects.requireNonNull(delegate, "The JDBC data source must not be null.");
        this.transactionIdentity = Objects.requireNonNull(transactionIdentity,
                                                          "The data source transaction identity must not be null.");
        this.xa = Objects.requireNonNull(xa, "The XA data source capability optional must not be null.");
    }

    @Override
    public DataSource dataSource() {
        return delegate;
    }

    @Override
    public Object transactionIdentity() {
        return transactionIdentity;
    }

    @Override
    public Optional<XaDataSourceCapability> xa() {
        return xa;
    }
}
