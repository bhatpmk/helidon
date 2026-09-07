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

class DelegatingConnection implements Connection {

    private final Connection delegate;

    DelegatingConnection(Connection delegate) {
        super();
        this.delegate = requireNonNull(delegate, "delegate");
    }

    Connection delegate() throws SQLException {
        return this.delegate;
    }

    @Override // Wrapper
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return this.delegate().unwrap(iface);
    }

    @Override // Wrapper
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.delegate().isWrapperFor(iface);
    }

    @Override // Connection
    public Statement createStatement() throws SQLException {
        return this.delegate().createStatement();
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return this.delegate().prepareStatement(sql);
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql) throws SQLException {
        return this.delegate().prepareCall(sql);
    }

    @Override // Connection
    public String nativeSQL(String sql) throws SQLException {
        return this.delegate().nativeSQL(sql);
    }

    @Override // Connection
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.delegate().setAutoCommit(autoCommit);
    }

    @Override // Connection
    public boolean getAutoCommit() throws SQLException {
        return this.delegate().getAutoCommit();
    }

    @Override // Connection
    public void commit() throws SQLException {
        this.delegate().commit();
    }

    @Override // Connection
    public void rollback() throws SQLException {
        this.delegate().rollback();
    }

    @Override // Connection
    public void close() throws SQLException {
        this.delegate().close();
    }

    @Override // Connection
    public boolean isClosed() throws SQLException {
        return this.delegate().isClosed();
    }

    @Override // Connection
    public DatabaseMetaData getMetaData() throws SQLException {
        return this.delegate().getMetaData();
    }

    @Override // Connection
    public void setReadOnly(boolean readOnly) throws SQLException {
        this.delegate().setReadOnly(readOnly);
    }

    @Override // Connection
    public boolean isReadOnly() throws SQLException {
        return this.delegate().isReadOnly();
    }

    @Override // Connection
    public void setCatalog(String catalog) throws SQLException {
        this.delegate().setCatalog(catalog);
    }

    @Override // Connection
    public String getCatalog() throws SQLException {
        return this.delegate().getCatalog();
    }

    @Override // Connection
    public void setTransactionIsolation(int level) throws SQLException {
        this.delegate().setTransactionIsolation(level);
    }

    @Override // Connection
    public int getTransactionIsolation() throws SQLException {
        return this.delegate().getTransactionIsolation();
    }

    @Override // Connection
    public SQLWarning getWarnings() throws SQLException {
        return this.delegate().getWarnings();
    }

    @Override // Connection
    public void clearWarnings() throws SQLException {
        this.delegate().clearWarnings();
    }

    @Override // Connection
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return this.delegate().createStatement(resultSetType, resultSetConcurrency);
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        return this.delegate().prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        return this.delegate().prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override // Connection
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return this.delegate().getTypeMap();
    }

    @Override // Connection
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        this.delegate().setTypeMap(map);
    }

    @Override // Connection
    public void setHoldability(int holdability) throws SQLException {
        this.delegate().setHoldability(holdability);
    }

    @Override // Connection
    public int getHoldability() throws SQLException {
        return this.delegate().getHoldability();
    }

    @Override // Connection
    public Savepoint setSavepoint() throws SQLException {
        return this.delegate().setSavepoint();
    }

    @Override // Connection
    public Savepoint setSavepoint(String name) throws SQLException {
        return this.delegate().setSavepoint(name);
    }

    @Override // Connection
    public void rollback(Savepoint savepoint) throws SQLException {
        this.delegate().rollback(savepoint);
    }

    @Override // Connection
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        this.delegate().releaseSavepoint(savepoint);
    }

    @Override // Connection
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        return this.delegate().createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql,
                                              int resultSetType,
                                              int resultSetConcurrency,
                                              int resultSetHoldability)
        throws SQLException {
        return this.delegate().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override // Connection
    public CallableStatement prepareCall(String sql,
                                         int resultSetType,
                                         int resultSetConcurrency,
                                         int resultSetHoldability)
        throws SQLException {
        return this.delegate().prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return this.delegate().prepareStatement(sql, autoGeneratedKeys);
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return this.delegate().prepareStatement(sql, columnIndexes);
    }

    @Override // Connection
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return this.delegate().prepareStatement(sql, columnNames);
    }

    @Override // Connection
    public Clob createClob() throws SQLException {
        return this.delegate().createClob();
    }

    @Override // Connection
    public Blob createBlob() throws SQLException {
        return this.delegate().createBlob();
    }

    @Override // Connection
    public NClob createNClob() throws SQLException {
        return this.delegate().createNClob();
    }

    @Override // Connection
    public SQLXML createSQLXML() throws SQLException {
        return this.delegate().createSQLXML();
    }

    @Override // Connection
    public boolean isValid(int timeout) throws SQLException {
        return this.delegate().isValid(timeout);
    }

    @Override // Connection
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        try {
            this.delegate().setClientInfo(name, value);
        } catch (SQLClientInfoException e) {
            throw e;
        } catch (SQLException e) {
            throw new SQLClientInfoException(Map.of(name, ClientInfoStatus.REASON_UNKNOWN), e);
        }
    }

    @Override // Connection
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        try {
            this.delegate().setClientInfo(properties);
        } catch (SQLClientInfoException e) {
            throw e;
        } catch (SQLException e) {
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
            throw new SQLClientInfoException(m, e);
        }
    }

    @Override // Connection
    public String getClientInfo(String name) throws SQLException {
        return this.delegate().getClientInfo(name);
    }

    @Override // Connection
    public Properties getClientInfo() throws SQLException {
        return this.delegate().getClientInfo();
    }

    @Override // Connection
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return this.delegate().createArrayOf(typeName, elements);
    }

    @Override // Connection
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return this.delegate().createStruct(typeName, attributes);
    }

    @Override // Connection
    public void setSchema(String schema) throws SQLException {
        this.delegate().setSchema(schema);
    }

    @Override // Connection
    public String getSchema() throws SQLException {
        return this.delegate().getSchema();
    }

    @Override // Connection
    public void abort(Executor executor) throws SQLException {
        this.delegate().abort(executor);
    }

    @Override // Connection
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        this.delegate().setNetworkTimeout(executor, milliseconds);
    }

    @Override // Connection
    public int getNetworkTimeout() throws SQLException {
        return this.delegate().getNetworkTimeout();
    }

    @Override // Connection
    public void beginRequest() throws SQLException {
        this.delegate().beginRequest();
    }

    @Override // Connection
    public void endRequest() throws SQLException {
        this.delegate().endRequest();
    }

    @Override // Connection
    public boolean setShardingKeyIfValid(ShardingKey shardingKey,
                                         ShardingKey superShardingKey,
                                         int timeout)
        throws SQLException {
        return this.delegate().setShardingKeyIfValid(shardingKey, superShardingKey, timeout);
    }

    @Override // Connection
    public boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException {
        return this.delegate().setShardingKeyIfValid(shardingKey, timeout);
    }

    @Override // Connection
    public void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException {
        this.delegate().setShardingKey(shardingKey, superShardingKey);
    }

    @Override // Connection
    public void setShardingKey(ShardingKey shardingKey) throws SQLException {
        this.delegate().setShardingKey(shardingKey);
    }

    @Override // Connection
    public String enquoteLiteral(String val) throws SQLException {
        return this.delegate().enquoteLiteral(val);
    }

    @Override // Connection
    public String enquoteIdentifier(String identifier, boolean alwaysQuote) throws SQLException {
        return this.delegate().enquoteIdentifier(identifier, alwaysQuote);
    }

    @Override // Connection
    public boolean isSimpleIdentifier(String identifier) throws SQLException {
        return this.delegate().isSimpleIdentifier(identifier);
    }

    @Override // Connection
    public String enquoteNCharLiteral(String val) throws SQLException {
        return this.delegate().enquoteNCharLiteral(val);
    }

}
