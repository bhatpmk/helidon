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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;

final class TransactionalDataSource implements DataSource {

    private final ConnectionResolver connectionResolver;

    private final DataSource delegate;

    TransactionalDataSource(ConnectionResolver connectionResolver, DataSource delegate) {
        super();
        this.connectionResolver = requireNonNull(connectionResolver, "connectionResolver");
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override // DataSource
    public Connection getConnection() throws SQLException {
        return this.connectionResolver.connection(this.delegate);
    }

    @Override // DataSource
    public Connection getConnection(String username, String password) throws SQLException {
        return this.connectionResolver.connection(this.delegate, username, password);
    }

    @Override // DataSource
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return this.delegate.unwrap(iface);
    }

    @Override // DataSource
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.delegate.isWrapperFor(iface);
    }

    @Override // DataSource
    public PrintWriter getLogWriter() throws SQLException {
        return this.delegate.getLogWriter();
    }

    @Override // DataSource
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.delegate.setLogWriter(out);
    }

    @Override // DataSource
    public void setLoginTimeout(int seconds) throws SQLException {
        this.delegate.setLoginTimeout(seconds);
    }

    @Override // DataSource
    public int getLoginTimeout() throws SQLException {
        return this.delegate.getLoginTimeout();
    }

    @Override // DataSource
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return this.delegate.getParentLogger();
    }

}
