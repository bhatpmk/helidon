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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

class JdbcClientBatchTest {

    @Test
    void executesBatchWithNamedParameters() {
        JdbcClient client = client();
        client.execute("""
                       CREATE TABLE pokemon (
                           id BIGINT PRIMARY KEY,
                           name VARCHAR(64)
                       )
                       """)
                .updateCount();

        long[] counts = client.execute("INSERT INTO pokemon(id, name) VALUES (:id, :name)")
                .param("id", 1L)
                .param("name", "Pikachu")
                .addBatch()
                .param("id", 2L)
                .param("name", "Raichu")
                .addBatch()
                .batchUpdateCounts();

        assertThat(List.of(counts[0], counts[1]), contains(1L, 1L));
        assertThat(client.execute("SELECT name FROM pokemon ORDER BY id")
                           .list(row -> row.value("name", String.class)),
                   contains("Pikachu", "Raichu"));
    }

    @Test
    void executesBatchWithExplicitBatchParameterLists() {
        JdbcClient client = client();
        client.execute("""
                       CREATE TABLE pokemon (
                           id BIGINT PRIMARY KEY,
                           name VARCHAR(64)
                       )
                       """)
                .updateCount();

        long[] counts = client.execute("INSERT INTO pokemon(id, name) VALUES (:id, :name)")
                .addBatch(JdbcParameter.create("id", 1L), JdbcParameter.create("name", "Pikachu"))
                .addBatch(JdbcParameter.create("id", 2L), JdbcParameter.create("name", "Raichu"))
                .batchUpdateCounts();

        assertThat(List.of(counts[0], counts[1]), contains(1L, 1L));
    }

    private static JdbcClient client() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcClientImpl("test", dataSource, Optional.empty());
    }
}
