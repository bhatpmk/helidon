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

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;

final class JdbcDataSourceConnectionProvider implements JdbcConnectionProvider {

    private final DataSource dataSource;

    JdbcDataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

}
