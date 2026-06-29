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

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;

import io.helidon.data.DataException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterializedRowTest {

    @Test
    void readsByIndexAndLabel() {
        MaterializedRow row = row(1L, "Pikachu");

        assertThat(row.columnCount(), is(2));
        assertThat(row.value(1, Long.class), is(1L));
        assertThat(row.value("name", String.class), is("Pikachu"));
        assertThat(row.value("NAME", String.class), is("Pikachu"));
    }

    @Test
    void convertsNumbersExactly() {
        MaterializedRow row = row(new BigDecimal("42"), "Pikachu");

        assertThat(row.value("id", Integer.class), is(42));
        assertThat(row.value("id", Long.class), is(42L));
    }

    @Test
    void rejectsMissingColumn() {
        MaterializedRow row = row(1L, "Pikachu");

        assertThrows(DataException.class, () -> row.value("missing"));
    }

    private static MaterializedRow row(Object id, Object name) {
        return new MaterializedRow(List.of(new ColumnInfo("id", "ID", Types.BIGINT, "BIGINT", false),
                                          new ColumnInfo("name", "NAME", Types.VARCHAR, "VARCHAR", true)),
                                   new Object[] {id, name});
    }
}
