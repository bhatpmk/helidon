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
package io.helidon.transaction.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKey;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.util.Collections.unmodifiableMap;
import static java.util.LinkedHashMap.newLinkedHashMap;
import static java.util.Objects.requireNonNull;

class ConnectionStub implements Connection {

    ConnectionStub() {
        super();
    }

    @Override // Wrapper
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Wrapper
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Statement createStatement() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String nativeSQL(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean getAutoCommit() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void commit() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void rollback() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void close() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean isClosed() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public DatabaseMetaData getMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setReadOnly(boolean readOnly) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean isReadOnly() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setCatalog(String catalog) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String getCatalog() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setTransactionIsolation(int level) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public int getTransactionIsolation() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override // Connection
    public void clearWarnings() throws SQLException {
    }

    @Override // Connection
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setHoldability(int holdability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public int getHoldability() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql,
                                              int resultSetType,
                                              int resultSetConcurrency,
                                              int resultSetHoldability)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql,
                                         int resultSetType,
                                         int resultSetConcurrency,
                                         int resultSetHoldability)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean isValid(int timeout) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new SQLClientInfoException(Map.of(name, ClientInfoStatus.REASON_UNKNOWN));
    }

    @Override // Connection
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        Map<String, ClientInfoStatus> m;
        Set<String> stringPropertyNames = properties.stringPropertyNames();
        if (stringPropertyNames.isEmpty()) {
            m = Map.of();
        } else {
            m = newLinkedHashMap(stringPropertyNames.size());
            for (String n : stringPropertyNames) {
                m.put(n, ClientInfoStatus.REASON_UNKNOWN);
            }
            m = unmodifiableMap(m);
        }
        throw new SQLClientInfoException(m);
    }

    @Override // Connection
    public String getClientInfo(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Properties getClientInfo() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setSchema(String schema) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String getSchema() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void abort(Executor executor) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public int getNetworkTimeout() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void beginRequest() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void endRequest() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean setShardingKeyIfValid(ShardingKey shardingKey,
                                         ShardingKey superShardingKey,
                                         int timeout)
        throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public void setShardingKey(ShardingKey shardingKey) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String enquoteLiteral(String val) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String enquoteIdentifier(String identifier, boolean alwaysQuote) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public boolean isSimpleIdentifier(String identifier) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override // Connection
    public String enquoteNCharLiteral(String val) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

}
