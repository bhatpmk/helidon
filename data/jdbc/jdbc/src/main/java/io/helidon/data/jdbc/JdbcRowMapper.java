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
 * Maps one JDBC row snapshot to an application value.
 * <p>
 * Materialized terminals invoke the mapper over detached rows after JDBC resources close. Streaming terminals invoke
 * it as each row is read while the provider-owned streaming scope is open. A mapper must return an application-owned
 * value and must not retain resource-backed JDBC values.
 *
 * @param <T> mapped type
 */
@FunctionalInterface
public interface JdbcRowMapper<T> {

    /**
     * Map a row snapshot.
     *
     * @param row row to map
     * @return mapped value
     */
    T map(JdbcRow row);
}
