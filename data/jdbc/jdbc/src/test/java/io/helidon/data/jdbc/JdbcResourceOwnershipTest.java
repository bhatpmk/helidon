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
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        RecordingJdbc recording = new RecordingJdbc();
        JdbcClient client = new JdbcClientImpl(recording.dataSource());

        assertTrue(!client.create("select VALUE from TEST")
                .map(String.class)
                .forEachWhile(ignored -> false));

        List<String> events = recording.events();
        assertEquals(1, events.stream().filter("result.next"::equals).count());
        assertTrue(events.indexOf("result.close") < events.indexOf("statement.close"), events::toString);
        assertTrue(events.indexOf("statement.close") < events.indexOf("connection.close"), events::toString);
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
}
