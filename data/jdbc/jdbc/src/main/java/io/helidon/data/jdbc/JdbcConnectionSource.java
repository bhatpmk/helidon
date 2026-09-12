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

import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import io.helidon.data.sql.datasource.spi.SqlDataSource;
import io.helidon.data.sql.datasource.spi.XaDataSourceCapability;

/**
 * Fully resolved connection and transaction capabilities of one JDBC client.
 *
 * @param dataSource ordinary operation data source
 * @param transactionIdentity stable resource identity
 * @param xa explicit XA capability
 * @param participation configured transaction participation
 */
record JdbcConnectionSource(DataSource dataSource,
                            Object transactionIdentity,
                            Optional<XaDataSourceCapability> xa,
                            TransactionParticipation participation) {

    JdbcConnectionSource {
        Objects.requireNonNull(dataSource, "The JDBC data source must not be null.");
        Objects.requireNonNull(transactionIdentity, "The JDBC transaction identity must not be null.");
        Objects.requireNonNull(xa, "The JDBC XA capability optional must not be null.");
        Objects.requireNonNull(participation, "The JDBC transaction participation must not be null.");
    }

    /**
     * Creates a resolved source from an explicit SQL data source descriptor.
     *
     * @param descriptor SQL data source descriptor
     * @param participation participation policy
     * @return resolved connection source
     */
    static JdbcConnectionSource create(SqlDataSource descriptor, TransactionParticipation participation) {
        return new JdbcConnectionSource(descriptor.dataSource(),
                                        descriptor.transactionIdentity(),
                                        descriptor.xa(),
                                        participation);
    }

    /**
     * Creates a local-only source around a compatibility data source.
     *
     * @param dataSource compatibility data source
     * @param participation participation policy
     * @return resolved connection source
     */
    static JdbcConnectionSource create(DataSource dataSource, TransactionParticipation participation) {
        if (dataSource instanceof SqlDataSource descriptor) {
            return create(descriptor, participation);
        }
        Object identity = dataSource instanceof JdbcTransactionConnectionManager.IdentitySource source
                ? source.transactionIdentity()
                : dataSource;
        return new JdbcConnectionSource(dataSource, identity, Optional.empty(), participation);
    }
}
