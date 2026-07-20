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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import io.helidon.data.DataException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTransactionConnectionManagerTest {

    @Test
    void reusesAndClosesTransactionOwnedConnectionAtCommit() throws Exception {
        JdbcDataSource dataSource = initializedDataSource("tx_commit");
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jdbc");
        manager.begin("tx-1");

        JdbcConnectionLease first = manager.acquire(dataSource);
        Connection physical = first.connection();
        first.close();
        assertFalse(physical.isClosed());
        try (JdbcConnectionLease second = manager.acquire(dataSource)) {
            assertSame(physical, second.connection());
        }

        JdbcClient client = new JdbcClientImpl(dataSource, manager);
        assertEquals(1, client.create("INSERT INTO ITEMS VALUES (?)").bind(1, 1).execute());
        manager.commit("tx-1");
        manager.end();

        assertTrue(physical.isClosed());
        assertEquals(1, count(dataSource));
    }

    @Test
    void rollsBackAndRejectsASecondDatasource() throws Exception {
        JdbcDataSource firstDataSource = initializedDataSource("tx_rollback");
        JdbcDataSource secondDataSource = initializedDataSource("tx_other");
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jdbc");
        manager.begin("tx-2");
        JdbcClient client = new JdbcClientImpl(firstDataSource, manager);
        client.create("INSERT INTO ITEMS VALUES (?)").bind(1, 1).execute();

        assertThrows(DataException.class, () -> manager.acquire(secondDataSource));
        manager.rollback("tx-2");
        manager.end();

        assertEquals(0, count(firstDataSource));
    }

    @Test
    void failsFastInsideForeignTransaction() {
        JdbcDataSource dataSource = initializedDataSource("tx_foreign");
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jta");
        manager.begin("foreign-1");

        assertThrows(DataException.class, () -> manager.acquire(dataSource));

        manager.rollback("foreign-1");
        manager.end();
    }

    @Test
    void borrowsLazilyAndRestoresOwnershipAtCompletion() throws Exception {
        RecordingJdbc recording = new RecordingJdbc();
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jdbc");
        manager.begin("empty");
        manager.commit("empty");
        manager.end();
        assertTrue(recording.events().isEmpty(), "An empty transaction must not borrow a connection");

        manager.start("jdbc");
        manager.begin("used");
        try (JdbcConnectionLease ignored = manager.acquire(recording.dataSource())) {
            // Logical operation close must leave physical ownership with the transaction manager.
        }
        assertTrue(!recording.events().contains("connection.close"));
        manager.commit("used");
        manager.end();

        assertEquals(List.of("connection.acquire",
                             "connection.autoCommit:false",
                             "connection.commit",
                             "connection.autoCommit:true",
                             "connection.close"),
                     recording.events());
    }

    @Test
    void streamingTerminalsCloseRowsAndStatementsButLeaveTransactionConnectionOpen() {
        RecordingJdbc recording = new RecordingJdbc().rows("first", "second");
        var dataSource = recording.dataSource();
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jdbc");
        manager.begin("streaming");
        JdbcClient client = new JdbcClientImpl(dataSource, manager);

        List<String> pushed = new ArrayList<>();
        client.create("select VALUE from TEST")
                .map(String.class)
                .visitAll(JdbcResultRequest.visitAll(pushed::add));
        assertFalse(client.create("select VALUE from TEST")
                            .map(String.class)
                            .visitWhile(JdbcResultRequest.visitWhile(value -> false)));
        List<String> materialized = client.create("select VALUE from TEST")
                .map(String.class)
                .list();
        int reduced = client.create("select VALUE from TEST").reduce(new JdbcClient.RowReducer<>() {
            private int count;

            @Override
            public void accept(JdbcClient.Row row) {
                row.required(1, String.class);
                count++;
            }

            @Override
            public Integer finish() {
                return count;
            }
        });

        assertEquals(List.of("first", "second"), pushed);
        assertEquals(List.of("first", "second"), materialized);
        assertEquals(2, reduced);
        assertEquals(4, recording.events().stream().filter("result.close"::equals).count());
        assertEquals(4, recording.events().stream().filter("statement.close"::equals).count());
        assertFalse(recording.events().contains("connection.close"));

        manager.commit("streaming");
        manager.end();

        assertEquals(1, recording.events().stream().filter("connection.close"::equals).count());
        assertTrue(recording.events().indexOf("statement.close")
                           < recording.events().indexOf("connection.commit"));
    }

    @Test
    void callableTerminalUsesTheTransactionOwnedConnection() {
        RecordingJdbc recording = new RecordingJdbc();
        var dataSource = recording.dataSource();
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        manager.start("jdbc");
        manager.begin("call");
        JdbcClient client = new JdbcClientImpl(dataSource, manager);

        client.create("{call NOTIFY(?)}")
                .bind(1, "ready")
                .call(JdbcCall.builder().in(1).build());

        assertEquals(1, recording.events().stream().filter("call.close"::equals).count());
        assertFalse(recording.events().contains("connection.close"));

        manager.commit("call");
        manager.end();

        assertEquals(1, recording.events().stream().filter("connection.close"::equals).count());
        assertTrue(recording.events().indexOf("call.close") < recording.events().indexOf("connection.commit"));
    }

    private static JdbcDataSource initializedDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY)");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return dataSource;
    }

    private static int count(JdbcDataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            result.next();
            return result.getInt(1);
        }
    }
}
