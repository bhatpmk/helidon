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

import io.helidon.data.DataException;

/**
 * Detached implementation of {@link JdbcRow}.
 * <p>
 * A materialized row stores the values copied from one JDBC {@link java.sql.ResultSet} row and reads labels through a
 * shared {@link RowLayout}. The row does not retain a {@code ResultSet}, statement, or connection. This is the object
 * generated mappers and imperative row mappers read after {@code JdbcRunner} has closed JDBC resources.
 */
final class MaterializedRow implements JdbcRow {

    private final RowLayout layout;
    private final Object[] values;

    /**
     * Create a row from caller-owned column metadata and values.
     * <p>
     * The values array is defensively copied because the caller may still own and mutate it.
     *
     * @param columns row columns
     * @param values row values
     */
    MaterializedRow(List<ColumnInfo> columns, Object[] values) {
        this(new RowLayout(columns), values);
    }

    /**
     * Create a row from a shared layout and caller-owned values.
     * <p>
     * The values array is defensively copied because the caller may still own and mutate it.
     *
     * @param layout shared row layout
     * @param values row values
     */
    MaterializedRow(RowLayout layout, Object[] values) {
        this(layout, values, true);
    }

    /**
     * Create a row from values already owned by the transcript materialization path.
     * <p>
     * This method intentionally skips the defensive array copy. It must only be used when the caller created the values
     * array for this row and will not mutate it after passing it here.
     *
     * @param layout shared row layout
     * @param values row values owned by this row after the call
     * @return materialized row
     */
    static MaterializedRow trusted(RowLayout layout, Object[] values) {
        return new MaterializedRow(layout, values, false);
    }

    private MaterializedRow(RowLayout layout, Object[] values, boolean copyValues) {
        this.layout = Objects.requireNonNull(layout, "Row layout must not be null");
        Objects.requireNonNull(values, "Row values must not be null");
        if (layout.columnCount() != values.length) {
            throw new DataException("JDBC row has "
                                            + values.length
                                            + " values, but row layout has "
                                            + layout.columnCount()
                                            + " columns");
        }
        this.values = copyValues ? values.clone() : values;
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
        return values[layout.columnIndex(label)];
    }

    @Override
    public <T> T value(String label, Class<T> type) {
        return JdbcValues.convert(value(label), type);
    }

}
