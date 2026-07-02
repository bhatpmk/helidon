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

import io.helidon.data.DataException;
import io.helidon.data.Page;
import io.helidon.data.PageRequest;
import io.helidon.data.Slice;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcClientPaginationTest {

    private static final String OFFSET_SQL = """
            SELECT id
            FROM pokemon
            WHERE type = :type
            ORDER BY id
            LIMIT :__helidon_page_size OFFSET :__helidon_page_offset
            """;
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM pokemon WHERE type = :type";
    private static final String KEYSET_SQL = """
            SELECT id
            FROM pokemon
            WHERE type = :type AND id > :afterId
            ORDER BY id
            LIMIT :__helidon_page_size
            """;

    @Test
    void returnsFirstMiddleFinalAndOutOfRangePages() {
        JdbcClient client = populatedClient();

        Page<Integer> first = page(client, PageRequest.create(0, 2));
        Page<Integer> middle = page(client, PageRequest.create(1, 2));
        Page<Integer> last = page(client, PageRequest.create(2, 2));
        Page<Integer> beyondLast = page(client, PageRequest.create(3, 2));

        assertThat(first.list(), contains(1, 2));
        assertThat(first.totalSize(), is(5));
        assertThat(first.totalPages(), is(3));
        assertThat(middle.list(), contains(3, 4));
        assertThat(last.list(), contains(5));
        assertThat(beyondLast.list(), empty());
        assertThat(beyondLast.totalSize(), is(5));
    }

    @Test
    void returnsOffsetSliceWithoutRunningCountSql() {
        JdbcClient client = populatedClient();

        Slice<Integer> slice = client.execute(OFFSET_SQL)
                .param("type", "normal")
                .readColumns("id")
                .slice(PageRequest.create(1, 2), row -> row.value("id", Integer.class));

        assertThat(slice.list(), contains(3, 4));
        assertThat(slice.request().page(), is(1));
        assertThat(slice.request().size(), is(2));
    }

    @Test
    void returnsKeysetSliceUsingExplicitCursorPredicate() {
        JdbcClient client = populatedClient();

        Slice<Integer> slice = client.execute(KEYSET_SQL)
                .param("type", "normal")
                .param("afterId", 2)
                .readColumns("id")
                .slice(PageRequest.create(0, 2), row -> row.value("id", Integer.class));

        assertThat(slice.list(), contains(3, 4));
    }

    @Test
    void rejectsUnboundedSliceSql() {
        JdbcClient client = populatedClient();

        DataException error = assertThrows(DataException.class,
                                           () -> client.execute("SELECT id FROM pokemon ORDER BY id")
                                                   .slice(PageRequest.create(0, 2),
                                                          row -> row.value(1, Integer.class)));

        assertThat(error.getMessage(),
                   is("Page SQL must contain exactly one :__helidon_page_size parameter, but found 0"));
    }

    @Test
    void rejectsNonZeroPageForKeysetSlice() {
        JdbcClient client = populatedClient();

        assertThrows(DataException.class,
                     () -> client.execute(KEYSET_SQL)
                             .param("type", "normal")
                             .param("afterId", 2)
                             .slice(PageRequest.create(1, 2), row -> row.value(1, Integer.class)));
    }

    @Test
    void rejectsCountSqlWithDifferentFilters() {
        JdbcClient client = populatedClient();

        assertThrows(DataException.class,
                     () -> client.execute(OFFSET_SQL)
                             .param("type", "normal")
                             .page(PageRequest.create(0, 2),
                                   "SELECT COUNT(*) FROM pokemon",
                                   row -> row.value(1, Integer.class)));
    }

    @Test
    void rejectsNonIntegralCount() {
        JdbcClient client = populatedClient();
        String sql = "SELECT id FROM pokemon ORDER BY id "
                + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset";

        assertThrows(DataException.class,
                     () -> client.execute(sql)
                             .page(PageRequest.create(0, 2),
                                   "SELECT CAST(1.5 AS DECIMAL(2, 1))",
                                   row -> row.value(1, Integer.class)));
    }

    @Test
    void rejectsCountOutsidePageTotalRange() {
        JdbcClient client = populatedClient();
        String sql = "SELECT id FROM pokemon ORDER BY id "
                + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset";

        assertThrows(DataException.class,
                     () -> client.execute(sql)
                             .page(PageRequest.create(0, 2),
                                   "SELECT CAST(2147483648 AS BIGINT)",
                                   row -> row.value(1, Integer.class)));
    }

    @Test
    void skipsContentSqlWhenCountIsZero() {
        JdbcClient client = populatedClient();
        String invalidContentSql = "SELECT id FROM table_that_does_not_exist ORDER BY id "
                + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset";

        Page<Integer> page = client.execute(invalidContentSql)
                .page(PageRequest.create(0, 2), "SELECT 0", row -> row.value(1, Integer.class));

        assertThat(page.list(), empty());
        assertThat(page.totalSize(), is(0));
    }

    private static Page<Integer> page(JdbcClient client, PageRequest request) {
        return client.execute(OFFSET_SQL)
                .param("type", "normal")
                .readColumns("id")
                .page(request, COUNT_SQL, row -> row.value("id", Integer.class));
    }

    private static JdbcClient populatedClient() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcClient client = new JdbcClientImpl("test", dataSource, Optional.empty());
        client.execute("CREATE TABLE pokemon (id INTEGER PRIMARY KEY, name VARCHAR(40), type VARCHAR(20))")
                .discard();
        for (int id = 1; id <= 5; id++) {
            client.execute("INSERT INTO pokemon (id, name, type) VALUES (:id, :name, :type)")
                    .param("id", id)
                    .param("name", "Pokemon-" + id)
                    .param("type", "normal")
                    .discard();
        }
        client.execute("INSERT INTO pokemon (id, name, type) VALUES (6, 'Other', 'electric')")
                .discard();
        return client;
    }
}
