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
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Small recording JDBC fixture for lifecycle tests; it implements only calls made by the tested execution path.
 */
final class RecordingJdbc {
    private final List<String> events = new ArrayList<>();
    private final List<CallResult> callResults = new ArrayList<>();
    private final Map<Integer, Object> callOutputs = new HashMap<>();
    private List<String> resultRows = List.of("row");
    private SQLException resultNextFailure;
    private SQLException resultCloseFailure;
    private SQLException statementCloseFailure;
    private SQLException connectionCloseFailure;
    private SQLException fetchSizeFailure;
    private SQLWarning resultWarning;
    private SQLWarning statementWarning;
    private SQLWarning connectionWarning;
    private Long updateCount;
    private boolean largeMaxRowsUnsupported;

    RecordingJdbc rows(String... rows) {
        resultRows = List.of(rows);
        return this;
    }

    RecordingJdbc failResultNext(SQLException failure) {
        resultNextFailure = failure;
        return this;
    }

    RecordingJdbc failResultClose(SQLException failure) {
        resultCloseFailure = failure;
        return this;
    }

    RecordingJdbc failStatementClose(SQLException failure) {
        statementCloseFailure = failure;
        return this;
    }

    RecordingJdbc failConnectionClose(SQLException failure) {
        connectionCloseFailure = failure;
        return this;
    }

    RecordingJdbc failFetchSize(SQLException failure) {
        fetchSizeFailure = failure;
        return this;
    }

    RecordingJdbc updateCount(long count) {
        updateCount = count;
        return this;
    }

    RecordingJdbc callRows(String... rows) {
        callResults.add(new CallRows(List.of(rows)));
        return this;
    }

    RecordingJdbc callUpdateCount(long count) {
        callResults.add(new CallUpdateCount(count));
        return this;
    }

    RecordingJdbc callOutput(int index, Object value) {
        callOutputs.put(index, value);
        return this;
    }

    RecordingJdbc callCursor(int index, String... rows) {
        callOutputs.put(index, new CallRows(List.of(rows)));
        return this;
    }

    RecordingJdbc largeMaxRowsUnsupported() {
        largeMaxRowsUnsupported = true;
        return this;
    }

    RecordingJdbc warnings(SQLWarning result, SQLWarning statement, SQLWarning connection) {
        resultWarning = result;
        statementWarning = statement;
        connectionWarning = connection;
        return this;
    }

    List<String> events() {
        return List.copyOf(events);
    }

    DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                events.add("connection.acquire");
                return connection();
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return iface.cast(this);
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return iface.isInstance(this);
            }
        };
    }

    private Connection connection() {
        return proxy(Connection.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "clearWarnings" -> record("connection.clearWarnings", null);
            case "getWarnings" -> connectionWarning;
            case "getAutoCommit" -> true;
            case "setAutoCommit" -> record("connection.autoCommit:" + arguments[0], null);
            case "isReadOnly" -> false;
            case "setReadOnly" -> record("connection.readOnly:" + arguments[0], null);
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "setTransactionIsolation" -> record("connection.isolation:" + arguments[0], null);
            case "commit" -> record("connection.commit", null);
            case "rollback" -> record("connection.rollback", null);
            case "prepareStatement" -> record("statement.prepare", statement());
            case "prepareCall" -> record("call.prepare", callableStatement());
            case "close" -> recordOrThrow("connection.close", connectionCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingConnection";
            default -> defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement statement() {
        boolean[] advanced = {false};
        return proxy(PreparedStatement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "clearWarnings" -> record("statement.clearWarnings", null);
            case "getWarnings" -> statementWarning;
            case "setFetchSize" -> recordOrThrow("statement.fetchSize:" + arguments[0], fetchSizeFailure);
            case "setQueryTimeout" -> record("statement.queryTimeout:" + arguments[0], null);
            case "setLargeMaxRows" -> largeMaxRows((Long) arguments[0]);
            case "setMaxRows" -> record("statement.legacyMaxRows:" + arguments[0], null);
            case "setPoolable" -> record("statement.poolable:" + arguments[0], null);
            case "setObject" -> record("statement.bind:" + arguments[0] + ":" + arguments[1], null);
            case "setNull" -> record("statement.bindNull:" + arguments[0] + ":" + arguments[1], null);
            case "execute" -> {
                advanced[0] = false;
                yield record("statement.execute", updateCount == null);
            }
            case "getResultSet" -> record("statement.resultSet", resultSet());
            case "getMoreResults" -> {
                advanced[0] = true;
                yield record("statement.moreResults:" + arguments[0], false);
            }
            case "getLargeUpdateCount" -> advanced[0] || updateCount == null ? -1L : updateCount;
            case "getUpdateCount" -> advanced[0] || updateCount == null ? -1 : Math.toIntExact(updateCount);
            case "close" -> recordOrThrow("statement.close", statementCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingPreparedStatement";
            default -> defaultValue(method.getReturnType());
        });
    }

    private CallableStatement callableStatement() {
        int[] current = {-1};
        ResultSet[] currentResultSet = {null};
        return proxy(CallableStatement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "clearWarnings" -> record("call.clearWarnings", null);
            case "getWarnings" -> statementWarning;
            case "setFetchSize" -> recordOrThrow("call.fetchSize:" + arguments[0], fetchSizeFailure);
            case "setQueryTimeout" -> record("call.queryTimeout:" + arguments[0], null);
            case "setLargeMaxRows" -> callLargeMaxRows((Long) arguments[0]);
            case "setMaxRows" -> record("call.legacyMaxRows:" + arguments[0], null);
            case "setPoolable" -> record("call.poolable:" + arguments[0], null);
            case "setObject" -> record("call.bind:" + arguments[0] + ":" + arguments[1], null);
            case "setNull" -> record("call.bindNull:" + arguments[0] + ":" + arguments[1], null);
            case "registerOutParameter" -> record("call.register:" + arguments[0] + ":" + arguments[1]
                                                           + (arguments.length == 3 ? ":" + arguments[2] : ""),
                                                   null);
            case "execute" -> {
                current[0] = callResults.isEmpty() ? -1 : 0;
                currentResultSet[0] = null;
                yield record("call.execute", isCallRows(current[0]));
            }
            case "getResultSet" -> {
                if (!isCallRows(current[0])) {
                    yield null;
                }
                if (currentResultSet[0] == null) {
                    CallRows rows = (CallRows) callResults.get(current[0]);
                    currentResultSet[0] = resultSet("call.result." + current[0], rows.rows());
                }
                yield record("call.resultSet:" + current[0], currentResultSet[0]);
            }
            case "getMoreResults" -> {
                currentResultSet[0] = null;
                if (current[0] >= 0) {
                    current[0]++;
                    if (current[0] >= callResults.size()) {
                        current[0] = -1;
                    }
                }
                yield record("call.moreResults:" + arguments[0], isCallRows(current[0]));
            }
            case "getLargeUpdateCount" -> callUpdateCount(current[0]);
            case "getUpdateCount" -> Math.toIntExact(callUpdateCount(current[0]));
            case "getObject" -> readCallOutput((Integer) arguments[0], arguments.length == 2);
            case "close" -> recordOrThrow("call.close", statementCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingCallableStatement";
            default -> defaultValue(method.getReturnType());
        });
    }

    private boolean isCallRows(int index) {
        return index >= 0 && callResults.get(index) instanceof CallRows;
    }

    private long callUpdateCount(int index) {
        return index >= 0 && callResults.get(index) instanceof CallUpdateCount count ? count.count() : -1L;
    }

    private Object readCallOutput(int index, boolean typed) {
        events.add("call.output:" + index + (typed ? ":typed" : ""));
        Object value = callOutputs.get(index);
        if (value instanceof CallRows rows) {
            return resultSet("call.cursor." + index, rows.rows());
        }
        return value;
    }

    private Object callLargeMaxRows(long rows) throws SQLException {
        events.add("call.maxRows:" + rows);
        if (largeMaxRowsUnsupported) {
            throw new SQLFeatureNotSupportedException("Large maximum rows are unsupported", "0A000");
        }
        return null;
    }

    private Object largeMaxRows(long rows) throws SQLException {
        events.add("statement.maxRows:" + rows);
        if (largeMaxRowsUnsupported) {
            throw new SQLFeatureNotSupportedException("Large maximum rows are unsupported", "0A000");
        }
        return null;
    }

    private ResultSet resultSet() {
        return resultSet("result", resultRows);
    }

    private ResultSet resultSet(String eventPrefix, List<String> rows) {
        int[] row = {-1};
        return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getMetaData" -> metadata();
            case "next" -> {
                events.add(eventPrefix + ".next");
                if (resultNextFailure != null) {
                    throw resultNextFailure;
                }
                yield ++row[0] < rows.size();
            }
            case "getObject" -> rows.get(row[0]);
            case "getWarnings" -> resultWarning;
            case "clearWarnings" -> record(eventPrefix + ".clearWarnings", null);
            case "close" -> recordOrThrow(eventPrefix + ".close", resultCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingResultSet";
            default -> defaultValue(method.getReturnType());
        });
    }

    private ResultSetMetaData metadata() {
        return proxy(ResultSetMetaData.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getColumnCount" -> 1;
            case "getColumnLabel", "getColumnName" -> "value";
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingResultSetMetaData";
            default -> defaultValue(method.getReturnType());
        });
    }

    private Object record(String event, Object result) {
        events.add(event);
        return result;
    }

    private Object recordOrThrow(String event, SQLException failure) throws SQLException {
        events.add(event);
        if (failure != null) {
            throw failure;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> contract, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private sealed interface CallResult permits CallRows, CallUpdateCount {
    }

    private record CallRows(List<String> rows) implements CallResult {
    }

    private record CallUpdateCount(long count) implements CallResult {
    }
}
