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
 * Detached rows from one JDBC result set or cursor.
 * <p>
 * A row set combines a shared {@link RowLayout} with the materialized row values that use that layout. It is the
 * payload stored by direct row results, generated-key results, and callable cursor attachments.
 *
 * The explicit fields make the ownership boundary clear: a row set owns detached values and shares one immutable
 * layout across them.
 */
final class RowSet {
    private final RowLayout layout;
    private final List<MaterializedRow> rows;

    RowSet(List<ColumnInfo> columns, List<MaterializedRow> rows) {
        this(new RowLayout(columns), rows);
    }

    RowSet(RowLayout layout, List<MaterializedRow> rows) {
        Objects.requireNonNull(layout, "Row layout must not be null");
        Objects.requireNonNull(rows, "Rows must not be null");
        this.layout = layout;
        this.rows = List.copyOf(rows);
    }

    RowLayout layout() {
        return layout;
    }

    List<MaterializedRow> rows() {
        return rows;
    }

    List<ColumnInfo> columns() {
        return layout.columns();
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    int size() {
        return rows.size();
    }
}
