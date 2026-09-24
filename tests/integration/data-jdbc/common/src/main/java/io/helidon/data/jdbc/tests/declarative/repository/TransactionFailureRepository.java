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

import io.helidon.data.Data;
import io.helidon.data.jdbc.Jdbc;
import io.helidon.data.jdbc.tests.application.MapperFailureContact;
import io.helidon.data.jdbc.tests.application.TestSql;
import io.helidon.data.jdbc.tests.application.transaction.TransactionSql;
import io.helidon.data.jdbc.tests.declarative.ThrowingContactMapper;
import io.helidon.transaction.Tx;

/**
 * Generated repository methods that expose transaction failure boundaries.
 */
@Data.Repository
@Data.Provider("jdbc")
public interface TransactionFailureRepository {

    /**
     * Inserts in a new transaction.
     *
     * @param name contact name
     * @return generated identifier
     */
    @Tx.New
    @Jdbc.Statement(TestSql.INSERT_WITHOUT_EMAIL)
    @Jdbc.GeneratedKeys("id")
    long insertNew(String name);

    /**
     * Inserts and fails in its generated-key mapper in a new transaction.
     *
     * @param name contact name
     * @return no result because mapping fails
     */
    @Tx.New
    @Jdbc.Statement(TestSql.INSERT_WITHOUT_EMAIL)
    @Jdbc.GeneratedKeys("id")
    @Jdbc.RowMapper(ThrowingContactMapper.class)
    MapperFailureContact failNew(String name);

    /**
     * Executes invalid SQL while requiring an existing transaction.
     *
     * @return no rows because execution fails
     */
    @Tx.Mandatory
    @Jdbc.Statement(TransactionSql.INVALID_QUERY)
    @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
    List<String> failMandatory();

    /**
     * Executes invalid SQL while joining a transaction when one exists.
     *
     * @return no rows because execution fails
     */
    @Tx.Supported
    @Jdbc.Statement(TransactionSql.INVALID_QUERY)
    @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
    List<String> failSupportedQuery();
}
