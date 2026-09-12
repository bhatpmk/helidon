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
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;

import io.helidon.data.DataException;
import io.helidon.data.sql.datasource.spi.XaDataSourceCapability;

/**
 * Provider-owned live XA resources for one datasource in one global
 * transaction.
 */
final class JdbcGlobalDataSourceAssociation {

    private final XAConnection xaConnection;
    private final XAResource xaResource;
    private final Connection connection;
    private State state = State.DELISTED;

    private JdbcGlobalDataSourceAssociation(XAConnection xaConnection,
                                            XAResource xaResource,
                                            Connection connection) {
        this.xaConnection = xaConnection;
        this.xaResource = xaResource;
        this.connection = connection;
    }

    /**
     * Acquires all unpublished XA resources, closing partial state on failure.
     *
     * @param capability XA datasource capability
     * @return initialized association
     */
    static JdbcGlobalDataSourceAssociation create(XaDataSourceCapability capability) {
        XAConnection xaConnection = null;
        Connection connection = null;
        try {
            xaConnection = Objects.requireNonNull(capability.dataSource().getXAConnection(),
                                                  "The XA data source returned a null XA connection.");
            XAResource xaResource = Objects.requireNonNull(xaConnection.getXAResource(),
                                                           "The XA connection returned a null XA resource.");
            connection = Objects.requireNonNull(xaConnection.getConnection(),
                                                "The XA connection returned a null logical connection.");
            return new JdbcGlobalDataSourceAssociation(xaConnection, xaResource, connection);
        } catch (SQLException | RuntimeException | Error failure) {
            Throwable cleanupFailure = close(connection, xaConnection);
            if (cleanupFailure != null && cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new DataException("Failed to acquire an XA JDBC connection.",
                                    JdbcExceptionTranslator.sanitize("acquiring XA resources", failure));
        }
    }

    Connection connection() {
        if (state == State.CLOSED || state == State.FAILED) {
            throw new IllegalStateException("The XA JDBC association is not usable.");
        }
        return connection;
    }

    XAResource xaResource() {
        return xaResource;
    }

    State state() {
        return state;
    }

    void enlisted() {
        if (state != State.DELISTED && state != State.SUSPENDED) {
            throw new IllegalStateException("The XA JDBC association cannot be enlisted from state '" + state + "'.");
        }
        state = State.ENLISTED;
    }

    void delisted() {
        if (state != State.ENLISTED) {
            throw new IllegalStateException("The XA JDBC association cannot be delisted from state '" + state + "'.");
        }
        state = State.DELISTED;
    }

    void suspended() {
        if (state != State.ENLISTED) {
            throw new IllegalStateException("The XA JDBC association cannot be suspended from state '" + state + "'.");
        }
        state = State.SUSPENDED;
    }

    void failed() {
        state = State.FAILED;
    }

    Throwable close() {
        if (state == State.CLOSED) {
            return null;
        }
        state = State.CLOSED;
        return close(connection, xaConnection);
    }

    private static Throwable close(Connection connection, XAConnection xaConnection) {
        Throwable failure = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                failure = closeFailure;
            }
        }
        if (xaConnection != null) {
            try {
                xaConnection.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    /**
     * Live association state.
     */
    enum State {
        DELISTED,
        ENLISTED,
        SUSPENDED,
        FAILED,
        CLOSED
    }
}
