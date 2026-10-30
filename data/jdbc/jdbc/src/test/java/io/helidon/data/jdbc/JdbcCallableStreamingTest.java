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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCallableStreamingTest {
    private DataSource source;
    private Connection connection;
    private CallableStatement statement;
    private JdbcClient client;

    @BeforeEach
    void setup() throws Exception {
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(CallableStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareCall(anyString())).thenReturn(statement);
        when(statement.getLargeUpdateCount()).thenReturn(-1L);
        client = JdbcTestClients.create(source);
    }

    /**
     * Cursor traversal maps on demand and closes the channel before enclosing call cleanup.
     */
    @Test
    void streamsCursorWithoutExecutingAgain() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        AtomicInteger mapped = new AtomicInteger();
        call().consume(result -> {
            JdbcClient.Rows<Integer> rows = result.cursor("rows", row -> {
                mapped.incrementAndGet();
                return row.get(1, Integer.class);
            });
            rows.useStream(stream -> {
                assertThat(mapped.get(), is(0));
                assertThat(stream.isParallel(), is(false));
                assertThat(stream.toList(), is(List.of(42, 42)));
            });
            assertThrows(IllegalStateException.class, rows::list);
            assertThrows(NullPointerException.class, () -> rows.useStream(null));
            try {
                verify(cursor).close();
                verify(statement, never()).close();
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        });
        assertThat(mapped.get(), is(2));
        verify(statement).execute();
        verify(statement, never()).executeQuery();
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * Short traversal, no traversal, and explicit closure never map discarded rows.
     */
    @Test
    void closesPartiallyConsumedAndUnvisitedCursors() throws Exception {
        List<Consumer<Stream<Integer>>> actions = List.of(
                stream -> assertThat(stream.findFirst(), is(Optional.of(42))),
                _ -> { },
                Stream::close);
        for (int index = 0; index < actions.size(); index++) {
            ResultSet cursor = cursor(42);
            when(statement.getObject(1)).thenReturn(cursor);
            AtomicInteger mapped = new AtomicInteger();
            Consumer<Stream<Integer>> action = actions.get(index);
            call().consume(result -> result.cursor("rows", row -> {
                mapped.incrementAndGet();
                return row.get(1, Integer.class);
            }).useStream(action));
            assertThat(mapped.get(), is(index == 0 ? 1 : 0));
            verify(cursor).close();
            verify(cursor, times(index == 0 ? 1 : 0)).next();
        }
    }

    /**
     * Direct streams leave update counts and later row events available in encounter order.
     */
    @Test
    void traversesDirectRowsAndUpdateCounts() throws Exception {
        ResultSet first = cursor(42);
        ResultSet second = cursor(43);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(first, second);
        when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(false, true, false);
        when(statement.getLargeUpdateCount()).thenReturn(0L, -1L);
        client.create("{call routine()}").call().consume(result -> {
            result.rows(row -> row.get(1, Integer.class)).useStream(stream -> {
                assertThat(stream.findFirst(), is(Optional.of(42)));
                assertThrows(IllegalStateException.class, result::next);
            });
            result.next();
            assertThat(result.updateCount(), is(0L));
            result.next();
            result.rows(row -> row.get(1, Integer.class)).useStream(stream ->
                    assertThat(stream.toList(), is(List.of(43, 43))));
            result.skipRemainingResults();
            assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
        });
        verify(first).close();
        verify(second).close();
        verify(statement).execute();
    }

    /**
     * Each cursor finishes before another output can be selected, including after explicit stream closure.
     */
    @Test
    void consumesSequentialCursorsAndLaterOutputs() throws Exception {
        ResultSet first = cursor(42);
        ResultSet second = cursor(43);
        when(statement.getObject(1)).thenReturn(first);
        when(statement.getObject(2)).thenReturn(second);
        when(statement.getInt(3)).thenReturn(7);
        client.create("{call routine(?, ?, ?)}").call()
                .out("first", 1, JDBCType.REF_CURSOR).out("second", 2, JDBCType.REF_CURSOR)
                .out("value", 3, JDBCType.INTEGER).consume(result -> {
                    assertThat(result.get("value", Integer.class), is(7));
                    result.cursor("first", row -> row.get(1, Integer.class)).useStream(stream -> {
                        stream.close();
                        assertThrows(IllegalStateException.class, () -> result.optionalCursor("second",
                                row -> row.get(1, Integer.class)));
                    });
                    result.optionalCursor("second", row -> row.get(1, Integer.class)).orElseThrow()
                            .useStream(stream -> assertThat(stream.count(), is(2L)));
                    assertThat(result.get("value", Integer.class), is(7));
                });
        verify(first).close();
        verify(second).close();
    }

    /**
     * SQL NULL cursors remain distinct from present cursors with no rows.
     */
    @Test
    void distinguishesAbsentEmptyAndRequiredNullCursors() throws Exception {
        call().consume(result -> assertThat(result.optionalCursor("rows", row -> row.get(1, Integer.class)),
                                          is(Optional.empty())));
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> call().consume(result -> result.cursor("rows",
                row -> row.get(1, Integer.class)).useStream(_ -> invoked.set(true))));
        assertThat(invoked.get(), is(false));
        ResultSet empty = cursor(42);
        when(empty.next()).thenReturn(false);
        when(statement.getObject(1)).thenReturn(empty);
        call().consume(result -> result.optionalCursor("rows", row -> row.get(1, Integer.class)).orElseThrow()
                .useStream(stream -> assertThat(stream.toList(), is(List.of()))));
        verify(empty).close();
    }

    /**
     * Off-thread traversal fails before JDBC and escaped lazy handles expire with the inner callback.
     */
    @Test
    void confinesStreamsAndExpiresRetainedHandles() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        AtomicReference<Stream<Integer>> escaped = new AtomicReference<>();
        AtomicReference<Iterator<Integer>> iterator = new AtomicReference<>();
        call().consume(result -> {
            result.cursor("rows", row -> row.get(1, Integer.class)).useStream(stream -> {
                CompletableFuture<Void> worker = CompletableFuture.runAsync(() ->
                        assertThrows(IllegalStateException.class, stream::findFirst));
                try {
                    worker.get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                } finally {
                    worker.cancel(true);
                }
                escaped.set(stream);
                iterator.set(stream.iterator());
            });
            assertThrows(IllegalStateException.class, iterator.get()::hasNext);
            assertThrows(IllegalStateException.class, escaped.get()::count);
        });
        assertThrows(IllegalStateException.class, iterator.get()::next);
        verify(cursor, never()).next();
    }

    /**
     * Rows expire before downstream stream operations receive their detached values.
     */
    @Test
    void expiresRowsBeforeDownstreamProcessing() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        AtomicReference<JdbcClient.Row> retained = new AtomicReference<>();
        call().consume(result -> result.cursor("rows", row -> {
            retained.set(row);
            return row.get(1, Integer.class);
        }).useStream(stream -> stream.forEach(value -> {
            assertThat(value, is(42));
            assertThrows(IllegalStateException.class, () -> retained.get().get(1, Integer.class));
        })));
    }

    /**
     * Catching a mapper error cannot replace it with a later callback error or reopen parent access.
     */
    @Test
    void retainsFirstErrorAcrossNestedCallbacks() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        Error first = new AssertionError("The mapper failed first.");
        Error later = new AssertionError("The callback failed later.");
        Error observed = assertThrows(Error.class, () -> call().consume(result -> {
            assertThat(assertThrows(Error.class, () -> result.cursor("rows", _ -> { throw first; })
                    .useStream(stream -> {
                        assertThat(assertThrows(Error.class, stream::count), sameInstance(first));
                        assertThrows(IllegalStateException.class, result::resultKind);
                    })), sameInstance(first));
            throw later;
        }));
        assertThat(observed, sameInstance(first));
        assertThat(observed.getSuppressed(), is(new Throwable[] {later}));
        verify(cursor).close();
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * A caught JDBC read failure remains primary even when the mapper returns a value.
     */
    @Test
    void retainsReadFailureCaughtInsideMapper() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        when(cursor.getInt(1)).thenThrow(new SQLException("secret-read", "22003", 42));
        AtomicReference<DataException> first = new AtomicReference<>();
        DataException observed = assertThrows(DataException.class, () -> call().consume(result ->
                result.cursor("rows", row -> {
                    first.set(assertThrows(DataException.class, () -> row.get(1, Integer.class)));
                    return 42;
                }).useStream(stream -> assertThrows(IllegalStateException.class, stream::count))));
        assertThat(observed, sameInstance(first.get()));
        verify(cursor, times(1)).next();
        verify(cursor).close();
    }

    /**
     * Close handlers all run in registration order and a caught handler failure still fails the parent call.
     */
    @Test
    void retainsCloseHandlerFailureAndSanitizesCleanup() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        doThrow(new SQLException("secret-close", "08000", 7)).when(cursor).close();
        IllegalArgumentException first = new IllegalArgumentException("The first close handler failed.");
        IllegalStateException second = new IllegalStateException("The second close handler failed.");
        List<Integer> closed = new ArrayList<>();
        RuntimeException observed = assertThrows(RuntimeException.class, () -> call().consume(result -> {
            assertThat(assertThrows(RuntimeException.class, () -> result.cursor("rows", row -> row.get(1, Integer.class))
                    .useStream(stream -> {
                        stream.onClose(() -> { closed.add(1); throw first; });
                        stream.onClose(() -> { closed.add(2); throw second; });
                        assertThat(assertThrows(RuntimeException.class, stream::close), sameInstance(first));
                        assertThrows(IllegalStateException.class, result::resultKind);
                    })), sameInstance(first));
        }));
        assertThat(observed, sameInstance(first));
        assertThat(closed, is(List.of(1, 2)));
        assertThat(first.getSuppressed().length, is(2));
        assertThat(first.getSuppressed()[0], sameInstance(second));
        StringWriter diagnostics = new StringWriter();
        first.printStackTrace(new PrintWriter(diagnostics));
        assertThat(diagnostics.toString(), not(containsString("secret-close")));
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * Metadata setup and unsupported cursor objects fail before invoking the stream consumer.
     */
    @Test
    void cleansUpSetupFailuresBeforeConsumerInvocation() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        when(cursor.getMetaData()).thenThrow(new SQLException("secret-metadata"));
        assertThrows(DataException.class, () -> call().consume(result -> result.cursor("rows",
                row -> row.get(1, Integer.class)).useStream(_ -> invoked.set(true))));
        verify(cursor).close();
        when(statement.getObject(1)).thenReturn("unsupported cursor object");
        assertThrows(DataException.class, () -> call().consume(result -> result.cursor("rows",
                row -> row.get(1, Integer.class)).useStream(_ -> invoked.set(true))));
        assertThat(invoked.get(), is(false));
        verify(statement, times(2)).close();
        verify(connection, times(2)).close();
    }

    /**
     * A null mapper result fails permanently even when the stream consumer catches it.
     */
    @Test
    void retainsNullMappingFailure() throws Exception {
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        assertThrows(DataException.class, () -> call().consume(result -> result.cursor("rows", _ -> null)
                .useStream(stream -> assertThrows(DataException.class, stream::count))));
    }

    /**
     * Reservations survive explicit stream closure and remain until the enclosing call completes cleanup.
     */
    @Test
    void reservesParentTransactionAndReleasesAfterCaughtFailure() throws Exception {
        AtomicBoolean automatic = new AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(_ -> automatic.get());
        doAnswer(invocation -> {
            automatic.set(invocation.getArgument(0));
            return null;
        }).when(connection).setAutoCommit(anyBoolean());
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        JdbcRunner runner = new JdbcRunner(source, manager);
        ResultSet cursor = cursor(42);
        when(statement.getObject(1)).thenReturn(cursor);
        manager.start("jdbc");
        manager.begin("call");
        IllegalArgumentException first = new IllegalArgumentException("The close handler failed.");
        assertThat(assertThrows(IllegalArgumentException.class, () -> new JdbcStatement(runner,
                "{call routine(?)}", 1).call().out("rows", 1, JDBCType.REF_CURSOR).consume(result -> {
                    assertThat(assertThrows(IllegalArgumentException.class, () -> result.cursor("rows",
                            row -> row.get(1, Integer.class)).useStream(stream -> {
                                stream.close();
                                assertThrows(IllegalStateException.class, () -> manager.acquire(source));
                                assertThrows(IllegalStateException.class, () -> manager.suspend("call"));
                                assertThrows(IllegalStateException.class, () -> manager.commit("call"));
                                throw first;
                            })), sameInstance(first));
                    assertThrows(IllegalStateException.class, () -> manager.acquire(source));
                })), sameInstance(first));
        try (JdbcConnectionLease released = manager.acquire(source)) {
            assertThat(released.connection(), sameInstance(connection));
        }
        assertThrows(TxException.class, () -> manager.commit("call"));
        manager.end();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private JdbcClient.Call call() {
        return client.create("{? = call routine()}").call().out("rows", 1, JDBCType.REF_CURSOR);
    }

    private ResultSet cursor(int value) throws Exception {
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(result.next()).thenReturn(true, true, false);
        when(result.getInt(1)).thenReturn(value);
        return result;
    }
}
