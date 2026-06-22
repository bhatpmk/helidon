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

import java.sql.SQLException;

/**
 * A {@link JdbcResult} providing access to the results of {@linkplain java.sql.Statement#executeBatch() a batch
 * execution}.
 *
 * @see #batchExecutionResults()
 */
@io.helidon.common.Api.Internal
public non-sealed interface JdbcBatchExecutionResults extends JdbcResult {

    /**
     * Returns the results of the batch execution.
     *
     * @return a non-{@code null} {@code long} array containing a mix of update counts and status indicators
     * @throws SQLException if a database error occurs
     * @see java.sql.Statement#executeLargeBatch()
     * @see java.sql.Statement#EXECUTE_FAILED
     * @see java.sql.Statement#SUCCESS_NO_INFO
     */
    long[] batchExecutionResults() throws SQLException;

}
