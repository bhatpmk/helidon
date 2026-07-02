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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.helidon.data.DataException;

/**
 * Shared column layout for all rows in one materialized row set.
 * <p>
 * The layout owns the immutable column metadata and lookup map used by {@link MaterializedRow}. Keeping this data
 * outside individual rows avoids repeating label maps and column metadata for every row returned by a query.
 *
 * The explicit fields make the shared layout and its lookup index visible without repeating metadata in each row.
 */
final class RowLayout {
    private final List<ColumnInfo> columns;
    private final Map<String, Integer> labels;

    RowLayout(List<ColumnInfo> columns) {
        this(columns, labels(columns));
    }

    RowLayout(List<ColumnInfo> columns, Map<String, Integer> labels) {
        Objects.requireNonNull(columns, "Columns must not be null");
        Objects.requireNonNull(labels, "Labels must not be null");
        this.columns = List.copyOf(columns);
        this.labels = Map.copyOf(labels);
    }

    List<ColumnInfo> columns() {
        return columns;
    }

    Map<String, Integer> labels() {
        return labels;
    }

    int columnCount() {
        return columns.size();
    }

    int columnIndex(String label) {
        Integer index = labels.get(label);
        if (index == null) {
            index = labels.get(label.toLowerCase(Locale.ROOT));
        }
        if (index == null) {
            throw new DataException("Column label \"" + label + "\" is not present in JDBC row");
        }
        return index;
    }

    String columnLabel(int index) {
        if (index >= columns.size()) {
            return "column" + (index + 1);
        }
        String label = columns.get(index).label();
        return label == null || label.isBlank() ? "column" + (index + 1) : label;
    }

    private static Map<String, Integer> labels(List<ColumnInfo> columns) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String label = columns.get(i).label();
            if (label != null && !label.isBlank()) {
                result.putIfAbsent(label, i);
                result.putIfAbsent(label.toLowerCase(Locale.ROOT), i);
            }
            String name = columns.get(i).name();
            if (name != null && !name.isBlank()) {
                result.putIfAbsent(name, i);
                result.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
            }
        }
        return result;
    }
}
