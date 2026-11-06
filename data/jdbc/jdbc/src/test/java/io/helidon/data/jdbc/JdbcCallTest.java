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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Calendar;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import io.helidon.data.DataException;
import io.helidon.data.NonUniqueResultException;
import io.helidon.transaction.TxException;

import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCallTest {
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
     * Staging and invalid snapshot declarations must never acquire a connection.
     */
    @Test
    void rejectsInvalidRegistrationsBeforeAcquisition() throws Exception {
        JdbcClient.Call call = client.create("{call routine(?)}").call();
        assertThrows(NullPointerException.class, () -> call.out(null, 0, JDBCType.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> call.out("x", 0, JDBCType.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> call.out("x", 1, JDBCType.INTEGER, 2));
        call.out("x", 1, JDBCType.REF_CURSOR);
        assertThrows(IllegalArgumentException.class, () -> call.out("x", 1, JDBCType.INTEGER));
        assertThrows(IllegalStateException.class, call::outputs);
        verify(source, never()).getConnection();
    }

    @Test
    void executesParameterlessCommandOnce() throws Exception {
        JdbcClient.Call call = client.create("{call routine()}").call();
        call.execute();
        assertThrows(IllegalStateException.class, call::execute);
        verify(statement).execute();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void bindsInputAndRegistersInoutWithScale() throws Exception {
        when(statement.getInt(1)).thenReturn(42);
        JdbcClient.OutParameters result = client.create("{call routine(?, ?)}").bind(1, 7).bind(2, "input")
                .call().out("value", 1, JDBCType.INTEGER).outputs();
        assertThat(result.get("value", int.class), is(42));
        verify(statement).setInt(1, 7);
        verify(statement).setString(2, "input");
        verify(statement).registerOutParameter(1, JDBCType.INTEGER.getVendorTypeNumber());
    }

    @Test
    void bindsTypedNullThroughExistingBridge() throws Exception {
        JdbcClient.Statement stage = client.create("{call routine(?)}");
        GeneratedJdbcData.bindNull(stage, 1, JDBCType.INTEGER);
        stage.call().execute();
        verify(statement).setNull(1, JDBCType.INTEGER.getVendorTypeNumber());
    }

    @Test
    void validatesCanonicalClassEvenForNull() throws Exception {
        when(statement.wasNull()).thenReturn(true);
        JdbcClient.OutParameters result = client.create("{? = call routine()}").call()
                .out("return", 1, JDBCType.INTEGER).outputs();
        assertThat(result.optional("return", Integer.class), is(Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> result.optional("return", Long.class));
        assertThrows(IllegalArgumentException.class, () -> result.get("return", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> result.get("missing", Integer.class));
    }

    @Test
    void copiesBinaryAtCaptureAndEveryRead() throws Exception {
        byte[] bytes = {1, 2};
        when(statement.getBytes(1)).thenReturn(bytes);
        JdbcClient.OutParameters result = client.create("{call routine(?)}").call()
                .out("value", 1, JDBCType.BINARY).outputs();
        bytes[0] = 9;
        result.get("value", byte[].class)[0] = 8;
        assertThat(result.get("value", byte[].class)[0], is((byte) 1));
    }

    @Test
    void preservesZeroAndFallsBackOnce() throws Exception {
        when(statement.getLargeUpdateCount()).thenThrow(new SQLFeatureNotSupportedException("secret"));
        when(statement.getUpdateCount()).thenReturn(0, -1);
        client.create("{call routine()}").call().consume(result -> {
            assertThat(result.resultKind(), is(JdbcClient.CallResultKind.UPDATE_COUNT));
            assertThat(result.updateCount(), is(0L));
            result.next();
            assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
            result.next();
        });
        verify(statement).getLargeUpdateCount();
        verify(statement).getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }

    @Test
    void mapsRowsWithoutReexecutionAndExpiresEvent() throws Exception {
        rows(1, 42);
        client.create("{call routine()}").call().consume(result -> {
            JdbcClient.Rows<Integer> stage = result.rows(row -> row.get(1, Integer.class));
            assertThat(stage.one(), is(42));
            assertThrows(IllegalStateException.class, stage::one);
            result.next();
            assertThrows(IllegalStateException.class, stage::list);
        });
        verify(statement).execute();
    }

    /**
     * A caught mapper failure remains primary and blocks later reads.
     */
    @Test
    void retainsCaughtMapperFailure() throws Exception {
        rows(1, 42);
        IllegalArgumentException expected = new IllegalArgumentException("application failure");
        Throwable actual = assertThrows(IllegalArgumentException.class, () -> client.create("{call routine()}")
                .call().consume(result -> {
                    assertThrows(IllegalArgumentException.class, () -> result.rows(_ -> { throw expected; }).list());
                    assertThrows(IllegalStateException.class, result::next);
                }));
        assertThat(actual, sameInstance(expected));
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void retainsCaughtReadFailureInsideMapper() throws Exception {
        rows(1, 42);
        when(statement.getResultSet().getInt(1)).thenThrow(new SQLException("secret"));
        assertThrows(DataException.class, () -> client.create("{call routine()}").call().consume(result -> {
            assertThrows(DataException.class, () -> result.rows(row -> {
                assertThrows(DataException.class, () -> row.get(1, Integer.class));
                return 1;
            }).list());
        }));
    }

    @Test
    void retainsRequiredNullFailure() throws Exception {
        when(statement.wasNull()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> client.create("{call routine(?)}").call()
                .out("value", 1, JDBCType.INTEGER).consume(result -> {
                    assertThrows(IllegalArgumentException.class, () -> result.get("value", Integer.class));
                    assertThrows(NullPointerException.class, () -> result.optional(null, Integer.class));
                    assertThrows(IllegalStateException.class, result::resultKind);
                }));
    }

    @Test
    void pureValidationDoesNotPoisonScope() {
        client.create("{call routine()}").call().consume(result -> {
            assertThrows(IllegalStateException.class, result::updateCount);
            assertThrows(IllegalArgumentException.class, () -> result.get("unknown", String.class));
            assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
        });
    }

    @Test
    void rejectsWrongThreadAndExpiresCallback() throws Exception {
        AtomicReference<JdbcClient.CallResult> escaped = new AtomicReference<>();
        client.create("{call routine()}").call().consume(result -> {
            escaped.set(result);
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() ->
                    assertThrows(IllegalStateException.class, result::resultKind));
            try {
                worker.get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                worker.cancel(true);
            }
        });
        assertThrows(IllegalStateException.class, escaped.get()::resultKind);
    }

    @Test
    void distinguishesNullOptionalCursorAndRequiredCursor() throws Exception {
        client.create("{call routine(?)}").call().out("cursor", 1, JDBCType.REF_CURSOR).consume(result ->
                assertThat(result.optionalCursor("cursor", _ -> 1), is(Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> client.create("{call routine(?)}").call()
                .out("cursor", 1, JDBCType.REF_CURSOR).consume(result -> result.cursor("cursor", _ -> 1).list()));
    }

    @Test
    void cursorSelectionIsLazyAndSequential() throws Exception {
        ResultSet cursor = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(statement.getObject(1)).thenReturn(cursor);
        when(cursor.getMetaData()).thenReturn(metadata);
        client.create("{call routine(?, ?)}").call().out("cursor", 1, JDBCType.REF_CURSOR)
                .out("scalar", 2, JDBCType.INTEGER).consume(result -> {
                    JdbcClient.Rows<Integer> selected = result.cursor("cursor", _ -> 1);
                    try {
                        verify(statement, never()).getObject(1);
                    } catch (SQLException failure) {
                        throw new AssertionError(failure);
                    }
                    assertThrows(IllegalStateException.class, () -> result.get("scalar", Integer.class));
                    assertThat(selected.list(), is(List.of()));
                    assertThat(result.get("scalar", Integer.class), is(0));
                    assertThrows(IllegalStateException.class, () -> result.cursor("cursor", _ -> 1));
                });
        verify(cursor).close();
    }

    @Test
    void rejectsWrongCursorObjectAndNullCallbackReturn() throws Exception {
        when(statement.getObject(1)).thenReturn("not a cursor");
        assertThrows(DataException.class, () -> client.create("{call routine(?)}").call()
                .out("cursor", 1, JDBCType.REF_CURSOR).consume(result -> result.optionalCursor("cursor", _ -> 1)));
        assertThrows(DataException.class, () -> client.create("{call routine()}").call().map(_ -> null));
    }

    @Test
    void preservesApplicationFailureDuringCleanup() throws Exception {
        IllegalArgumentException expected = new IllegalArgumentException("application");
        doThrow(new SQLException("driver secret")).when(statement).close();
        assertThat(assertThrows(IllegalArgumentException.class, () -> client.create("{call routine()}")
                .call().consume(_ -> { throw expected; })), sameInstance(expected));
        verify(connection).close();
        assertThat(expected.getSuppressed().length, is(1));
    }

    /**
     * Each canonical registration retains its exact class and temporal precision after closure.
     */
    @Test
    void capturesEveryCanonicalScalarType() throws Exception {
        LocalTime time = LocalTime.of(12, 13, 14, 123456789);
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalDateTime timestamp = LocalDateTime.of(date, time);
        Map<JDBCType, Object> samples = Map.ofEntries(
                Map.entry(JDBCType.BOOLEAN, true), Map.entry(JDBCType.BIT, true),
                Map.entry(JDBCType.TINYINT, (byte) 1), Map.entry(JDBCType.SMALLINT, (short) 2),
                Map.entry(JDBCType.INTEGER, 3), Map.entry(JDBCType.BIGINT, 4L),
                Map.entry(JDBCType.REAL, 1.5F), Map.entry(JDBCType.FLOAT, 2.5D), Map.entry(JDBCType.DOUBLE, 2.5D),
                Map.entry(JDBCType.NUMERIC, new BigDecimal("1.234")), Map.entry(JDBCType.DECIMAL, new BigDecimal("1.234")),
                Map.entry(JDBCType.CHAR, "value"), Map.entry(JDBCType.VARCHAR, "value"),
                Map.entry(JDBCType.LONGVARCHAR, "value"), Map.entry(JDBCType.NCHAR, "value"),
                Map.entry(JDBCType.NVARCHAR, "value"), Map.entry(JDBCType.LONGNVARCHAR, "value"),
                Map.entry(JDBCType.DATE, date), Map.entry(JDBCType.TIME, time), Map.entry(JDBCType.TIMESTAMP, timestamp));
        when(statement.getBoolean(1)).thenReturn(true);
        when(statement.getByte(1)).thenReturn((byte) 1);
        when(statement.getShort(1)).thenReturn((short) 2);
        when(statement.getInt(1)).thenReturn(3);
        when(statement.getLong(1)).thenReturn(4L);
        when(statement.getFloat(1)).thenReturn(1.5F);
        when(statement.getDouble(1)).thenReturn(2.5D);
        when(statement.getBigDecimal(1)).thenReturn(new BigDecimal("1.234"));
        when(statement.getString(1)).thenReturn("value");
        when(statement.getDate(1)).thenReturn(Date.valueOf(date));
        when(statement.getObject(1, LocalTime.class)).thenReturn(time);
        when(statement.getTimestamp(1)).thenReturn(Timestamp.valueOf(timestamp));
        when(statement.getTimestamp(eq(1), any(Calendar.class))).thenReturn(Timestamp.from(timestamp.toInstant(ZoneOffset.UTC)));
        for (Map.Entry<JDBCType, Object> entry : samples.entrySet()) {
            JdbcClient.OutParameters result = client.create("{call routine(?)}").call()
                    .out("value", 1, entry.getKey()).outputs();
            assertThat(result.get("value", entry.getValue().getClass()), is(entry.getValue()));
            assertThrows(IllegalArgumentException.class, () -> result.get("value", Object.class));
        }
    }

    /**
     * The first processing failure forces rollback even when application code catches the terminal failure.
     */
    @Test
    void reservesTransactionAndRetainsRollbackOnlyAfterCaughtFailure() throws Exception {
        AtomicBoolean automatic = new AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(_ -> automatic.get());
        doAnswer(invocation -> {
            automatic.set((Boolean) invocation.getArguments()[0]);
            return null;
        }).when(connection).setAutoCommit(anyBoolean());
        JdbcTransactionConnectionManager manager = new JdbcTransactionConnectionManager();
        JdbcRunner runner = new JdbcRunner(source, manager);
        manager.start("jdbc");
        manager.begin("call");
        when(statement.wasNull()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> new JdbcStatement(runner, "{call routine(?)}", 1).call()
                .out("value", 1, JDBCType.INTEGER).consume(result -> {
                    assertThrows(NullPointerException.class, () -> manager.acquire(null));
                    assertThrows(IllegalStateException.class, () -> manager.acquire(source));
                    assertThrows(IllegalStateException.class, () -> manager.suspend("call"));
                    assertThrows(IllegalArgumentException.class, () -> result.get("value", Integer.class));
                }));
        try (JdbcConnectionLease released = manager.acquire(source)) {
            assertThat(released.connection(), sameInstance(connection));
        }
        assertThrows(TxException.class, () -> manager.commit("call"));
        manager.end();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    /**
     * A fatal cleanup failure takes precedence while retaining the application failure as evidence.
     */
    @Test
    void fatalCleanupTakesPrecedence() throws Exception {
        Error fatal = new OutOfMemoryError("test");
        doThrow(fatal).when(statement).close();
        IllegalArgumentException application = new IllegalArgumentException("application");
        assertThat(assertThrows(OutOfMemoryError.class, () -> client.create("{call routine()}").call()
                .consume(_ -> { throw application; })), sameInstance(fatal));
        verify(connection).close();
    }

    /**
     * A later callback error cannot replace the first row processing error or prevent cleanup.
     */
    @Test
    void preservesFirstErrorWhenCallbackThrowsAnotherError() throws Exception {
        rows(1, 42);
        ResultSet resultSet = statement.getResultSet();
        Error first = new AssertionError("The first mapper failed.");
        Error later = new AssertionError("The callback failed later.");

        Error observed = assertThrows(Error.class, () -> client.create("{call routine()}").call().consume(result -> {
            assertThat(assertThrows(Error.class, () -> result.rows(_ -> { throw first; }).list()), sameInstance(first));
            throw later;
        }));

        assertThat(observed, sameInstance(first));
        assertThat(observed.getSuppressed().length, is(1));
        assertThat(observed.getSuppressed()[0], sameInstance(later));
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * Repeated retention through nested row processing boundaries suppresses a later error exactly once.
     */
    @Test
    void preservesFirstReadErrorWhenMapperThrowsAnotherError() throws Exception {
        rows(1, 42);
        ResultSet resultSet = statement.getResultSet();
        Error first = new AssertionError("The row read failed.");
        Error later = new AssertionError("The mapper failed later.");
        when(resultSet.getInt(1)).thenThrow(first);

        Error observed = assertThrows(Error.class, () -> client.create("{call routine()}").call().consume(result ->
                result.rows(row -> {
                    assertThat(assertThrows(Error.class, () -> row.get(1, Integer.class)), sameInstance(first));
                    throw later;
                }).one()));

        assertThat(observed, sameInstance(first));
        assertThat(observed.getSuppressed().length, is(1));
        assertThat(observed.getSuppressed()[0], sameInstance(later));
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * A later fatal error still outranks an earlier recoverable mapper failure.
     */
    @Test
    void promotesCallbackErrorAfterRecoverableMapperFailure() throws Exception {
        rows(1, 42);
        IllegalArgumentException first = new IllegalArgumentException("The mapper failed.");
        Error later = new AssertionError("The callback failed later.");

        Error observed = assertThrows(Error.class, () -> client.create("{call routine()}").call().consume(result -> {
            assertThat(assertThrows(IllegalArgumentException.class, () -> result.rows(_ -> { throw first; }).list()),
                       sameInstance(first));
            throw later;
        }));

        assertThat(observed, sameInstance(later));
        assertThat(observed.getSuppressed().length, is(1));
        assertThat(observed.getSuppressed()[0], sameInstance(first));
        verify(statement).close();
        verify(connection).close();
    }

    /**
     * A recoverable mapper failure never replaces an earlier row read error.
     */
    @Test
    void preservesReadErrorWhenMapperThrowsRecoverableFailure() throws Exception {
        rows(1, 42);
        ResultSet resultSet = statement.getResultSet();
        Error first = new AssertionError("The row read failed.");
        IllegalStateException later = new IllegalStateException("The mapper failed later.");
        when(resultSet.getInt(1)).thenThrow(first);

        Error observed = assertThrows(Error.class, () -> client.create("{call routine()}").call().consume(result ->
                result.rows(row -> {
                    assertThat(assertThrows(Error.class, () -> row.get(1, Integer.class)), sameInstance(first));
                    throw later;
                }).one()));

        assertThat(observed, sameInstance(first));
        assertThat(observed.getSuppressed().length, is(1));
        assertThat(observed.getSuppressed()[0], sameInstance(later));
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void rejectsInputInLeadingReturnSlotWithoutAcquisition() throws Exception {
        JdbcClient.Call call = client.create("{? = call routine()}").bind(1, 1).call().out("return", 1, JDBCType.INTEGER);
        assertThrows(IllegalStateException.class, call::execute);
        verify(source, never()).getConnection();
    }

    /**
     * A selected cursor stays usable on its invocation thread after an off-thread access is rejected before JDBC.
     */
    @Test
    void cursorIsConfinedAndSequential() throws Exception {
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(first.getMetaData()).thenReturn(metadata);
        when(second.getMetaData()).thenReturn(metadata);
        when(statement.getObject(1)).thenReturn(first);
        when(statement.getObject(2)).thenReturn(second);
        client.create("{call routine(?, ?)}").call().out("first", 1, JDBCType.REF_CURSOR)
                .out("second", 2, JDBCType.REF_CURSOR).consume(result -> {
                    JdbcClient.Rows<Integer> selected = result.cursor("first", _ -> 1);
                    result.next();
                    result.skipRemainingResults();
                    assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
                    CompletableFuture<Void> worker = CompletableFuture.runAsync(() ->
                            assertThrows(IllegalStateException.class, selected::list));
                    try {
                        worker.get(5, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    } finally {
                        worker.cancel(true);
                    }
                    assertThat(selected.list(), is(List.of()));
                    assertThat(result.cursor("second", _ -> 2).list(), is(List.of()));
                });
        verify(statement).getObject(1);
        verify(statement).getObject(2);
        verify(first).close();
        verify(second).close();
    }

    @Test
    void reportsCardinalityFailureAndPreservesItAfterCleanup() throws Exception {
        rows(1, 42);
        ResultSet resultSet = statement.getResultSet();
        when(resultSet.next()).thenReturn(true, true, false);
        doThrow(new SQLException("cleanup secret")).when(resultSet).close();
        DataException failure = assertThrows(NonUniqueResultException.class,
                () -> client.create("{call routine()}").call().consume(result -> {
                    assertThrows(NonUniqueResultException.class,
                                 () -> result.rows(row -> row.get(1, Integer.class)).one());
                    throw new IllegalArgumentException("later application failure");
                }));
        assertThat(failure.getSuppressed().length, is(2));
        verify(connection).close();
    }

    @Test
    void registersNamedAndScaledOutputsBeforeBindingAndExecution() throws Exception {
        client.create("{call routine(?, ?, ?)}").bind(3, "input").call()
                .out("named", 1, JDBCType.STRUCT, "APP.TYPE")
                .out("scaled", 2, JDBCType.DECIMAL, 2)
                .out("inout", 3, JDBCType.VARCHAR).execute();
        InOrder order = inOrder(statement);
        order.verify(statement).registerOutParameter(1, JDBCType.STRUCT.getVendorTypeNumber(), "APP.TYPE");
        order.verify(statement).registerOutParameter(2, JDBCType.DECIMAL.getVendorTypeNumber(), 2);
        order.verify(statement).registerOutParameter(3, JDBCType.VARCHAR.getVendorTypeNumber());
        order.verify(statement).setString(3, "input");
        order.verify(statement).execute();
        order.verify(statement).close();
    }

    /**
     * Callable setup and read failures retain sanitized diagnostics and attempt every acquired resource.
     */
    @Test
    void sanitizesFailuresAcrossEveryJdbcBoundary() throws Exception {
        for (String phase : List.of("acquire", "prepare", "register", "bind", "execute", "read", "close")) {
            setup();
            SQLException driver = new SQLException("confidential-driver-canary", "HY000", 1234);
            switch (phase) {
            case "acquire" -> when(source.getConnection()).thenThrow(driver);
            case "prepare" -> when(connection.prepareCall(anyString())).thenThrow(driver);
            case "register" -> doThrow(driver).when(statement).registerOutParameter(1, JDBCType.INTEGER.getVendorTypeNumber());
            case "bind" -> doThrow(driver).when(statement).setInt(1, 7);
            case "execute" -> when(statement.execute()).thenThrow(driver);
            case "read" -> when(statement.getInt(1)).thenThrow(driver);
            case "close" -> doThrow(driver).when(statement).close();
            default -> throw new AssertionError(phase);
            }
            DataException failure = assertThrows(DataException.class, () -> client.create("{call confidential_sql(?)}")
                    .bind(1, 7).call().out("value", 1, JDBCType.INTEGER).outputs());
            StringWriter text = new StringWriter();
            failure.printStackTrace(new PrintWriter(text));
            assertThat(text.toString().contains("confidential-driver-canary"), is(false));
            assertThat(text.toString().contains("confidential_sql"), is(false));
            if (!phase.equals("acquire")) {
                verify(connection).close();
            }
            if (!phase.equals("acquire") && !phase.equals("prepare")) {
                verify(statement).close();
            }
        }
    }

    @Test
    void exposesLargeCountsInEncounterOrderAndEmptySnapshots() throws Exception {
        when(statement.getLargeUpdateCount()).thenReturn(3_000_000_000L, 0L, -1L);
        client.create("{call routine()}").call().consume(result -> {
            assertThat(result.updateCount(), is(3_000_000_000L));
            result.next();
            assertThat(result.updateCount(), is(0L));
            result.next();
            assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
        });
        JdbcClient.OutParameters empty = client.create("{call routine()}").call().outputs();
        assertThrows(IllegalArgumentException.class, () -> empty.optional("missing", Integer.class));
    }

    private void rows(int size, int value) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(resultSet.next()).thenReturn(size > 0, false);
        when(resultSet.getInt(1)).thenReturn(value);
    }
}
