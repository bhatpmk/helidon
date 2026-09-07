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

/**
 * One of possibly many {@linkplain JdbcResults results} of {@linkplain java.sql.Statement#execute(String) executing a
 * <code>Statement</code>} or {@linkplain java.sql.PreparedStatement#execute() executing a
 * <code>PreparedStatement</code> (including <code>CallableStatement</code>s)}.
 *
 * <p>As with all constructs related to JDBC, a {@link JdbcResult} is not necessarily safe for concurrent use by
 * multiple threads unless explicitly noted.</p>
 *
 * @see JdbcResultSet
 * @see JdbcUpdateCount
 * @see JdbcOutValues
 * @see JdbcBatchExecutionResults
 * @see JdbcResults
 * @see java.sql.Statement#execute(String)
 * @see java.sql.PreparedStatement#execute()
 * @see java.sql.Statement#executeLargeBatch()
 */
public sealed interface JdbcResult permits JdbcResultSet, JdbcUpdateCount, JdbcOutValues, JdbcBatchExecutionResults {
}
