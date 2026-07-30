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
 *
 * <p>Generated record and bean mappers, explicit row mappers, and reducers
 * all address values by label. Reading metadata once when a cursor opens keeps
 * label lookup out of the per-row hot path. This class uses only the result
 * set's labels and names; it does not inspect database primary-key metadata or
 * infer object identity.</p>
 */
final class JdbcColumnLayout {
    /** Number of physical columns in the result set. */
    private final int columnCount;
    /** Exact and lower-case label keys mapped to one-based JDBC indexes. */
    private final Map<String, Integer> indexes;

    /**
     * Creates a cached column layout.
     *
     * @param columnCount physical column count
     * @param indexes label-to-index map
     */
    private JdbcColumnLayout(int columnCount, Map<String, Integer> indexes) {
        this.columnCount = columnCount;
        this.indexes = indexes;
    }

    /**
     * Reads result metadata once and builds exact and case-insensitive label indexes.
     *
     * <p>Column labels are preferred because SQL aliases define the mapping
     * contract. The physical column name is used only when a driver returns a
     * blank label. The first occurrence wins when a driver exposes duplicate
     * labels; code generation is responsible for rejecting ambiguous projected
     * aliases where it can do so at compile time.</p>
     *
     * @param metadata result-set metadata
     * @param operation operation used for JDBC error translation
     * @return immutable layout
     */
    static JdbcColumnLayout create(ResultSetMetaData metadata, JdbcOperation operation) {
        try {
            int count = metadata.getColumnCount();
            Map<String, Integer> indexes = new HashMap<>(Math.max(4, count * 2));
            for (int index = 1; index <= count; index++) {
                String label = metadata.getColumnLabel(index);
                if (label == null || label.isBlank()) {
                    // Some drivers return no label for an unaliased expression; retain a usable fallback.
                    label = metadata.getColumnName(index);
                }
                // Keep both forms so generated aliases remain predictable across driver case conventions.
                indexes.putIfAbsent(label, index);
                indexes.putIfAbsent(label.toLowerCase(Locale.ROOT), index);
            }
            return new JdbcColumnLayout(count, Map.copyOf(indexes));
        } catch (SQLException e) {
            throw JdbcExceptionTranslator.translate(operation, e);
        }
    }

    /**
     * Returns the physical column count.
     *
     * @return column count
     */
    int columnCount() {
        return columnCount;
    }

    /**
     * Resolves a result label to its one-based JDBC index.
     *
     * @param label requested label
     * @return one-based column index
     * @throws DataException when the label is absent
     */
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
