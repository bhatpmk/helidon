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

import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.data.DataException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcResourceOwnershipTest {

    @Test
    void normalExhaustionAdvancesThenClosesInOwnershipOrder() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        assertEquals(List.of("row"), client.create("select VALUE from TEST").map(String.class).list());

        List<String> events = recording.events();
        assertTrue(events.indexOf("statement.moreResults:" + Statement.CLOSE_CURRENT_RESULT)
                           < events.indexOf("result.close"), events::toString);
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
    }

    @Test
    void earlyPredicateStopStillClosesEveryOwnedResource() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        assertFalse(client.create("select VALUE from TEST")
                            .map(String.class)
                            .visitWhile(JdbcResultRequest.visitWhile(ignored -> false)));

        List<String> events = recording.events();
        assertEquals(1, events.stream().filter("result.next"::equals).count());
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
    }

    @Test
    void pushTraversalExhaustsRowsWithoutMaterializingAResultList() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second", "third");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        List<String> consumed = new ArrayList<>();

        client.create("select VALUE from TEST")
                .map(String.class)
                .visitAll(JdbcResultRequest.visitAll(consumed::add));

        assertEquals(List.of("first", "second", "third"), consumed);
        assertEquals(4, recording.events().stream().filter("result.next"::equals).count());
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void appliesStatementOptionsBeforeQueryBinds() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcClient.Rows<String> rows = client.create("select VALUE from TEST where A = ? or B = ?")
                .options(JdbcStatementOptions.builder()
                                 .fetchSize(37)
                                 .queryTimeout(Duration.ofSeconds(4))
                                 .maxRows(91)
                                 .poolableHint(true)
                                 .build())
                .bind(1, "value")
                .bindNull(2, JDBCType.VARCHAR)
                .map(String.class);

        assertThat(recording.events(), is(empty()));
        assertThat(rows.list(), is(List.of("row")));

        List<String> events = recording.events();
        assertThat(events,
                   hasItems("statement.fetchSize:37",
                            "statement.maxRows:91",
                            "statement.queryTimeout:4",
                            "statement.poolable:true",
                            "statement.bind:1:value",
                            "statement.bindNull:2:" + JDBCType.VARCHAR.getVendorTypeNumber()));
        assertThat(events.indexOf("statement.prepare"), lessThan(events.indexOf("statement.fetchSize:37")));
        assertThat(events.indexOf("statement.fetchSize:37"), lessThan(events.indexOf("statement.maxRows:91")));
        assertThat(events.indexOf("statement.maxRows:91"), lessThan(events.indexOf("statement.queryTimeout:4")));
        assertThat(events.indexOf("statement.queryTimeout:4"), lessThan(events.indexOf("statement.poolable:true")));
        assertThat(events.indexOf("statement.poolable:true"), lessThan(events.indexOf("statement.bind:1:value")));
        assertThat(events.indexOf("statement.bindNull:2:" + JDBCType.VARCHAR.getVendorTypeNumber()),
                   lessThan(events.indexOf("statement.execute")));
    }

    @Test
    void appliesStatementOptionsToUpdatesThroughTheSharedRunner() {
        RecordingJdbc recording = new RecordingJdbc().updateCount(7);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        long count = client.create("update TEST set VALUE = ?")
                .options(JdbcStatementOptions.builder().fetchSize(5).maxRows(11).build())
                .bind(1, "updated")
                .execute();

        assertThat(count, is(7L));
        List<String> events = recording.events();
        assertThat(events, hasItems("statement.fetchSize:5", "statement.maxRows:11", "statement.bind:1:updated"));
        assertThat(events.indexOf("statement.maxRows:11"), lessThan(events.indexOf("statement.bind:1:updated")));
        assertThat(events.indexOf("statement.bind:1:updated"), lessThan(events.indexOf("statement.execute")));
        assertClosesInOwnershipOrderWithoutResult(events);
    }

    @Test
    void callbackRequestComposesStatementOptionsWithoutOwningResources() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        List<String> consumed = new ArrayList<>();
        JdbcResultRequest.VisitAll<String> request = JdbcResultRequest.<String>visitAll(consumed::add)
                .withOptions(JdbcStatementOptions.builder().fetchSize(17).poolableHint(false).build());

        client.create("select VALUE from TEST")
                .options(JdbcStatementOptions.builder().fetchSize(3).maxRows(41).build())
                .map(String.class)
                .visitAll(request);

        assertThat(consumed, is(List.of("first", "second")));
        assertThat(recording.events(),
                   hasItems("statement.fetchSize:17", "statement.maxRows:41", "statement.poolable:false"));
        assertThat(recording.events().indexOf("statement.poolable:false"),
                   lessThan(recording.events().indexOf("statement.execute")));
    }

    @Test
    void fallsBackToLegacyMaximumRowsWithoutNarrowing() {
        RecordingJdbc recording = new RecordingJdbc().largeMaxRowsUnsupported();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        assertThat(client.create("select VALUE from TEST")
                           .options(JdbcStatementOptions.builder().maxRows(23).build())
                           .map(String.class)
                           .list(),
                   is(List.of("row")));

        assertThat(recording.events(), hasItems("statement.maxRows:23", "statement.legacyMaxRows:23"));
    }

    @Test
    void rejectsAnUnrepresentableLegacyMaximumRowsFallback() {
        RecordingJdbc recording = new RecordingJdbc().largeMaxRowsUnsupported();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        DataException failure = assertThrows(DataException.class,
                                             () -> client.create("select VALUE from TEST")
                                                     .options(JdbcStatementOptions.builder()
                                                                      .maxRows((long) Integer.MAX_VALUE + 1)
                                                                      .build())
                                                     .map(String.class)
                                                     .list());

        assertThat(failure.getCause(), instanceOf(java.sql.SQLFeatureNotSupportedException.class));
        assertThat(recording.events().contains("statement.legacyMaxRows:" + ((long) Integer.MAX_VALUE + 1)), is(false));
        assertClosesInOwnershipOrderWithoutResult(recording.events());
    }

    @Test
    void optionFailureIsTranslatedAndClosesThePreparedResources() {
        SQLException optionFailure = new SQLException("fetch size failed", "42000", 91);
        RecordingJdbc recording = new RecordingJdbc().failFetchSize(optionFailure);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        DataException failure = assertThrows(DataException.class,
                                             () -> client.create("select VALUE from TEST")
                                                     .options(JdbcStatementOptions.builder().fetchSize(9).build())
                                                     .map(String.class)
                                                     .list());

        assertThat(failure.getCause(), sameInstance(optionFailure));
        assertThat(recording.events().contains("statement.execute"), is(false));
        assertClosesInOwnershipOrderWithoutResult(recording.events());
    }

    @Test
    void directQueryRequestDoesNotApplyUnsetStatementSettings() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        client.create("select VALUE from TEST")
                .map(String.class)
                .visitAll(JdbcResultRequest.visitAll(ignored -> { }));

        assertFalse(recording.events().stream().anyMatch(event -> event.startsWith("statement.fetchSize:")));
        assertFalse(recording.events().stream().anyMatch(event -> event.startsWith("statement.queryTimeout:")));
        assertFalse(recording.events().stream().anyMatch(event -> event.startsWith("statement.maxRows:")));
        assertFalse(recording.events().stream().anyMatch(event -> event.startsWith("statement.poolable:")));
    }

    @Test
    void preservesDriverPoolableDefaultWhenHintIsUnset() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        assertEquals(List.of("row"), client.create("select VALUE from TEST").map(String.class).list());

        assertFalse(recording.events().stream().anyMatch(event -> event.startsWith("statement.poolable:")),
                    recording.events()::toString);
    }

    @Test
    void callbackFailureRemainsPrimaryAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalStateException expected = new IllegalStateException("callback failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                    () -> client.create("select VALUE from TEST")
                                                            .map(String.class)
                                                            .visitAll(JdbcResultRequest.visitAll(ignored -> {
                                                                throw expected;
                                                            })));

        assertSame(expected, actual);
        List<String> events = recording.events();
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
    }

    @Test
    void mapperFailureRemainsPrimaryAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalArgumentException expected = new IllegalArgumentException("mapper failed");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                                                        () -> client.create("select VALUE from TEST")
                                                                .map(row -> {
                                                                    throw expected;
                                                                })
                                                                .visitAll(JdbcResultRequest.visitAll(ignored -> { })));

        assertSame(expected, actual);
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void predicateFailureRemainsPrimaryAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalStateException expected = new IllegalStateException("predicate failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                    () -> client.create("select VALUE from TEST")
                                                            .map(String.class)
                                                            .visitWhile(JdbcResultRequest.visitWhile(ignored -> {
                                                                throw expected;
                                                            })));

        assertSame(expected, actual);
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void sqlFailurePreservesDetailsWarningsAndClosesEveryResource() {
        SQLException readFailure = new SQLException("read failed", "42000", 73);
        SQLWarning resultWarning = new SQLWarning("result warning", "01000", 1);
        SQLWarning statementWarning = new SQLWarning("statement warning", "01000", 2);
        SQLWarning connectionWarning = new SQLWarning("connection warning", "01000", 3);
        RecordingJdbc recording = new RecordingJdbc()
                .failResultNext(readFailure)
                .warnings(resultWarning, statementWarning, connectionWarning);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        DataException actual = assertThrows(DataException.class,
                                            () -> client.create("select VALUE from TEST")
                                                    .map(String.class)
                                                    .visitAll(JdbcResultRequest.visitAll(ignored -> { })));

        assertSame(readFailure, actual.getCause());
        assertTrue(actual.getMessage().contains("SQLState=42000"));
        assertTrue(actual.getMessage().contains("vendorCode=73"));
        assertEquals(List.of(resultWarning, statementWarning, connectionWarning),
                     List.of(readFailure.getSuppressed()));
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void cleanupFailuresAreSuppressedOnTheCallbackFailure() {
        SQLException resultClose = new SQLException("result close failed", "08000", 81);
        SQLException statementClose = new SQLException("statement close failed", "08000", 82);
        SQLException connectionClose = new SQLException("connection close failed", "08000", 83);
        RecordingJdbc recording = new RecordingJdbc()
                .failResultClose(resultClose)
                .failStatementClose(statementClose)
                .failConnectionClose(connectionClose);
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalStateException expected = new IllegalStateException("callback failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                    () -> client.create("select VALUE from TEST")
                                                            .map(String.class)
                                                            .visitAll(JdbcResultRequest.visitAll(row -> {
                                                                throw expected;
                                                            })));

        assertSame(expected, actual);
        assertEquals(1, actual.getSuppressed().length);
        DataException cleanup = assertInstanceOf(DataException.class, actual.getSuppressed()[0]);
        assertSame(resultClose, cleanup.getCause());
        assertEquals(List.of(statementClose, connectionClose), List.of(resultClose.getSuppressed()));
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void repeatedEarlyTraversalDoesNotLeakOwnedConnections() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        AtomicInteger consumed = new AtomicInteger();

        for (int i = 0; i < 20; i++) {
            assertFalse(client.create("select VALUE from TEST")
                                .map(String.class)
                                .visitWhile(JdbcResultRequest.visitWhile(value -> {
                                    consumed.incrementAndGet();
                                    return false;
                                })));
        }

        assertEquals(20, consumed.get());
        assertEquals(20, recording.events().stream().filter("connection.acquire"::equals).count());
        assertEquals(20, recording.events().stream().filter("connection.close"::equals).count());
    }

    @Test
    void reducerConsumesEveryRowAndCannotRetainTheRowView() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second", "third");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<JdbcClient.Row> retained = new AtomicReference<>();

        int result = client.create("select VALUE from TEST").reduce(new JdbcClient.RowReducer<>() {
            @Override
            public void accept(JdbcClient.Row row) {
                retained.set(row);
                row.required(1, String.class);
                accepted.incrementAndGet();
            }

            @Override
            public Integer finish() {
                return accepted.get();
            }
        });

        assertEquals(3, result);
        assertThrows(IllegalStateException.class, () -> retained.get().optional(1, String.class));
        assertEquals(4, recording.events().stream().filter("result.next"::equals).count());
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void reducerAcceptFailureSkipsFinishAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalStateException expected = new IllegalStateException("accept failed");
        AtomicInteger finishes = new AtomicInteger();

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                    () -> client.create("select VALUE from TEST")
                                                            .reduce(new JdbcClient.RowReducer<>() {
                                                                @Override
                                                                public void accept(JdbcClient.Row row) {
                                                                    throw expected;
                                                                }

                                                                @Override
                                                                public Object finish() {
                                                                    finishes.incrementAndGet();
                                                                    return null;
                                                                }
                                                            }));

        assertSame(expected, actual);
        assertEquals(0, finishes.get());
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void reducerFinishFailureRemainsPrimaryAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalArgumentException expected = new IllegalArgumentException("finish failed");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                                                        () -> client.create("select VALUE from TEST")
                                                                .reduce(new JdbcClient.RowReducer<>() {
                                                                    @Override
                                                                    public void accept(JdbcClient.Row row) {
                                                                    }

                                                                    @Override
                                                                    public Object finish() {
                                                                        throw expected;
                                                                    }
                                                                }));

        assertSame(expected, actual);
        assertClosesInOwnershipOrder(recording.events());
    }

    private static void assertClosesInOwnershipOrder(List<String> events) {
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
    }

    private static void assertClosesInOwnershipOrderWithoutResult(List<String> events) {
        assertThat(events.indexOf("statement.close"), lessThan(events.indexOf("connection.close")));
    }
}
