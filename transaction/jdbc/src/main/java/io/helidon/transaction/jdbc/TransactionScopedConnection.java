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

import java.sql.Connection;
import java.sql.SQLException;

class TransactionScopedConnection extends DelegatingConnection {

    // The standard SQL state used for unspecified connection exceptions. Used in this class primarily to indicate
    // premature connection closure.
    private static final String CONNECTION_EXCEPTION_NO_SUBCLASS = "08000";

    private boolean closed;

    TransactionScopedConnection(Connection delegate) {
        super(delegate);
    }

    @Override // DelegatingConnection
    public void close() throws SQLException {
        this.closed = true;
    }

    @Override // DelegatingConnection
    Connection delegate() throws SQLException {
        if (this.closed) {
            throw new SQLException("closed", CONNECTION_EXCEPTION_NO_SUBCLASS, null);
        }
        Connection delegate = super.delegate();
        if (delegate.isClosed()) {
            throw new SQLException("closed", CONNECTION_EXCEPTION_NO_SUBCLASS, null);
        }
        return delegate;
    }

    @Override // DelegatingConnection
    public boolean isClosed() throws SQLException {
        return this.closed
            || super.delegate().isClosed(); // note super; important
    }

    @Override // DelegatingConnection
    public void commit() throws SQLException {
        throw new SQLException("Explicit commit is prohibited", CONNECTION_EXCEPTION_NO_SUBCLASS, null);
    }

    @Override // DelegatingConnection
    public void rollback() throws SQLException {
        throw new SQLException("Explicit rollback is prohibited", CONNECTION_EXCEPTION_NO_SUBCLASS, null);
    }

    @Override // DelegatingConnection
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        throw new SQLException("Changing autoCommit state is prohibited", CONNECTION_EXCEPTION_NO_SUBCLASS, null);
    }

}
