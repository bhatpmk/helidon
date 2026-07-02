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
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Creates an exhaustive, multiline representation of a detached JDBC execution result.
 * <p>
 * This formatter exists only for the current architecture demonstration. It intentionally prints application data,
 * generated keys, callable values, warning messages, and failure messages. It must be removed before the provider is
 * checked in and must never be used as a production logging facility.
 */
final class JdbcExecutionResultFormatter {
    private JdbcExecutionResultFormatter() {
    }

    /**
     * Format every operation and captured value in an execution result.
     *
     * @param result execution result to format
     * @return exhaustive multiline representation
     */
    static String format(JdbcExecutionResult result) {
        PrettyPrinter output = new PrettyPrinter();
        output.line("JdbcExecutionResult {");
        output.indent();
        output.line("operationCount: " + result.operations().size());
        output.line("operations: [");
        output.indent();
        for (JdbcOperationResult operation : result.operations()) {
            appendOperation(output, operation);
        }
        output.outdent();
        output.line("]");
        output.outdent();
        output.line("}");
        return output.toString();
    }

    /**
     * Append one operation, preserving the distinction between direct results and scoped attachments.
     */
    private static void appendOperation(PrettyPrinter output, JdbcOperationResult operation) {
        output.line("JdbcOperationResult {");
        output.indent();
        output.line("reference: {");
        output.indent();
        output.line("index: " + operation.ref().index());
        output.line("name: " + formatValue(operation.ref().name().orElse(null)));
        output.outdent();
        output.line("}");
        output.line("kind: " + operation.kind());
        appendDirectResults(output, operation.directResults().items());

        if (operation.generatedKeys().isPresent()) {
            appendRowSet(output, "generatedKeys", operation.generatedKeys().orElseThrow());
        } else {
            output.line("generatedKeys: absent");
        }

        appendCallableOutputs(output, operation.callableOutputs());
        appendBatch(output, operation.batch().orElse(null));
        appendWarnings(output, operation.warnings());
        appendFailure(output, operation.failure().orElse(null));
        output.outdent();
        output.line("}");
    }

    /**
     * Append direct result sets and update counts in JDBC encounter order.
     */
    private static void appendDirectResults(PrettyPrinter output, List<JdbcDirectResult> directResults) {
        output.line("directResults: [");
        output.indent();
        for (JdbcDirectResult directResult : directResults) {
            if (directResult instanceof JdbcRowsResult rowsResult) {
                output.line("Rows {");
                output.indent();
                output.line("ordinal: " + rowsResult.ordinal());
                appendRowSet(output, "rowSet", rowsResult.rows());
                output.outdent();
                output.line("}");
            } else if (directResult instanceof JdbcUpdateCountResult updateCountResult) {
                output.line("UpdateCount {");
                output.indent();
                output.line("ordinal: " + updateCountResult.ordinal());
                output.line("count: " + updateCountResult.count());
                output.outdent();
                output.line("}");
            } else {
                output.line("UnknownDirectResult {");
                output.indent();
                output.line("type: " + directResult.getClass().getName());
                output.line("ordinal: " + directResult.ordinal());
                output.outdent();
                output.line("}");
            }
        }
        output.outdent();
        output.line("]");
    }

    /**
     * Append complete row-set metadata and every captured row value.
     */
    private static void appendRowSet(PrettyPrinter output, String label, RowSet rowSet) {
        output.line(label + ": RowSet {");
        output.indent();
        output.line("columnCount: " + rowSet.columns().size());
        output.line("rowCount: " + rowSet.size());
        appendColumns(output, rowSet.columns());
        output.line("labelIndexes: " + formatLabelIndexes(rowSet.layout().labels()));
        appendRows(output, rowSet);
        output.outdent();
        output.line("}");
    }

    /**
     * Append every field copied from {@link java.sql.ResultSetMetaData} for each selected column.
     */
    private static void appendColumns(PrettyPrinter output, List<ColumnInfo> columns) {
        output.line("columns: [");
        output.indent();
        for (int i = 0; i < columns.size(); i++) {
            ColumnInfo column = columns.get(i);
            output.line("Column {");
            output.indent();
            output.line("index: " + (i + 1));
            output.line("label: " + formatValue(column.label()));
            output.line("name: " + formatValue(column.name()));
            output.line("jdbcType: " + column.jdbcType());
            output.line("typeName: " + formatValue(column.typeName()));
            output.line("nullable: " + column.nullable());
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
    }

    /**
     * Append every materialized record with values aligned to the shared column layout.
     */
    private static void appendRows(PrettyPrinter output, RowSet rowSet) {
        output.line("records: [");
        output.indent();
        for (int rowIndex = 0; rowIndex < rowSet.rows().size(); rowIndex++) {
            MaterializedRow row = rowSet.rows().get(rowIndex);
            output.line("Record {");
            output.indent();
            output.line("index: " + rowIndex);
            output.line("values: [");
            output.indent();
            for (int columnIndex = 1; columnIndex <= row.columnCount(); columnIndex++) {
                ColumnInfo column = rowSet.columns().get(columnIndex - 1);
                Object value = row.value(columnIndex);
                output.line("Value {");
                output.indent();
                output.line("columnIndex: " + columnIndex);
                output.line("label: " + formatValue(column.label()));
                output.line("javaType: " + (value == null ? "null" : value.getClass().getName()));
                output.line("value: " + formatValue(value));
                output.outdent();
                output.line("}");
            }
            output.outdent();
            output.line("]");
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
    }

    /**
     * Append scalar OUT values and cursor row sets using their declared output names.
     */
    private static void appendCallableOutputs(PrettyPrinter output, JdbcCallableOutputs callableOutputs) {
        output.line("callableOutputs: {");
        output.indent();
        output.line("values: [");
        output.indent();
        for (Map.Entry<String, Object> entry : callableOutputs.values().entrySet()) {
            Object value = entry.getValue();
            output.line("OutputValue {");
            output.indent();
            output.line("name: " + formatValue(entry.getKey()));
            output.line("javaType: " + (value == null ? "null" : value.getClass().getName()));
            output.line("value: " + formatValue(value));
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
        output.line("cursors: [");
        output.indent();
        for (Map.Entry<String, RowSet> entry : callableOutputs.cursors().entrySet()) {
            output.line("Cursor {");
            output.indent();
            output.line("name: " + formatValue(entry.getKey()));
            appendRowSet(output, "rowSet", entry.getValue());
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
        output.outdent();
        output.line("}");
    }

    /**
     * Append every prepared-batch item and its normalized JDBC outcome.
     */
    private static void appendBatch(PrettyPrinter output, JdbcBatchResult batch) {
        if (batch == null) {
            output.line("batch: absent");
            return;
        }

        output.line("batch: JdbcBatchResult {");
        output.indent();
        output.line("items: [");
        output.indent();
        for (int i = 0; i < batch.items().size(); i++) {
            JdbcBatchResult.JdbcBatchItem item = batch.items().get(i);
            output.line("BatchItem {");
            output.indent();
            output.line("index: " + i);
            output.line("status: " + item.status());
            output.line("updateCount: " + (item.updateCount().isPresent()
                    ? item.updateCount().getAsLong()
                    : "absent"));
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
        output.outdent();
        output.line("}");
    }

    /**
     * Append every detached warning field, including the driver message.
     */
    private static void appendWarnings(PrettyPrinter output, List<JdbcWarningInfo> warnings) {
        output.line("warnings: [");
        output.indent();
        for (JdbcWarningInfo warning : warnings) {
            output.line("Warning {");
            output.indent();
            output.line("sqlState: " + formatValue(warning.sqlState()));
            output.line("vendorCode: " + warning.vendorCode());
            output.line("message: " + formatValue(warning.message()));
            output.outdent();
            output.line("}");
        }
        output.outdent();
        output.line("]");
    }

    /**
     * Append every detached failure field, including the driver message.
     */
    private static void appendFailure(PrettyPrinter output, JdbcFailure failure) {
        if (failure == null) {
            output.line("failure: absent");
            return;
        }

        output.line("failure: JdbcFailure {");
        output.indent();
        output.line("sqlState: " + formatValue(failure.sqlState()));
        output.line("vendorCode: " + failure.vendorCode());
        output.line("message: " + formatValue(failure.message()));
        output.outdent();
        output.line("}");
    }

    /**
     * Format the complete case-insensitive label lookup map in stable key order.
     */
    private static String formatLabelIndexes(Map<String, Integer> labels) {
        StringJoiner result = new StringJoiner(", ", "{", "}");
        new TreeMap<>(labels).forEach((label, index) -> result.add(formatValue(label) + ": " + index));
        return result.toString();
    }

    /**
     * Format common scalar and array values without using Java reflection.
     */
    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence chars) {
            return quote(chars.toString());
        }
        if (value instanceof Character character) {
            return quote(character.toString());
        }
        if (value instanceof Object[] array) {
            return Arrays.deepToString(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof short[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof int[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof long[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof float[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof double[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof boolean[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof char[] array) {
            return quote(new String(array));
        }
        return String.valueOf(value);
    }

    /**
     * Quote and escape text so embedded control characters do not break the pretty-print structure.
     */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + '"';
    }

    /**
     * Minimal indentation helper for a deterministic multiline diagnostic.
     */
    private static final class PrettyPrinter {
        private static final String INDENT = "  ";

        private final StringBuilder output = new StringBuilder();
        private int indentation;

        void indent() {
            indentation++;
        }

        void outdent() {
            indentation--;
        }

        void line(String value) {
            output.append(INDENT.repeat(indentation)).append(value).append(System.lineSeparator());
        }

        @Override
        public String toString() {
            int lineSeparatorLength = System.lineSeparator().length();
            return output.substring(0, output.length() - lineSeparatorLength);
        }
    }
}
