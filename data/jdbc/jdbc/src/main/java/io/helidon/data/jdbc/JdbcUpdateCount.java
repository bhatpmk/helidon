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
 * A {@link JdbcResult} {@linkplain #updateCount() providing access to an update count}.
 *
 * @see #updateCount()
 * @see JdbcResult
 * @see java.sql.Statement#getLargeUpdateCount()
 * @see java.sql.Statement#getUpdateCount()
 */
@io.helidon.common.Api.Internal
public non-sealed interface JdbcUpdateCount extends JdbcResult {

    /**
     * Returns the update count represented by this {@link JdbcUpdateCount}.
     *
     * @return an update count
     * @throws SQLException if a database error occurs
     * @see java.sql.Statement#getLargeUpdateCount()
     * @see java.sql.Statement#getUpdateCount()
     */
    long updateCount() throws SQLException;

}
