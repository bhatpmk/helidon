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

import java.util.List;
import java.util.Objects;

record JdbcOperation(SqlKind kind,
                     String sql,
                     List<JdbcParameter> parameters,
                     int fetchSize,
                     GeneratedKeysRequest generatedKeys) {

    JdbcOperation {
        Objects.requireNonNull(kind, "SQL kind must not be null");
        Objects.requireNonNull(sql, "SQL must not be null");
        Objects.requireNonNull(parameters, "Parameters must not be null");
        Objects.requireNonNull(generatedKeys, "Generated keys request must not be null");
        if (fetchSize < 0) {
            throw new IllegalArgumentException("Fetch size must not be negative");
        }
        parameters = List.copyOf(parameters);
    }

    static JdbcOperation query(String sql, List<JdbcParameter> parameters) {
        return query(sql, parameters, 0);
    }

    static JdbcOperation query(String sql, List<JdbcParameter> parameters, int fetchSize) {
        return new JdbcOperation(SqlKind.QUERY, sql, parameters, fetchSize, GeneratedKeysRequest.none());
    }

    static JdbcOperation update(String sql, List<JdbcParameter> parameters) {
        return update(sql, parameters, 0);
    }

    static JdbcOperation update(String sql, List<JdbcParameter> parameters, int fetchSize) {
        return update(sql, parameters, fetchSize, GeneratedKeysRequest.none());
    }

    static JdbcOperation update(String sql,
                                List<JdbcParameter> parameters,
                                int fetchSize,
                                GeneratedKeysRequest generatedKeys) {
        return new JdbcOperation(SqlKind.UPDATE, sql, parameters, fetchSize, generatedKeys);
    }
}
