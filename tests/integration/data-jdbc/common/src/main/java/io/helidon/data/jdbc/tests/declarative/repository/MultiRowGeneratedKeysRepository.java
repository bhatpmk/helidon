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
package io.helidon.data.jdbc.tests.declarative.repository;

import java.util.List;
import java.util.Optional;

import io.helidon.data.Data;
import io.helidon.data.jdbc.Jdbc;
import io.helidon.data.jdbc.tests.application.MapperFailureContact;
import io.helidon.data.jdbc.tests.declarative.MultiRowFailingGeneratedKeyMapper;
import io.helidon.transaction.Tx;

/**
 * Generated repository methods for MySQL multi-row generated-key behavior.
 */
@Data.Repository
@Data.Provider("jdbc")
public interface MultiRowGeneratedKeysRepository {

    /**
     * Inserts two contacts and returns their keys in encounter order.
     *
     * @param first first contact name
     * @param second second contact name
     * @return generated identifiers
     */
    @Jdbc.Statement("INSERT INTO CONTACT (NAME) VALUES (:first), (:second)")
    @Jdbc.GeneratedKeys("id")
    List<Long> insertList(String first, String second);

    /**
     * Inserts two contacts and requests singular cardinality.
     *
     * @param first first contact name
     * @param second second contact name
     * @return unreachable generated identifier
     */
    @Jdbc.Statement("INSERT INTO CONTACT (NAME) VALUES (:first), (:second)")
    @Jdbc.GeneratedKeys("id")
    long insertOne(String first, String second);

    /**
     * Inserts two contacts and requests optional cardinality.
     *
     * @param first first contact name
     * @param second second contact name
     * @return unreachable generated identifier
     */
    @Jdbc.Statement("INSERT INTO CONTACT (NAME) VALUES (:first), (:second)")
    @Jdbc.GeneratedKeys("id")
    Optional<Long> insertOptional(String first, String second);

    /**
     * Inserts two contacts and fails while mapping the second generated key.
     *
     * @param first first contact name
     * @param second second contact name
     * @return no result because mapping fails
     */
    @Jdbc.Statement("INSERT INTO CONTACT (NAME) VALUES (:first), (:second)")
    @Jdbc.GeneratedKeys("id")
    @Jdbc.RowMapper(MultiRowFailingGeneratedKeyMapper.class)
    List<MapperFailureContact> insertWithMapperFailure(String first, String second);

    /**
     * Inserts two contacts transactionally and fails while mapping the second generated key.
     *
     * @param first first contact name
     * @param second second contact name
     * @return no result because mapping fails
     */
    @Tx.Required
    @Jdbc.Statement("INSERT INTO CONTACT (NAME) VALUES (:first), (:second)")
    @Jdbc.GeneratedKeys("id")
    @Jdbc.RowMapper(MultiRowFailingGeneratedKeyMapper.class)
    List<MapperFailureContact> insertWithTransactionalMapperFailure(String first, String second);
}
