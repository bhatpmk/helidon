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

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

class JdbcExecutionResultTest {

    @Test
    void prettyPrintsCompleteExecutionResult() {
        RowSet rows = rows("row-value");
        JdbcOperationResult.Builder builder = JdbcOperationResult.builder(StepRef.create(0), SqlKind.CALL);
        builder.addRows(rows);
        builder.addUpdateCount(3);
        builder.addGeneratedKeys(rows("generated-key"));
        builder.addOutput("output-name", "output-value");
        builder.addCursor("cursor-name", rows);
        builder.addBatch(new JdbcBatchResult(List.of(
                new JdbcBatchResult.JdbcBatchItem(JdbcBatchResult.BatchStatus.UPDATED, OptionalLong.of(1)),
                new JdbcBatchResult.JdbcBatchItem(JdbcBatchResult.BatchStatus.SUCCESS_NO_INFO, OptionalLong.empty()))));

        JdbcOperationResult operation = builder.build(
                List.of(new JdbcWarningInfo("01000", 10, "warning-message")),
                Optional.of(new JdbcFailure("42000", 20, "failure-message")));

        String dump = new JdbcExecutionResult(List.of(operation)).toString();

        assertThat(dump, containsString("JdbcExecutionResult {" + System.lineSeparator()
                                                + "  operationCount: 1"));
        assertThat(dump, containsString("kind: CALL"));
        assertThat(dump, containsString("ordinal: 0"));
        assertThat(dump, containsString("columnCount: 1"));
        assertThat(dump, containsString("rowCount: 1"));
        assertThat(dump, containsString("label: \"value\""));
        assertThat(dump, containsString("name: \"VALUE\""));
        assertThat(dump, containsString("jdbcType: " + Types.VARCHAR));
        assertThat(dump, containsString("typeName: \"VARCHAR\""));
        assertThat(dump, containsString("nullable: true"));
        assertThat(dump, containsString("labelIndexes: {\"VALUE\": 0, \"value\": 0}"));
        assertThat(dump, containsString("javaType: java.lang.String"));
        assertThat(dump, containsString("value: \"row-value\""));
        assertThat(dump, containsString("count: 3"));
        assertThat(dump, containsString("value: \"generated-key\""));
        assertThat(dump, containsString("name: \"output-name\""));
        assertThat(dump, containsString("value: \"output-value\""));
        assertThat(dump, containsString("name: \"cursor-name\""));
        assertThat(dump, containsString("status: UPDATED"));
        assertThat(dump, containsString("updateCount: 1"));
        assertThat(dump, containsString("status: SUCCESS_NO_INFO"));
        assertThat(dump, containsString("updateCount: absent"));
        assertThat(dump, containsString("sqlState: \"01000\""));
        assertThat(dump, containsString("vendorCode: 10"));
        assertThat(dump, containsString("message: \"warning-message\""));
        assertThat(dump, containsString("sqlState: \"42000\""));
        assertThat(dump, containsString("vendorCode: 20"));
        assertThat(dump, containsString("message: \"failure-message\""));
    }

    @Test
    void printsEveryDirectResult() {
        JdbcOperationResult.Builder builder = JdbcOperationResult.builder(StepRef.create(0), SqlKind.UPDATE);
        for (int i = 0; i < 17; i++) {
            builder.addUpdateCount(i);
        }

        JdbcExecutionResult result = new JdbcExecutionResult(
                List.of(builder.build(List.of(), Optional.empty())));

        String dump = result.toString();

        assertThat(dump, containsString("ordinal: 15"));
        assertThat(dump, containsString("count: 15"));
        assertThat(dump, containsString("ordinal: 16"));
        assertThat(dump, containsString("count: 16"));
    }

    /**
     * Create a one-column row set containing a value the demonstration dump must display.
     *
     * @param value test value
     * @return detached row set
     */
    private static RowSet rows(Object value) {
        List<ColumnInfo> columns = List.of(new ColumnInfo("value", "VALUE", Types.VARCHAR, "VARCHAR", true));
        return new RowSet(columns, List.of(new MaterializedRow(columns, new Object[] {value})));
    }
}
