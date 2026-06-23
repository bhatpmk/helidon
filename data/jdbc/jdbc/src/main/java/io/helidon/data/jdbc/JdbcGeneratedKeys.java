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

import java.util.Arrays;

import io.helidon.common.Api;

/**
 * Generated-key request used by the internal JDBC execution plan.
 *
 * @param columnNames generated-key column names, an empty array for default columns, or {@code null} for no keys
 */
@Api.Internal
public record JdbcGeneratedKeys(String[] columnNames) {

    /**
     * No generated keys.
     */
    public static final JdbcGeneratedKeys NONE = new JdbcGeneratedKeys(null);

    /**
     * Generated keys using driver default columns.
     */
    public static final JdbcGeneratedKeys DEFAULT = new JdbcGeneratedKeys(new String[0]);

    /**
     * Create a generated-key request.
     *
     * @param columnNames generated-key column names, an empty array for default columns, or {@code null} for no keys
     */
    public JdbcGeneratedKeys {
        columnNames = columnNames == null ? null : columnNames.clone();
    }

    /**
     * Whether generated keys are requested.
     *
     * @return {@code true} when generated keys are requested
     */
    public boolean requested() {
        return columnNames != null;
    }

    /**
     * Whether generated keys should use driver default columns.
     *
     * @return {@code true} when generated keys should use driver default columns
     */
    public boolean defaultColumns() {
        return columnNames != null && columnNames.length == 0;
    }

    /**
     * Generated-key column names.
     *
     * @return generated-key column names, an empty array for driver default columns, or {@code null} when keys are not requested
     */
    @Override
    public String[] columnNames() {
        return columnNames == null ? null : columnNames.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JdbcGeneratedKeys that
                && Arrays.equals(this.columnNames, that.columnNames);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(columnNames);
    }

}
