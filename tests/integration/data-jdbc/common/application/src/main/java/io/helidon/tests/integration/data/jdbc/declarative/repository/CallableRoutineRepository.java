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
package io.helidon.tests.integration.data.jdbc.declarative.repository;

import java.sql.JDBCType;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;

import io.helidon.data.Data;
import io.helidon.data.jdbc.Jdbc;
import io.helidon.data.jdbc.JdbcClient;
import io.helidon.service.registry.Service;

@Data.Repository
@Data.Provider("jdbc")
public interface CallableRoutineRepository {
    @Jdbc.Statement("{? = call sp_add(:jdbcClient)}")
    @Jdbc.OutParameter(type = JDBCType.INTEGER)
    int scalar(Integer jdbcClient);

    @Jdbc.Statement("{? = call sp_add(:value)}")
    @Jdbc.OutParameter(type = JDBCType.INTEGER)
    Optional<Integer> nullable(Integer value);

    @Jdbc.Statement("{call sp_values(:input, :value, :other)}")
    Values aggregate(Integer input, Integer value, String other);

    @Jdbc.Statement("{call sp_values(:input, :value, :other)}")
    @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
    @Jdbc.OutParameter(value = "other", type = JDBCType.VARCHAR)
    JdbcClient.OutParameters snapshot(Integer input, Integer value, String other);

    @Jdbc.Statement("{call sp_values(:input, :value, :other)}")
    @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
    @Jdbc.OutParameter(value = "other", type = JDBCType.VARCHAR)
    void consume(Integer input, Integer value, String other, Consumer<JdbcClient.CallResult> result);

    @Jdbc.Statement("{? = call sp_cursor(:input)}")
    @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
    List<Integer> cursor(int input);

    @Jdbc.Statement("{? = call sp_cursor(:input)}")
    @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
    Optional<List<Integer>> optionalCursor(int input);

    @Jdbc.Statement("{? = call sp_cursor(:input)}")
    @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
    void consumeCursor(int input, Consumer<JdbcClient.CallResult> rows);

    @Jdbc.Statement("{call sp_direct()}")
    @Jdbc.Execution(Jdbc.ExecutionType.CALL)
    void consumeDirect(Consumer<JdbcClient.CallResult> result);

    @Jdbc.Statement("{call sp_pair(:first, :second, :value)}")
    @Jdbc.OutParameter(value = "first", type = JDBCType.REF_CURSOR)
    @Jdbc.OutParameter(value = "second", type = JDBCType.REF_CURSOR)
    @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
    void consumePair(Consumer<JdbcClient.CallResult> statement);

    @Jdbc.Statement("{call sp_inout(:input, :value)}")
    @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
    int inout(int input, int value);

    @Jdbc.Statement("{call sp_direct()}")
    @Jdbc.Execution(Jdbc.ExecutionType.CALL)
    int direct();

    @Jdbc.Statement("{call sp_noop()}")
    @Jdbc.Execution(Jdbc.ExecutionType.CALL)
    int noDirect();

    @Jdbc.Statement("{call sp_bundle(:input, :value, :rows)}")
    Bundle aggregateCursor(int input);

    @Jdbc.Statement("{call sp_bundle(:input, :value, :rows)}")
    OptionalBundle aggregateOptionalCursor(int input);

    @Jdbc.Statement("{? = call sp_cursor(:input)}")
    @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
    int cursorOne(int input);

    @Jdbc.Statement("{? = call sp_cursor(:input)}")
    @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
    Optional<Integer> cursorOptional(int input);

    @Jdbc.Statement("{? = call sp_add(:input)}")
    @Jdbc.OutParameter(type = JDBCType.INTEGER)
    String mapped(Integer input, JdbcClient.CallMapper<String> mapper);

    @Jdbc.Statement("{call sp_bundle(:MAPPER_RECORD_BUNDLE_OUTPUT_0_2, :value, :rows)}")
    RecordBundle recordBundle(int MAPPER_RECORD_BUNDLE_OUTPUT_0_2);

    @Jdbc.Statement("SELECT DATA_VALUE AS label FROM TX_MATRIX WHERE DATA_VALUE = :input")
    QueryProjection recordBundle_OUTPUT_0(String input);

    record RecordBundle(@Jdbc.OutParameter(type = JDBCType.REF_CURSOR) List<Projection> rows,
                        @Jdbc.OutParameter(type = JDBCType.INTEGER) int value) {
    }

    record Projection(int id) {
    }

    record QueryProjection(String label) {
    }

    record OptionalBundle(@Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
                          @Jdbc.RowMapper(IntegerMapper.class) Optional<List<Integer>> rows,
                          @Jdbc.OutParameter(type = JDBCType.INTEGER) int value) {
    }

    record Bundle(@Jdbc.OutParameter(type = JDBCType.REF_CURSOR) @Jdbc.RowMapper(IntegerMapper.class) List<Integer> rows,
                  @Jdbc.OutParameter(type = JDBCType.INTEGER) int value) {
    }

    @Service.Singleton
    class IntegerMapper implements JdbcClient.RowMapper<Integer> {
        @Override
        public Integer map(JdbcClient.Row row) {
            Objects.requireNonNull(row, "The current row must not be null.");
            return row.get(1, Integer.class);
        }
    }

    record Values(@Jdbc.OutParameter(type = JDBCType.INTEGER) int value,
                  @Jdbc.OutParameter(type = JDBCType.VARCHAR) String other) {
    }
}
