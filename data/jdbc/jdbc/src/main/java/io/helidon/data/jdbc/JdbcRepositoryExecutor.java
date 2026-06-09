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
import java.util.Optional;

import io.helidon.service.registry.Service;

/**
 * Low level runtime contract used by generated JDBC repository implementations.
 * <p>
 * Generated repositories should remain simple: they pass SQL, the expected result type, and generated method
 * arguments. The runtime owns the JDBC lifecycle. This keeps generated source readable and leaves room for later
 * runtime improvements, such as statement planning and transaction scoped connections, without changing generated
 * repository method bodies.
 */
@Service.Contract
public interface JdbcRepositoryExecutor extends AutoCloseable {

    /**
     * Execute a SQL query and map all rows to the requested result type.
     *
     * @param sql SQL statement supplied through {@code @Data.Query}
     * @param resultType result row type
     * @param parameters named method parameters
     * @param <T> result row type
     * @return mapped rows
     */
    <T> List<T> queryList(String sql, Class<T> resultType, JdbcParameters parameters);

    /**
     * Execute a SQL query that returns zero or one row.
     *
     * @param sql SQL statement supplied through {@code @Data.Query}
     * @param resultType result row type
     * @param parameters named method parameters
     * @param <T> result row type
     * @return optional mapped row
     */
    <T> Optional<T> queryOptional(String sql, Class<T> resultType, JdbcParameters parameters);

    /**
     * Execute a SQL query that returns exactly one row.
     *
     * @param sql SQL statement supplied through {@code @Data.Query}
     * @param resultType result row type
     * @param parameters named method parameters
     * @param <T> result row type
     * @return mapped row
     */
    <T> T queryOne(String sql, Class<T> resultType, JdbcParameters parameters);

    /**
     * Execute DML SQL and return the update count.
     *
     * @param sql SQL statement supplied through {@code @Data.Query}
     * @param parameters named method parameters
     * @return update count
     */
    long update(String sql, JdbcParameters parameters);

    @Override
    default void close() {
    }
}
