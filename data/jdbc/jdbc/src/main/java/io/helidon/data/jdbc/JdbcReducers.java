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

import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;

final class JdbcReducers {

    private JdbcReducers() {
    }

    static <T> List<T> list(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowsEvent rows = rowsEvent(transcript);
        return rows.rowSet()
                .rows()
                .stream()
                .map(mapper::map)
                .toList();
    }

    static <T> T item(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowsEvent rows = rowsEvent(transcript);
        if (rows.rowSet().isEmpty()) {
            throw new NoResultException("JDBC query returned no rows.");
        }
        if (rows.rowSet().size() > 1) {
            throw new NonUniqueResultException("JDBC query returned more than one row.");
        }
        return mapper.map(rows.rowSet().rows().getFirst());
    }

    static <T> T itemOrNull(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowsEvent rows = rowsEvent(transcript);
        if (rows.rowSet().isEmpty()) {
            return null;
        }
        if (rows.rowSet().size() > 1) {
            throw new NonUniqueResultException("JDBC query returned more than one row.");
        }
        return mapper.map(rows.rowSet().rows().getFirst());
    }

    static <T> Optional<T> optional(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        return Optional.ofNullable(itemOrNull(transcript, mapper));
    }

    static Number updateCount(JdbcTranscript transcript) {
        long count = 0;
        boolean found = false;
        for (JdbcEvent event : transcript.onlyStep().events()) {
            if (event instanceof UpdateCountEvent updateCount) {
                count = Math.addExact(count, updateCount.count());
                found = true;
            }
        }
        if (!found) {
            return 0L;
        }
        return count;
    }

    static <T> List<T> generatedKeys(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowSet rowSet = generatedKeysEvent(transcript).rowSet();
        return rowSet.rows()
                .stream()
                .map(mapper::map)
                .toList();
    }

    static <T> T generatedKey(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowSet rowSet = generatedKeysEvent(transcript).rowSet();
        if (rowSet.isEmpty()) {
            throw new NoResultException("JDBC update returned no generated keys.");
        }
        if (rowSet.size() > 1) {
            throw new NonUniqueResultException("JDBC update returned more than one generated-key row.");
        }
        return mapper.map(rowSet.rows().getFirst());
    }

    static <T> Optional<T> optionalGeneratedKey(JdbcTranscript transcript, JdbcRowMapper<T> mapper) {
        RowSet rowSet = generatedKeysEvent(transcript).rowSet();
        if (rowSet.isEmpty()) {
            return Optional.empty();
        }
        if (rowSet.size() > 1) {
            throw new NonUniqueResultException("JDBC update returned more than one generated-key row.");
        }
        return Optional.of(mapper.map(rowSet.rows().getFirst()));
    }

    private static RowsEvent rowsEvent(JdbcTranscript transcript) {
        RowsEvent found = null;
        for (JdbcEvent event : transcript.onlyStep().events()) {
            if (event instanceof RowsEvent rows) {
                if (found != null) {
                    throw new DataException("JDBC transcript contains more than one result set.");
                }
                found = rows;
            }
        }
        if (found == null) {
            return updateCountRows(transcript);
        }
        return found;
    }

    private static RowsEvent updateCountRows(JdbcTranscript transcript) {
        StepTranscript step = transcript.onlyStep();
        long count = 0;
        boolean found = false;
        for (JdbcEvent event : step.events()) {
            if (event instanceof UpdateCountEvent updateCount) {
                count = Math.addExact(count, updateCount.count());
                found = true;
            }
        }
        if (!found) {
            throw new DataException("JDBC transcript contains no result set.");
        }

        List<ColumnInfo> columns = List.of(new ColumnInfo("updateCount", "updateCount", Types.BIGINT, "BIGINT", false));
        RowSet rowSet = new RowSet(columns, List.of(new MaterializedRow(columns, new Object[] {count})));
        return new RowsEvent(step.ref(),
                             0,
                             RowsEvent.RowRole.DIRECT_RESULT_SET,
                             Optional.of("updateCount"),
                             rowSet);
    }

    private static GeneratedKeysEvent generatedKeysEvent(JdbcTranscript transcript) {
        GeneratedKeysEvent found = null;
        for (JdbcEvent event : transcript.onlyStep().events()) {
            if (event instanceof GeneratedKeysEvent generatedKeys) {
                if (found != null) {
                    throw new DataException("JDBC transcript contains more than one generated-key result set.");
                }
                found = generatedKeys;
            }
        }
        if (found == null) {
            throw new DataException("JDBC transcript contains no generated-key result set.");
        }
        return found;
    }
}
