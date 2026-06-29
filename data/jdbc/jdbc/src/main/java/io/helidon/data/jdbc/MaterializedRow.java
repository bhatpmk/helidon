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

import io.helidon.data.DataException;

final class MaterializedRow implements JdbcRow {

    private final List<ColumnInfo> columns;
    private final Object[] values;
    private final Map<String, Integer> labels;

    MaterializedRow(List<ColumnInfo> columns, Object[] values) {
        this.columns = List.copyOf(columns);
        this.values = values.clone();
        this.labels = labels(columns);
    }

    @Override
    public int columnCount() {
        return values.length;
    }

    @Override
    public Object value(int index) {
        if (index < 1 || index > values.length) {
            throw new DataException("Column index " + index + " is outside row column range 1.." + values.length);
        }
        return values[index - 1];
    }

    @Override
    public <T> T value(int index, Class<T> type) {
        return JdbcValues.convert(value(index), type);
    }

    @Override
    public Object value(String label) {
        Integer index = labels.get(label);
        if (index == null) {
            index = labels.get(label.toLowerCase(Locale.ROOT));
        }
        if (index == null) {
            throw new DataException("Column label \"" + label + "\" is not present in JDBC row");
        }
        return values[index];
    }

    @Override
    public <T> T value(String label, Class<T> type) {
        return JdbcValues.convert(value(label), type);
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
        return Map.copyOf(result);
    }
}
