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
import java.util.concurrent.atomic.AtomicInteger;

final class RecordingDataSourceStub extends DataSourceStub {

    private final RecordingConnectionStub[] connections;

    private final AtomicInteger connectionRequestCount;

    RecordingDataSourceStub(RecordingConnectionStub... connections) {
        super();
        this.connections = connections.clone();
        this.connectionRequestCount = new AtomicInteger();
    }

    @Override
    public Connection getConnection() throws SQLException {
        int index = this.connectionRequestCount.getAndIncrement();
        if (index >= this.connections.length) {
            throw new SQLException("No connection configured for request index " + index);
        }
        return this.connections[index];
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return this.getConnection();
    }

    int connectionRequestCount() {
        return this.connectionRequestCount.get();
    }

}
