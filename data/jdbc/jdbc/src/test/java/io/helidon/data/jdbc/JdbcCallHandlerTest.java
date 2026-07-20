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
import java.math.BigInteger;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLWarning;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.data.DataException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCallHandlerTest {

    @Test
    void consumesAFunctionReturnedAsADirectResultByARealDriver() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:call_function;DB_CLOSE_DELAY=-1");
        JdbcClient client = new JdbcClientImpl(dataSource);
        JdbcCall layout = JdbcCall.builder().in(1, Types.INTEGER).build();

        int result = client.create("{call ABS(?)}")
                .bind(1, -19)
                .call(layout, JdbcResultRequest.call(call -> {
                    AtomicReference<Integer> value = new AtomicReference<>();
                    call.results().visit((index, rows) -> value.set(rows.map(Integer.class).one()));
                    return value.get();
                }));

        assertEquals(19, result);
    }

    @Test
    void consumesDirectResultsCursorsAndScalarsInDriverSafeOrder() {
        RecordingJdbc recording = new RecordingJdbc()
                .callRows("direct-one", "direct-two")
                .callUpdateCount(7)
                .callRows("direct-three")
                .callOutput(2, 41)
                .callCursor(3, "cursor-one", "cursor-two")
                .callOutput(4, "COMPLETE");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder()
                .in(1, Types.VARCHAR)
                .inOut(2, "attempts", Types.INTEGER, Integer.class)
                .cursor(3, "entries")
                .out(4, "status", Types.VARCHAR, String.class, "STATUS_TYPE")
                .build();
        List<String> directRows = new ArrayList<>();
        List<Integer> directResultIndexes = new ArrayList<>();
        List<Long> updateCounts = new ArrayList<>();
        List<String> cursorRows = new ArrayList<>();

        CallResult result = client.create("{call PROCESS(?, ?, ?, ?)}")
                .bind(1, "job-17")
                .bind(2, 40)
                .call(layout,
                      JdbcResultRequest.call(call -> {
                          call.results().visit(new JdbcClient.CallResultVisitor() {
                              @Override
                              public void rows(int resultSetIndex, JdbcClient.CallRows rows) {
                                  directResultIndexes.add(resultSetIndex);
                                  directRows.addAll(rows.map(String.class).list());
                              }

                              @Override
                              public void updateCount(int itemIndex, long count) {
                                  assertEquals(1, itemIndex);
                                  updateCounts.add(count);
                              }
                          });
                          call.outputs()
                                  .cursor("entries")
                                  .map(String.class)
                                  .visitAll(JdbcResultRequest.visitAll(cursorRows::add));
                          return new CallResult(call.outputs().required("attempts", Integer.class),
                                                call.outputs().required(4, String.class));
                      }).withOptions(JdbcStatementOptions.builder()
                                             .fetchSize(64)
                                             .queryTimeout(Duration.ofSeconds(3))
                                             .maxRows(500)
                                             .poolableHint(true)
                                             .build()));

        assertEquals(new CallResult(41, "COMPLETE"), result);
        assertEquals(List.of("direct-one", "direct-two", "direct-three"), directRows);
        assertEquals(List.of(0, 1), directResultIndexes);
        assertEquals(List.of(7L), updateCounts);
        assertEquals(List.of("cursor-one", "cursor-two"), cursorRows);

        List<String> events = recording.events();
        assertThat(events,
                   hasItems("call.fetchSize:64",
                            "call.maxRows:500",
                            "call.queryTimeout:3",
                            "call.poolable:true",
                            "call.bind:1:job-17",
                            "call.bind:2:40",
                            "call.register:2:" + Types.INTEGER,
                            "call.register:3:" + Types.REF_CURSOR,
                            "call.register:4:" + Types.VARCHAR + ":STATUS_TYPE"));
        assertThat(events.indexOf("call.prepare"), lessThan(events.indexOf("call.fetchSize:64")));
        assertThat(events.indexOf("call.poolable:true"), lessThan(events.indexOf("call.bind:1:job-17")));
        assertThat(events.indexOf("call.bind:2:40"), lessThan(events.indexOf("call.register:2:" + Types.INTEGER)));
        assertThat(events.indexOf("call.register:4:" + Types.VARCHAR + ":STATUS_TYPE"),
                   lessThan(events.indexOf("call.execute")));
        assertThat(events.indexOf("call.result.0.close"),
                   lessThan(events.indexOf("call.moreResults:" + Statement.CLOSE_CURRENT_RESULT)));
        assertThat(events.indexOf("call.result.2.close"), lessThan(events.indexOf("call.output:3")));
        assertThat(events.indexOf("call.cursor.3.close"), lessThan(events.indexOf("call.output:2:typed")));
        assertThat(events.indexOf("call.output:4:typed"), lessThan(events.indexOf("call.close")));
        assertThat(events.indexOf("call.close"), lessThan(events.indexOf("connection.close")));
    }

    @Test
    void readsFunctionReturnAndNullableScalarOutputs() {
        RecordingJdbc recording = new RecordingJdbc()
                .callOutput(1, 23L)
                .callOutput(3, null);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder()
                .returns("total", Types.BIGINT, Long.class)
                .in(2)
                .out(3, "note", Types.VARCHAR, String.class)
                .build();

        FunctionResult result = client.create("{? = call TOTAL(?, ?)}")
                .bind(2, "group-a")
                .call(layout, JdbcResultRequest.call(call -> {
                    call.results().discard();
                    return new FunctionResult(call.outputs().required(1, Long.class),
                                              call.outputs().optional("note", String.class));
                }));

        assertEquals(new FunctionResult(23L, Optional.empty()), result);
        assertThat(recording.events(),
                   hasItems("call.register:1:" + Types.BIGINT,
                            "call.bind:2:group-a",
                            "call.register:3:" + Types.VARCHAR));
    }

    @Test
    void returnsDetachedScalarOutputsAfterClosingJdbcResources() {
        byte[] source = {1, 2, 3};
        RecordingJdbc recording = new RecordingJdbc()
                .callOutput(1, 23L)
                .callOutput(2, null)
                .callOutput(3, source);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder()
                .out(1, "total", Types.BIGINT, Long.class)
                .out(2, "note", Types.VARCHAR, String.class)
                .out(3, "payload", Types.VARBINARY, byte[].class)
                .build();

        JdbcClient.CallOutputValues outputs = client.create("{call TOTAL(?, ?, ?)}")
                .callForOutputs(layout);

        assertThat(recording.events().indexOf("call.output:3:typed"), lessThan(recording.events().indexOf("call.close")));
        assertThat(recording.events().indexOf("call.close"), lessThan(recording.events().indexOf("connection.close")));
        assertEquals(23L, outputs.required("total", Long.class));
        assertEquals(Optional.empty(), outputs.optional(2, String.class));
        source[0] = 9;
        byte[] payload = outputs.required("payload", byte[].class);
        assertEquals(1, payload[0]);
        payload[0] = 8;
        assertEquals(1, outputs.required("payload", byte[].class)[0]);
        assertThrows(IllegalArgumentException.class, () -> outputs.required("total", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> outputs.required("missing", String.class));
    }

    @Test
    void detachedOutputsRejectResourceBearingCallShapes() {
        RecordingJdbc direct = new RecordingJdbc()
                .callRows("unexpected")
                .callOutput(1, "COMPLETE");
        JdbcCall scalar = JdbcCall.builder().out(1, "status", Types.VARCHAR, String.class).build();

        DataException directFailure = assertThrows(DataException.class,
                                                   () -> new JdbcClientImpl(direct.dataSource())
                                                           .create("{call PROCESS(?)}")
                                                           .callForOutputs(scalar));

        assertTrue(directFailure.getMessage().contains("callback-scoped call"));
        assertThat(direct.events().indexOf("call.result.0.close"), lessThan(direct.events().indexOf("call.close")));

        RecordingJdbc cursor = new RecordingJdbc();
        JdbcCall cursorLayout = JdbcCall.builder().cursor(1, "rows").build();
        assertThrows(IllegalArgumentException.class,
                     () -> new JdbcClientImpl(cursor.dataSource())
                             .create("{call PROCESS(?)}")
                             .callForOutputs(cursorLayout));
        assertTrue(cursor.events().isEmpty());
    }

    @Test
    void supportsInputOnlyCallsAndRejectsUnexpectedChannels() {
        JdbcCall layout = JdbcCall.builder().in(1).build();
        RecordingJdbc success = new RecordingJdbc();

        new JdbcClientImpl(success.dataSource())
                .create("{call NOTIFY(?)}")
                .bind(1, "ready")
                .call(layout);

        assertThat(success.events(), hasItems("call.bind:1:ready", "call.execute", "call.close", "connection.close"));

        RecordingJdbc unexpected = new RecordingJdbc().callRows("unexpected");
        DataException failure = assertThrows(DataException.class,
                                             () -> new JdbcClientImpl(unexpected.dataSource())
                                                     .create("{call NOTIFY(?)}")
                                                     .bind(1, "ready")
                                                     .call(layout));

        assertTrue(failure.getMessage().contains("unexpected result channel"));
        assertThat(unexpected.events().indexOf("call.result.0.close"),
                   lessThan(unexpected.events().indexOf("call.close")));
    }

    @Test
    void validatesCallableLayoutBeforeBorrowingAConnection() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall missingPosition = JdbcCall.builder()
                .in(1)
                .out(3, "status", Types.VARCHAR, String.class)
                .build();

        assertThrows(IllegalArgumentException.class,
                     () -> client.create("{call OUTPUT(?)}")
                             .call(JdbcCall.builder()
                                           .out(1, "status", Types.VARCHAR, String.class)
                                           .build()));

        assertThrows(IllegalStateException.class,
                     () -> client.create("{call BROKEN(?, ?, ?)}")
                             .bind(1, "value")
                             .call(missingPosition, JdbcResultRequest.call(call -> {
                                 call.results().discard();
                             })));
        assertTrue(recording.events().isEmpty());
    }

    @Test
    void enforcesResultPhasesAndCleansUnconsumedCursors() {
        RecordingJdbc recording = new RecordingJdbc()
                .callCursor(1, "one")
                .callOutput(2, "done");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder()
                .cursor(1, "entries")
                .out(2, "status", Types.VARCHAR, String.class)
                .build();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                                                      () -> client.create("{call READ(?, ?)}")
                                                              .call(layout, JdbcResultRequest.call(call -> {
                                                                  call.outputs().required("status", String.class);
                                                              })));

        assertTrue(failure.getMessage().contains("direct results"));
        assertThat(recording.events(), hasItems("call.output:1", "call.cursor.1.close"));
        assertThat(recording.events().indexOf("call.cursor.1.close"),
                   lessThan(recording.events().indexOf("call.close")));
    }

    @Test
    void closesCursorAfterEarlyTraversalAndInvalidatesRetainedViews() {
        RecordingJdbc recording = new RecordingJdbc().callCursor(1, "first", "second");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder().cursor(1, "entries").build();
        AtomicReference<JdbcClient.CallScope> retainedScope = new AtomicReference<>();
        AtomicReference<JdbcClient.ScopedRows<String>> retainedRows = new AtomicReference<>();

        boolean exhausted = client.create("{call READ(?)}")
                .call(layout, JdbcResultRequest.call(call -> {
                    retainedScope.set(call);
                    call.results().discard();
                    JdbcClient.ScopedRows<String> rows = call.outputs().cursor("entries").map(String.class);
                    retainedRows.set(rows);
                    return rows.visitWhile(JdbcResultRequest.visitWhile(ignored -> false));
                }));

        assertFalse(exhausted);
        assertEquals(1, recording.events().stream().filter("call.cursor.1.next"::equals).count());
        assertThrows(IllegalStateException.class, () -> retainedScope.get().outputs());
        assertThrows(IllegalStateException.class, retainedRows.get()::list);
        assertThat(recording.events().indexOf("call.cursor.1.close"),
                   lessThan(recording.events().indexOf("call.close")));
    }

    @Test
    void requiresEveryDirectRowChannelAndCursorToBeConsumed() {
        RecordingJdbc directRecording = new RecordingJdbc().callRows("row");
        JdbcClient directClient = new JdbcClientImpl(directRecording.dataSource());

        IllegalStateException directFailure = assertThrows(IllegalStateException.class,
                                                            () -> directClient.create("{call READ()} ")
                                                                    .call(JdbcCall.builder().build(),
                                                                          JdbcResultRequest.call(call -> {
                                                                              call.results().visit((index, rows) -> { });
                                                                          })));
        assertTrue(directFailure.getMessage().contains("must be consumed or discarded"));
        assertThat(directRecording.events().indexOf("call.result.0.close"),
                   lessThan(directRecording.events().indexOf("call.close")));

        RecordingJdbc cursorRecording = new RecordingJdbc().callCursor(1, "row");
        JdbcClient cursorClient = new JdbcClientImpl(cursorRecording.dataSource());
        JdbcCall cursorLayout = JdbcCall.builder().cursor(1, "entries").build();

        IllegalStateException cursorFailure = assertThrows(IllegalStateException.class,
                                                            () -> cursorClient.create("{call READ(?)}")
                                                                    .call(cursorLayout,
                                                                          JdbcResultRequest.call(call -> {
                                                                              call.results().discard();
                                                                          })));
        assertTrue(cursorFailure.getMessage().contains("must be consumed or discarded"));
        assertThat(cursorRecording.events(), hasItems("call.output:1", "call.cursor.1.close"));
    }

    @Test
    void preservesApplicationFailureAndClosesTheOpenCursor() {
        RecordingJdbc recording = new RecordingJdbc().callCursor(1, "row");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder().cursor(1, "entries").build();
        IllegalArgumentException expected = new IllegalArgumentException("consumer failed");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                                                        () -> client.create("{call READ(?)}")
                                                                .call(layout, JdbcResultRequest.call(call -> {
                                                                    call.results().discard();
                                                                    call.outputs()
                                                                            .cursor("entries")
                                                                            .map(String.class)
                                                                            .visitAll(JdbcResultRequest.visitAll(row -> {
                                                                                throw expected;
                                                                            }));
                                                                })));

        assertSame(expected, actual);
        assertThat(recording.events().indexOf("call.cursor.1.close"),
                   lessThan(recording.events().indexOf("call.close")));
        assertThat(recording.events().indexOf("call.close"),
                   lessThan(recording.events().indexOf("connection.close")));
    }

    @Test
    void capturesCursorWarningsBeforeClosingTheirResultSet() {
        SQLWarning warning = new SQLWarning("cursor warning", "01000", 17);
        RecordingJdbc recording = new RecordingJdbc()
                .callCursor(1, "row")
                .warnings(warning, null, null);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder().cursor(1, "entries").build();
        IllegalStateException expected = new IllegalStateException("callback failed after cursor consumption");
        JdbcResultRequest.Call request = JdbcResultRequest.call((JdbcResultRequest.CallConsumer) call -> {
            call.results().discard();
            call.outputs().cursor("entries").map(String.class).list();
            throw expected;
        });

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                     () -> client.create("{call READ(?)}")
                                                             .call(layout, request));

        assertSame(expected, actual);
        assertEquals(List.of(warning), List.of(actual.getSuppressed()));
        assertThat(recording.events().indexOf("call.cursor.1.clearWarnings"),
                   lessThan(recording.events().indexOf("call.cursor.1.close")));
    }

    @Test
    void rejectsLossyBigIntegerOutputConversionAndClosesResources() {
        RecordingJdbc recording = new RecordingJdbc().callOutput(1, new BigDecimal("12.5"));
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder().out(1, "total", Types.NUMERIC, BigInteger.class).build();

        DataException failure = assertThrows(DataException.class,
                                             () -> client.create("{call TOTAL(?)}")
                                                     .call(layout, JdbcResultRequest.call(call -> {
                                                         call.results().discard();
                                                         return call.outputs().required("total", BigInteger.class);
                                                     })));

        assertTrue(failure.getMessage().contains("cannot be converted to BigInteger"));
        assertThat(recording.events().indexOf("call.output:1"), lessThan(recording.events().indexOf("call.close")));
        assertThat(recording.events().indexOf("call.close"), lessThan(recording.events().indexOf("connection.close")));
    }

    @Test
    void closesCallableAndConnectionWhenCursorCloseFails() {
        SQLException closeFailure = new SQLException("cursor close failed", "08000", 83);
        RecordingJdbc recording = new RecordingJdbc()
                .callCursor(1, "row")
                .failResultClose(closeFailure);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcCall layout = JdbcCall.builder().cursor(1, "entries").build();

        DataException failure = assertThrows(DataException.class,
                                             () -> client.create("{call READ(?)}")
                                                     .call(layout, JdbcResultRequest.call(call -> {
                                                         call.results().discard();
                                                         call.outputs().cursor("entries").map(String.class).list();
                                                     })));

        assertSame(closeFailure, failure.getCause());
        assertTrue(recording.events().stream().filter("call.cursor.1.close"::equals).count() >= 2);
        assertThat(recording.events().indexOf("call.cursor.1.close"), lessThan(recording.events().indexOf("call.close")));
        assertThat(recording.events().indexOf("call.close"), lessThan(recording.events().indexOf("connection.close")));
    }

    @Test
    void callBuilderRejectsAmbiguousAndUnsupportedLayouts() {
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder().in(1).out(1, "status", Types.VARCHAR, String.class));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder()
                             .out(1, "status", Types.VARCHAR, String.class)
                             .out(2, "status", Types.INTEGER, Integer.class));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder().out(1, "value", Jdbc.INFERRED_TYPE, String.class));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder().out(1, "value", Types.BLOB, java.sql.Blob.class));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder().out(1, "value", Types.VARCHAR, String.class, " "));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcCall.builder().returns("value", Types.INTEGER, Integer.class)
                             .returns("other", Types.INTEGER, Integer.class));

        JdbcCall.Builder recoverable = JdbcCall.builder()
                .out(1, "status", Types.VARCHAR, String.class);
        assertThrows(IllegalArgumentException.class,
                     () -> recoverable.out(2, "status", Types.INTEGER, Integer.class));
        recoverable.out(2, "attempts", Types.INTEGER, Integer.class).build();

        JdbcCall reusableSnapshot = JdbcCall.builder().in(2).in(1, JDBCType.VARCHAR.getVendorTypeNumber()).build();
        RecordingJdbc recording = new RecordingJdbc();
        new JdbcClientImpl(recording.dataSource())
                .create("{call ORDERED(?, ?)}")
                .bind(1, "one")
                .bind(2, "two")
                .call(reusableSnapshot);
        assertThat(recording.events().indexOf("call.bind:1:one"),
                   lessThan(recording.events().indexOf("call.bind:2:two")));
    }

    private record CallResult(int attempts, String status) {
    }

    private record FunctionResult(long total, Optional<String> note) {
    }
}
