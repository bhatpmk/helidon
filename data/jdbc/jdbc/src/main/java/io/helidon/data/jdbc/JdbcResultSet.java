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

import java.sql.ResultSet;
import java.sql.SQLException;

import io.helidon.data.jdbc.function.JdbcAutoCloseable;

/**
 * A {@link JdbcResult} {@linkplain #resultSet() providing access to a <code>ResultSet</code>}.
 *
 * @see #resultSet()
 * @see JdbcResult
 * @see ResultSet
 * @see java.sql.Statement#getResultSet()
 */
public non-sealed interface JdbcResultSet extends JdbcResult, JdbcAutoCloseable, JdbcWarningsBearing {

    /**
     * Returns the non-{@code null} {@link ResultSet} represented by this {@link JdbcResultSet}.
     *
     * @return a non-{@code null} {@link ResultSet}
     * @throws SQLException if a database error occurs
     * @see java.sql.Statement#getResultSet()
     */
    ResultSet resultSet() throws SQLException;

}
