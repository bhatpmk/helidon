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

/**
 * Immutable JDBC execution plan for one repository SQL statement.
 * <p>
 * The current generated repository contract still passes SQL as a string, so the executor creates this plan at
 * runtime. Production code should generate one plan per repository method at build time. The important part is
 * that named parameters are resolved once into a stable JDBC SQL string and an ordered bind list.
 */
record JdbcStatementPlan(String originalSql, String jdbcSql, List<String> parameterNames) {

    static JdbcStatementPlan create(String sql) {
        Objects.requireNonNull(sql, "SQL statement must not be null");
        NamedStatementParser parser = new NamedStatementParser(sql);
        String jdbcSql = parser.convert();
        return new JdbcStatementPlan(sql, jdbcSql, List.copyOf(parser.namesOrder()));
    }
}
