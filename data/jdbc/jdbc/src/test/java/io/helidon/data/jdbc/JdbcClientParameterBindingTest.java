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

import java.util.Optional;
import java.util.UUID;

import io.helidon.data.DataException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcClientParameterBindingTest {

    @Test
    void bindsDollarOrdinalParametersInSqlOrder() {
        JdbcClient client = client();

        String value = client.execute("SELECT $2, $1")
                .param("first")
                .param("second")
                .single(row -> row.value(1, String.class) + "-" + row.value(2, String.class));

        assertThat(value, is("second-first"));
    }

    @Test
    void bindsQuestionOrdinalParametersInSqlOrder() {
        JdbcClient client = client();

        String value = client.execute("SELECT ?2, ?1")
                .param("first")
                .param("second")
                .single(row -> row.value(1, String.class) + "-" + row.value(2, String.class));

        assertThat(value, is("second-first"));
    }

    @Test
    void rejectsUnusedNamedBindings() {
        JdbcClient client = client();

        assertThrows(DataException.class,
                     () -> client.execute("SELECT :name")
                             .param("name", "Pikachu")
                             .param("unused", "Raichu")
                             .single(row -> row.value(1, String.class)));
    }

    @Test
    void rejectsMixedBindingStyles() {
        JdbcClient client = client();

        assertThrows(DataException.class,
                     () -> client.execute("SELECT :name, $1")
                             .param("name", "Pikachu")
                             .param("Raichu")
                             .single(row -> row.value(1, String.class)));
    }

    private static JdbcClient client() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcClientImpl("test", dataSource, Optional.empty());
    }
}
