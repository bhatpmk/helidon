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

import io.helidon.common.Api;

/**
 * Maps the current JDBC result row to a repository return value.
 *
 * @param <T> mapped value type
 */
@Api.Internal
@FunctionalInterface
public interface JdbcRowMapper<T> {

    /**
     * Map the current result row.
     *
     * @param row current row view
     * @return mapped value
     * @throws SQLException if mapping fails
     */
    T map(JdbcResultSetRowView row) throws SQLException;
}
