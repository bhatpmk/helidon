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
 * Detached metadata for one JDBC result-set column.
 * <p>
 * Column metadata is copied while the {@link java.sql.ResultSet} is open and then shared by all materialized rows in
 * the same {@link RowSet}. Both label and name are retained because JDBC drivers can expose aliases and physical names
 * differently.
 *
 * The class is immutable so the metadata can be shared by every materialized row in a row set.
 */
final class ColumnInfo {
    private final String label;
    private final String name;
    private final int jdbcType;
    private final String typeName;
    private final boolean nullable;

    ColumnInfo(String label, String name, int jdbcType, String typeName, boolean nullable) {
        this.label = label;
        this.name = name;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.nullable = nullable;
    }

    String label() {
        return label;
    }

    String name() {
        return name;
    }

    int jdbcType() {
        return jdbcType;
    }

    String typeName() {
        return typeName;
    }

    boolean nullable() {
        return nullable;
    }
}
