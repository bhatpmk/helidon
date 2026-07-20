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

import java.nio.file.Path;
import java.util.List;

import io.helidon.data.DataException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcInitScriptRunnerTest {

    @Test
    void splitsOnlyUnprotectedSemicolons() {
        List<String> statements = JdbcInitScriptRunner.split("""
                -- leading ; comment
                create table TEST (VALUE varchar(40));
                insert into TEST values ('a;b');
                insert into TEST values (q'[c;d]'); /* trailing ; comment */
                """);

        assertEquals(3, statements.size());
        assertEquals("insert into TEST values ('a;b')", statements.get(1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcInitScriptRunner.split("create procedure P as begin select 1; end"));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcInitScriptRunner.split("create function F() returns void as $$ begin; end $$"));
    }

    @Test
    void loadsAndExecutesClasspathScript() throws Exception {
        JdbcDataSource dataSource = dataSource("jdbc:h2:mem:init_script;DB_CLOSE_DELAY=-1");
        JdbcRunner runner = new JdbcRunner(dataSource,
                                           JdbcConnectionLease.ownedProvider());

        new JdbcInitScriptRunner(runner).run(Path.of("jdbc-init.sql"));

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM INIT_CONTACT")) {
            rows.next();
            assertEquals(2, rows.getInt(1));
        }
    }

    @Test
    void rollsBackDmlWhenLaterStatementFails() throws Exception {
        JdbcDataSource dataSource = dataSource("jdbc:h2:mem:init_rollback;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY)");
        }
        JdbcRunner runner = new JdbcRunner(dataSource,
                                           JdbcConnectionLease.ownedProvider());

        assertThrows(DataException.class,
                     () -> runner.executeScript(List.of("INSERT INTO ITEMS VALUES (1)",
                                                        "INSERT INTO ITEMS VALUES (1)")));

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            rows.next();
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void missingAndEmptyScriptsFailBeforePublication() {
        JdbcDataSource dataSource = dataSource("jdbc:h2:mem:init_missing;DB_CLOSE_DELAY=-1");
        JdbcRunner runner = new JdbcRunner(dataSource,
                                           JdbcConnectionLease.ownedProvider());

        assertThrows(DataException.class,
                     () -> new JdbcInitScriptRunner(runner).run(Path.of("missing-jdbc-init.sql")));
        assertThrows(IllegalArgumentException.class, () -> JdbcInitScriptRunner.split("-- comment only"));
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        return dataSource;
    }
}
