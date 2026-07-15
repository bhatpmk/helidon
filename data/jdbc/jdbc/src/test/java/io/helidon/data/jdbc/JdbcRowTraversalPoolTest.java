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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcRowTraversalPoolTest {

    @Test
    void everyTraversalOfferReturnsItsConnectionToASingleConnectionPool() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jdbc_row_traversal_pool;DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(500);
        config.setPoolName("jdbc-row-traversal-test");

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            initialize(dataSource);
            JdbcClient client = new JdbcClientImpl(dataSource);

            // A one-connection pool turns any escaped traversal lease into a deterministic timeout on the next call.
            for (int i = 0; i < 20; i++) {
                List<String> visited = new ArrayList<>();
                client.create("SELECT NAME FROM USERS ORDER BY ID")
                        .map(String.class)
                        .visitAll(JdbcQueryRequest.visitAll(visited::add));
                assertEquals(List.of("Ada", "Grace", "Linus"), visited);

                assertFalse(client.create("SELECT NAME FROM USERS ORDER BY ID")
                                    .map(String.class)
                                    .visitWhile(JdbcQueryRequest.visitWhile(ignored -> false)));

                assertEquals(List.of("Ada", "Grace", "Linus"),
                             client.create("SELECT NAME FROM USERS ORDER BY ID")
                                     .map(String.class)
                                     .list(JdbcQueryRequest.defaults()));
            }

            assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
        }
    }

    private static void initialize(HikariDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS USERS");
            statement.execute("CREATE TABLE USERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(80))");
            statement.execute("INSERT INTO USERS VALUES (1, 'Ada'), (2, 'Grace'), (3, 'Linus')");
        }
    }
}
