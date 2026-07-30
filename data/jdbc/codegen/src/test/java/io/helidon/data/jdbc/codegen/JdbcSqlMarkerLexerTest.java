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
package io.helidon.data.jdbc.codegen;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSqlMarkerLexerTest {

    @Test
    void rewritesNamedMarkersInEncounterOrder() {
        JdbcSqlMarkerLexer.Result result = JdbcSqlMarkerLexer.parse(
                "select * from T where ID = :id or PARENT_ID = :id and NAME = :name");

        assertEquals("select * from T where ID = ? or PARENT_ID = ? and NAME = ?", result.sql());
        assertEquals(List.of("id", "id", "name"), result.markers());
    }

    @Test
    void protectsQuotedCommentedAndVendorSyntax() {
        String sql = """
                select ':literal', "quoted:name", `mysql:name`, [sql:name], value::text, data ?| array['x']
                from T -- :line
                where ID = :id /* :block /* :nested */ still comment */
                  and BODY = $tag$:dollar$tag$ and Q = q'[oracle:name]'
                """;

        JdbcSqlMarkerLexer.Result result = JdbcSqlMarkerLexer.parse(sql);

        assertEquals(List.of("id"), result.markers());
        assertEquals(sql.replace("ID = :id", "ID = ?"), result.sql());
    }

    @Test
    void rejectsUnsupportedMarkerFormsAndMalformedRegions() {
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select :user.id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select ?"));
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select #name"));
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select <name>"));
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select 'unterminated"));
        assertThrows(IllegalArgumentException.class, () -> JdbcSqlMarkerLexer.parse("select /* unterminated"));
    }

    @Test
    void extractsOnlyOuterProjectionAliases() {
        String sql = """
                with SOURCE as (
                    select cast(ID as bigint) as inner_id from CONTACT as nested_contact
                )
                select cast(source.ID as bigint) as id,
                       coalesce(source.NAME, 'as from') as /* mapper hint */ "displayName"
                from SOURCE as source
                """;

        assertEquals(List.of("id", "displayName"), JdbcProjectionAliasLexer.aliases(sql));
        assertEquals(List.of("display\"Name"),
                     JdbcProjectionAliasLexer.aliases("select NAME as \"display\"\"Name\" from CONTACT"));
    }
}
