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
                            .forEachWhile(ignored -> false));

        List<String> events = recording.events();
        assertEquals(1, events.stream().filter("result.next"::equals).count());
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
    }

    @Test
    void withRowsBreakAndCallbackReturnCloseWithoutReadingAhead() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second", "third");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        List<String> consumed = new ArrayList<>();

        client.create("select VALUE from TEST")
                .map(String.class)
                .withRows(rows -> {
                    for (String row : rows) {
                        consumed.add(row);
                        break;
                    }
                });

        assertEquals(List.of("first"), consumed);
        List<String> events = recording.events();
        assertEquals(1, events.stream().filter("result.next"::equals).count());
        assertClosesInOwnershipOrder(events);
    }

    @Test
    void withRowsCallbackMayReturnWithoutCreatingAnIterator() {
        RecordingJdbc recording = new RecordingJdbc().rows("unread");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        client.create("select VALUE from TEST").map(String.class).withRows(rows -> { });

        List<String> events = recording.events();
        assertEquals(0, events.stream().filter("result.next"::equals).count());
        assertClosesInOwnershipOrder(events);
    }

    @Test
    void pushTraversalExhaustsRowsWithoutMaterializingAResultList() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second", "third");
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        List<String> consumed = new ArrayList<>();

        client.create("select VALUE from TEST").map(String.class).forEach(consumed::add);

        assertEquals(List.of("first", "second", "third"), consumed);
        assertEquals(4, recording.events().stream().filter("result.next"::equals).count());
        assertClosesInOwnershipOrder(recording.events());
    }

    @Test
    void appliesOptionsAndBindsOnlyWhenTerminalStarts() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        JdbcClient.Rows<String> rows = client.create("select VALUE from TEST where A = ? or B = ?")
                .options(JdbcExecutionOptions.builder()
                                 .fetchSize(37)
                                 .queryTimeout(Duration.ofSeconds(4))
                                 .maxRows(91)
                                 .build())
                .bind(1, "value")
                .bindNull(2, JDBCType.VARCHAR)
                .map(String.class);

        assertTrue(recording.events().isEmpty(), "Building a statement must not perform JDBC I/O");
        assertEquals(List.of("row"), rows.list());

        List<String> events = recording.events();
        assertTrue(events.contains("statement.fetchSize:37"), events::toString);
        assertTrue(events.contains("statement.queryTimeout:4"), events::toString);
        assertTrue(events.contains("statement.maxRows:91"), events::toString);
        assertTrue(events.contains("statement.bind:1:value"), events::toString);
        assertTrue(events.contains("statement.bindNull:2:" + JDBCType.VARCHAR.getVendorTypeNumber()), events::toString);
        assertTrue(events.indexOf("statement.prepare") < events.indexOf("statement.fetchSize:37"), events::toString);
        assertTrue(events.indexOf("statement.bindNull:2:" + JDBCType.VARCHAR.getVendorTypeNumber())
                           < events.indexOf("statement.execute"), events::toString);
    }

    @Test
    void callbackFailureRemainsPrimaryAndClosesEveryResource() {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());
        IllegalStateException expected = new IllegalStateException("callback failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                    () -> client.create("select VALUE from TEST")
                                                            .map(String.class)
                                                            .forEach(ignored -> {
                                                                throw expected;
                                                            }));

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
                                                                .forEach(ignored -> { }));

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
                                                            .forEachWhile(ignored -> {
                                                                throw expected;
                                                            }));

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
                                                    .forEach(ignored -> { }));

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
                                                            .withRows(rows -> {
                                                                throw expected;
                                                            }));

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
                                .forEachWhile(value -> {
                                    consumed.incrementAndGet();
                                    return false;
                                }));
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
        assertThrows(IllegalStateException.class, () -> retained.get().get(1, String.class));
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
}
