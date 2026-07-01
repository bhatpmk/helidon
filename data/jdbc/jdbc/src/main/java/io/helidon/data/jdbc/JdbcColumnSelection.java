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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.helidon.data.DataException;

record JdbcColumnSelection(List<Integer> indexes, List<String> labels) {

    static final JdbcColumnSelection ALL = new JdbcColumnSelection(List.of(), List.of());

    JdbcColumnSelection {
        indexes = List.copyOf(indexes);
        labels = List.copyOf(labels);
        if (!indexes.isEmpty() && !labels.isEmpty()) {
            throw new IllegalArgumentException("Column selection cannot mix indexes and labels");
        }
        Set<Integer> uniqueIndexes = new LinkedHashSet<>();
        for (int index : indexes) {
            if (index < 1) {
                throw new IllegalArgumentException("Column index must be positive");
            }
            if (!uniqueIndexes.add(index)) {
                throw new IllegalArgumentException("Duplicate column index: " + index);
            }
        }
        Set<String> uniqueLabels = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Column label must not be blank");
            }
            if (!uniqueLabels.add(label)) {
                throw new IllegalArgumentException("Duplicate column label: " + label);
            }
        }
    }

    static JdbcColumnSelection indexes(int firstIndex, int... additionalIndexes) {
        List<Integer> result = new ArrayList<>(additionalIndexes.length + 1);
        result.add(firstIndex);
        for (int index : additionalIndexes) {
            result.add(index);
        }
        return new JdbcColumnSelection(result, List.of());
    }

    static JdbcColumnSelection labels(String... labels) {
        Objects.requireNonNull(labels, "Column labels must not be null");
        List<String> result = new ArrayList<>(labels.length);
        for (String label : labels) {
            result.add(label);
        }
        return new JdbcColumnSelection(List.of(), result);
    }

    boolean all() {
        return indexes.isEmpty() && labels.isEmpty();
    }

    int[] selectedIndexes(List<ColumnInfo> columns) {
        if (all()) {
            int[] result = new int[columns.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = i + 1;
            }
            return result;
        }
        if (!indexes.isEmpty()) {
            for (int index : indexes) {
                if (index > columns.size()) {
                    throw new DataException("Column index " + index + " is outside result set column range 1.."
                                                    + columns.size());
                }
            }
            return indexes.stream()
                    .mapToInt(Integer::intValue)
                    .toArray();
        }
        Map<String, Integer> byLabel = labels(columns);
        int[] result = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            Integer index = byLabel.get(label);
            if (index == null) {
                index = byLabel.get(label.toLowerCase(Locale.ROOT));
            }
            if (index == null) {
                throw new DataException("Column label \"" + label + "\" is not present in JDBC result set");
            }
            result[i] = index + 1;
        }
        return result;
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
