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
 * Read-only materialized JDBC row view used by generated repository mappers.
 * <p>
 * Indexes are one-based, matching JDBC column indexes. Implementations do not expose live {@code ResultSet} state.
 */
public interface JdbcRow {

    /**
     * Number of columns in this row.
     *
     * @return column count
     */
    int columnCount();

    /**
     * Return a column value by one-based column index.
     *
     * @param index one-based column index
     * @return column value
     */
    Object value(int index);

    /**
     * Return a converted column value by one-based column index.
     *
     * @param index one-based column index
     * @param type  target type
     * @param <T>   target type
     * @return converted column value
     */
    <T> T value(int index, Class<T> type);

    /**
     * Return a column value by column label.
     *
     * @param label column label
     * @return column value
     */
    Object value(String label);

    /**
     * Return a converted column value by column label.
     *
     * @param label column label
     * @param type  target type
     * @param <T>   target type
     * @return converted column value
     */
    <T> T value(String label, Class<T> type);
}
