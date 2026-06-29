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
        JdbcTranscript transcript = rows(row(1L, "Pikachu"), row(2L, "Raichu"));

        List<String> names = JdbcReducers.list(transcript, row -> row.value("name", String.class));

        assertThat(names, contains("Pikachu", "Raichu"));
    }

    @Test
    void mapsSingleRow() {
        JdbcTranscript transcript = rows(row(1L, "Pikachu"));

        String name = JdbcReducers.item(transcript, row -> row.value("name", String.class));

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
    void sumsUpdateCounts() {
        StepRef step = StepRef.create(0);
        JdbcTranscript transcript = new JdbcTranscript(List.of(new StepTranscript(step,
                                                                                  SqlKind.UPDATE,
                                                                                  List.of(new UpdateCountEvent(step, 0, 2),
                                                                                          new UpdateCountEvent(step, 1, 3)),
                                                                                  List.of(),
                                                                                  Optional.empty())));

        assertThat(JdbcReducers.updateCount(transcript).longValue(), is(5L));
    }

    @Test
    void mapsUpdateCountsAsScalarRow() {
        StepRef step = StepRef.create(0);
        JdbcTranscript transcript = new JdbcTranscript(List.of(new StepTranscript(step,
                                                                                  SqlKind.QUERY,
                                                                                  List.of(new UpdateCountEvent(step, 0, 2),
                                                                                          new UpdateCountEvent(step, 1, 3)),
                                                                                  List.of(),
                                                                                  Optional.empty())));

        assertThat(JdbcReducers.item(transcript, row -> row.value(1, Integer.class)), is(5));
        assertThat(JdbcReducers.item(transcript, row -> row.value("updateCount", Long.class)), is(5L));
        assertThat(JdbcReducers.item(transcript, row -> row.value(1, Boolean.class)), is(true));
    }

    @Test
    void mapsGeneratedKeys() {
        StepRef step = StepRef.create(0);
        RowSet rowSet = new RowSet(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false)),
                                   List.of(new MaterializedRow(List.of(new ColumnInfo("id",
                                                                                     "ID",
                                                                                     Types.BIGINT,
                                                                                     "BIGINT",
                                                                                     false)),
                                                               new Object[] {1L})));
        JdbcTranscript transcript = new JdbcTranscript(List.of(new StepTranscript(step,
                                                                                  SqlKind.UPDATE,
                                                                                  List.of(new UpdateCountEvent(step, 0, 1),
                                                                                          new GeneratedKeysEvent(step, 1, rowSet)),
                                                                                  List.of(),
                                                                                  Optional.empty())));

        assertThat(JdbcReducers.generatedKeys(transcript, row -> row.value("id", Long.class)), contains(1L));
        assertThat(JdbcReducers.generatedKey(transcript, row -> row.value("id", Long.class)), is(1L));
        assertThat(JdbcReducers.optionalGeneratedKey(transcript, row -> row.value("id", Long.class)), is(Optional.of(1L)));
    }

    private static JdbcTranscript rows(MaterializedRow... rows) {
        StepRef step = StepRef.create(0);
        RowSet rowSet = new RowSet(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                           new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                    List.of(rows));
        return new JdbcTranscript(List.of(new StepTranscript(step,
                                                             SqlKind.QUERY,
                                                             List.of(new RowsEvent(step,
                                                                                   0,
                                                                                   RowsEvent.RowRole.DIRECT_RESULT_SET,
                                                                                   Optional.empty(),
                                                                                   rowSet)),
                                                             List.of(),
                                                             Optional.empty())));
    }

    private static MaterializedRow row(Object id, Object name) {
        return new MaterializedRow(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                          new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                   new Object[] {id, name});
    }
}
