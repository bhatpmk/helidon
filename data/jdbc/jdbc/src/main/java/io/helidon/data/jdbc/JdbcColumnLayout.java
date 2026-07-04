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

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.helidon.data.DataException;

/**
 * Result-column labels resolved once per result set.
 */
final class JdbcColumnLayout {
    private final int columnCount;
    private final Map<String, Integer> indexes;

    private JdbcColumnLayout(int columnCount, Map<String, Integer> indexes) {
        this.columnCount = columnCount;
        this.indexes = indexes;
    }

    static JdbcColumnLayout create(ResultSetMetaData metadata, JdbcOperation operation) {
        try {
            int count = metadata.getColumnCount();
            Map<String, Integer> indexes = new HashMap<>(Math.max(4, count * 2));
            for (int index = 1; index <= count; index++) {
                String label = metadata.getColumnLabel(index);
                if (label == null || label.isBlank()) {
                    label = metadata.getColumnName(index);
                }
                indexes.putIfAbsent(label, index);
                indexes.putIfAbsent(label.toLowerCase(Locale.ROOT), index);
            }
            return new JdbcColumnLayout(count, Map.copyOf(indexes));
        } catch (SQLException e) {
            throw JdbcExceptionTranslator.translate(operation, e);
        }
    }

    int columnCount() {
        return columnCount;
    }

    int index(String label) {
        Integer index = indexes.get(label);
        if (index == null) {
            index = indexes.get(label.toLowerCase(Locale.ROOT));
        }
        if (index == null) {
            throw new DataException("Result column label not found: " + label);
        }
        return index;
    }
}
