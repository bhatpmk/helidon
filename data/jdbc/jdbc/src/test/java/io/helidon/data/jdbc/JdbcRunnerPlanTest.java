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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcRunnerPlanTest {

    @Test
    void executesMultipleOperationPlan() {
        JdbcRunner runner = runner();

        JdbcExecutionResult result = runner.execute(new JdbcPlan(List.of(
                JdbcOperation.update("""
                                     CREATE TABLE pokemon (
                                         id BIGINT PRIMARY KEY,
                                         name VARCHAR(64)
                                     )
                                     """,
                                     List.of()),
                JdbcOperation.update("INSERT INTO pokemon(id, name) VALUES (:id, :name)",
                                     List.of(JdbcParameter.create("id", 1L),
                                             JdbcParameter.create("name", "Pikachu"))),
                JdbcOperation.query("SELECT name FROM pokemon WHERE id = :id",
                                    List.of(JdbcParameter.create("id", 1L))))));

        assertThat(result.operations().size(), is(3));
        assertThat(result.operations().get(0).ref().index(), is(0));
        assertThat(result.operations().get(1).ref().index(), is(1));
        assertThat(result.operations().get(2).ref().index(), is(2));
        assertThat(result.operations().get(1).directResults().at(0), instanceOf(JdbcUpdateCountResult.class));
        JdbcRowsResult rows = (JdbcRowsResult) result.operations().get(2).directResults().at(0);
        assertThat(rows.rows().rows().getFirst().value("name", String.class), is("Pikachu"));
    }

    @Test
    void failureCarriesPartialPlanResult() {
        JdbcRunner runner = runner();

        JdbcExecutionException exception = assertThrows(JdbcExecutionException.class,
                                                        () -> runner.execute(new JdbcPlan(List.of(
                                                                JdbcOperation.update("""
                                                                                     CREATE TABLE pokemon (
                                                                                         id BIGINT PRIMARY KEY
                                                                                     )
                                                                                     """,
                                                                                     List.of()),
                                                                JdbcOperation.update("INSERT INTO missing_table(id) VALUES (:id)",
                                                                                     List.of(JdbcParameter.create("id", 1L)))))));

        assertThat(exception.result().operations().size(), is(2));
        assertThat(exception.result().operations().get(0).failure(), is(Optional.empty()));
        assertThat(exception.result().operations().get(1).failure().isPresent(), is(true));
    }

    private static JdbcRunner runner() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcRunner(dataSource);
    }
}
