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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Small recording JDBC fixture for lifecycle tests; it implements only calls made by the tested execution path.
 */
final class RecordingJdbc {
    private final List<String> events = new ArrayList<>();
    private List<String> resultRows = List.of("row");
    private SQLException resultNextFailure;
    private SQLException resultCloseFailure;
    private SQLException statementCloseFailure;
    private SQLException connectionCloseFailure;
    private SQLWarning resultWarning;
    private SQLWarning statementWarning;
    private SQLWarning connectionWarning;

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
            case "close" -> recordOrThrow("connection.close", connectionCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingConnection";
            default -> defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement statement() {
        return proxy(PreparedStatement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "clearWarnings" -> record("statement.clearWarnings", null);
            case "getWarnings" -> statementWarning;
            case "setFetchSize" -> record("statement.fetchSize:" + arguments[0], null);
            case "setQueryTimeout" -> record("statement.queryTimeout:" + arguments[0], null);
            case "setLargeMaxRows" -> record("statement.maxRows:" + arguments[0], null);
            case "setPoolable" -> record("statement.poolable:" + arguments[0], null);
            case "setObject" -> record("statement.bind:" + arguments[0] + ":" + arguments[1], null);
            case "setNull" -> record("statement.bindNull:" + arguments[0] + ":" + arguments[1], null);
            case "execute" -> record("statement.execute", true);
            case "getResultSet" -> record("statement.resultSet", resultSet());
            case "getMoreResults" -> record("statement.moreResults:" + arguments[0], false);
            case "getLargeUpdateCount" -> -1L;
            case "getUpdateCount" -> -1;
            case "close" -> recordOrThrow("statement.close", statementCloseFailure);
            case "isClosed" -> false;
            case "unwrap" -> ((Class<?>) arguments[0]).cast(proxy);
            case "isWrapperFor" -> ((Class<?>) arguments[0]).isInstance(proxy);
            case "toString" -> "RecordingPreparedStatement";
            default -> defaultValue(method.getReturnType());
        });
    }

    private ResultSet resultSet() {
        int[] row = {-1};
        return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getMetaData" -> metadata();
            case "next" -> {
                events.add("result.next");
                if (resultNextFailure != null) {
                    throw resultNextFailure;
                }
                yield ++row[0] < resultRows.size();
            }
            case "getObject" -> resultRows.get(row[0]);
            case "getWarnings" -> resultWarning;
            case "clearWarnings" -> record("result.clearWarnings", null);
            case "close" -> recordOrThrow("result.close", resultCloseFailure);
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
}
