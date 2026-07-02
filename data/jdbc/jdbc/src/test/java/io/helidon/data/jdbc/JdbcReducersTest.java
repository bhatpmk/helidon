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
import java.util.function.Consumer;

import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcReducersTest {

    @Test
    void mapsListRows() {
        JdbcExecutionResult result = rows(row(1L, "Pikachu"), row(2L, "Raichu"));

        List<String> names = JdbcReducers.list(result, row -> row.value("name", String.class));

        assertThat(names, contains("Pikachu", "Raichu"));
    }

    @Test
    void mapsSingleRow() {
        JdbcExecutionResult result = rows(row(1L, "Pikachu"));

        String name = JdbcReducers.item(result, row -> row.value("name", String.class));

        assertThat(name, is("Pikachu"));
    }

    @Test
    void rejectsSingleRowCardinalityProblems() {
        assertThrows(NoResultException.class, () -> JdbcReducers.item(rows(), row -> row));
        assertThrows(NonUniqueResultException.class, () -> JdbcReducers.item(rows(row(1L, "Pikachu"),
                                                                                row(2L, "Raichu")),
                                                                            row -> row));
    }

    @Test
    void mapsOptionalRows() {
        assertThat(JdbcReducers.optional(rows(), row -> row.value("name", String.class)), is(Optional.empty()));
        assertThat(JdbcReducers.optional(rows(row(1L, "Pikachu")), row -> row.value("name", String.class)),
                   is(Optional.of("Pikachu")));
        assertThrows(NonUniqueResultException.class, () -> JdbcReducers.optional(rows(row(1L, "Pikachu"),
                                                                                      row(2L, "Raichu")),
                                                                                  row -> row));
    }

    @Test
    void mapsScalarRowAndUpdateCountWithoutSqlClassification() {
        assertThat(JdbcReducers.scalar(rows(row(5L, "ignored")), Integer.class), is(5));
        assertThat(JdbcReducers.scalar(result(SqlKind.UPDATE, capture -> capture.addUpdateCount(7)), Integer.class),
                   is(7));
    }

    @Test
    void returnsOneUpdateCount() {
        JdbcExecutionResult result = result(SqlKind.UPDATE, capture -> capture.addUpdateCount(5));

        assertThat(JdbcReducers.updateCount(result).longValue(), is(5L));
    }

    @Test
    void rejectsMultipleUpdateCountsInsteadOfSumming() {
        JdbcExecutionResult result = result(SqlKind.UPDATE, capture -> {
            capture.addUpdateCount(2);
            capture.addUpdateCount(3);
        });

        assertThrows(DataException.class, () -> JdbcReducers.updateCount(result));
    }

    @Test
    void rejectsUpdateCountAsRows() {
        JdbcExecutionResult result = result(SqlKind.UPDATE, capture -> capture.addUpdateCount(5));

        assertThrows(DataException.class, () -> JdbcReducers.item(result, row -> row));
    }

    @Test
    void mapsGeneratedKeys() {
        RowSet rowSet = new RowSet(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false)),
                                   List.of(new MaterializedRow(List.of(new ColumnInfo("id",
                                                                                     "ID",
                                                                                     Types.BIGINT,
                                                                                     "BIGINT",
                                                                                     false)),
                                                               new Object[] {1L})));
        JdbcExecutionResult result = result(SqlKind.UPDATE, capture -> {
            capture.addUpdateCount(1);
            capture.addGeneratedKeys(rowSet);
        });

        assertThat(JdbcReducers.generatedKeys(result, row -> row.value("id", Long.class)), contains(1L));
        assertThat(JdbcReducers.generatedKey(result, row -> row.value("id", Long.class)), is(1L));
        assertThat(JdbcReducers.optionalGeneratedKey(result, row -> row.value("id", Long.class)),
                   is(Optional.of(1L)));
    }

    @Test
    void mapsOutCursorRows() {
        RowSet rowSet = new RowSet(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                           new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                   List.of(row(1L, "Pikachu"), row(2L, "Raichu")));
        JdbcExecutionResult result = result(SqlKind.CALL, capture -> capture.addCursor("rows", rowSet));

        List<String> names = JdbcReducers.outCursor(result, "rows", row -> row.value("name", String.class));

        assertThat(names, contains("Pikachu", "Raichu"));
    }

    private static JdbcExecutionResult rows(MaterializedRow... rows) {
        StepRef step = StepRef.create(0);
        RowSet rowSet = new RowSet(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                           new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                   List.of(rows));
        return result(SqlKind.QUERY, capture -> capture.addRows(rowSet));
    }

    private static JdbcExecutionResult result(SqlKind kind, Consumer<JdbcOperationResult.Builder> capture) {
        JdbcOperationResult.Builder builder = JdbcOperationResult.builder(StepRef.create(0), kind);
        capture.accept(builder);
        return new JdbcExecutionResult(List.of(builder.build(List.of(), Optional.empty())));
    }

    private static MaterializedRow row(Object id, Object name) {
        return new MaterializedRow(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                           new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                   new Object[] {id, name});
    }
}
