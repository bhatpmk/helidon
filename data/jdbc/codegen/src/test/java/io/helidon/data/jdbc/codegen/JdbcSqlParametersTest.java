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

import io.helidon.codegen.CodegenException;

import org.junit.jupiter.api.Test;

import static io.helidon.data.jdbc.codegen.JdbcSqlParameters.Mode.NAMED;
import static io.helidon.data.jdbc.codegen.JdbcSqlParameters.Mode.NONE;
import static io.helidon.data.jdbc.codegen.JdbcSqlParameters.Mode.ORDINAL;
import static io.helidon.data.jdbc.codegen.JdbcSqlParameters.Mode.POSITIONAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSqlParametersTest {

    @Test
    void parsesNamedParametersAdjacentToSqlPunctuation() {
        JdbcSqlParameters parameters = JdbcSqlParameters.parse("""
                INSERT INTO POKEMON (NAME, TYPE_ID)
                VALUES (:pokemonName, :typeId)
                """);

        assertThat(parameters.mode(), is(NAMED));
        assertThat(parameters.names(), is(List.of("pokemonName", "typeId")));
        assertThat(parameters.indexes(), is(List.of()));
        assertThat(parameters.count(), is(2));
    }

    @Test
    void parsesPositionalParameters() {
        JdbcSqlParameters parameters = JdbcSqlParameters.parse("""
                UPDATE POKEMON SET NAME = ? WHERE ID = ?
                """);

        assertThat(parameters.mode(), is(POSITIONAL));
        assertThat(parameters.count(), is(2));
        assertThat(parameters.indexes(), is(List.of(1, 2)));
    }

    @Test
    void parsesOrdinalParameters() {
        JdbcSqlParameters parameters = JdbcSqlParameters.parse("""
                SELECT * FROM POKEMON WHERE NAME = ?2 AND TYPE_ID = $1
                """);

        assertThat(parameters.mode(), is(ORDINAL));
        assertThat(parameters.indexes(), is(List.of(2, 1)));
    }

    @Test
    void ignoresParameterLookingTextInsideQuotedAndCommentedRegions() {
        List<String> sql = List.of(
                "-- :hidden ?\nSELECT * FROM POKEMON WHERE ID = :id",
                "// :hidden ?\nSELECT * FROM POKEMON WHERE ID = :id",
                "/* :hidden ? */ SELECT * FROM POKEMON WHERE ID = :id",
                "/* outer /* inner */ :hidden ? */ SELECT * FROM POKEMON WHERE ID = :id",
                "SELECT ':hidden ?' FROM POKEMON WHERE ID = :id",
                "SELECT 'can\\'t :hidden ?' FROM POKEMON WHERE ID = :id",
                "SELECT \":hidden ?\" FROM POKEMON WHERE ID = :id",
                "SELECT `:hidden ?` FROM POKEMON WHERE ID = :id",
                "SELECT $$:hidden ?$$ FROM POKEMON WHERE ID = :id",
                "SELECT $tag$ text $other$ :hidden ? $tag$ FROM POKEMON WHERE ID = :id",
                "SELECT q'[:hidden ?]' FROM POKEMON WHERE ID = :id",
                "SELECT q'!can't :hidden ?!' FROM POKEMON WHERE ID = :id");

        for (String statement : sql) {
            JdbcSqlParameters parameters = JdbcSqlParameters.parse(statement);

            assertThat(statement, parameters.mode(), is(NAMED));
            assertThat(statement, parameters.names(), is(List.of("id")));
        }
    }

    @Test
    void ignoresDialectOperatorsThatLookLikeParameters() {
        JdbcSqlParameters parameters = JdbcSqlParameters.parse("""
                SELECT doc::text, x := y, flags ?? 'a', doc ?| array['a'], doc ?& array['a']
                FROM POKEMON
                WHERE ID = :id
                """);

        assertThat(parameters.mode(), is(NAMED));
        assertThat(parameters.names(), is(List.of("id")));
    }

    @Test
    void doesNotHideArraySubscriptParametersInsideBrackets() {
        JdbcSqlParameters named = JdbcSqlParameters.parse("SELECT values[:index] FROM POKEMON");
        JdbcSqlParameters positional = JdbcSqlParameters.parse("SELECT values[?] FROM POKEMON");

        assertThat(named.mode(), is(NAMED));
        assertThat(named.names(), is(List.of("index")));
        assertThat(positional.mode(), is(POSITIONAL));
        assertThat(positional.indexes(), is(List.of(1)));
    }

    @Test
    void returnsNoneWhenSqlDeclaresNoParameters() {
        JdbcSqlParameters parameters = JdbcSqlParameters.parse("SELECT [Column Name], ':hidden' FROM POKEMON");

        assertThat(parameters.mode(), is(NONE));
        assertThat(parameters.names(), is(List.of()));
        assertThat(parameters.indexes(), is(List.of()));
    }

    @Test
    void rejectsMixedParameterModes() {
        assertThrows(CodegenException.class, () -> JdbcSqlParameters.parse("SELECT * FROM POKEMON WHERE ID = :id AND NAME = ?"));
        assertThrows(CodegenException.class, () -> JdbcSqlParameters.parse("SELECT * FROM POKEMON WHERE ID = ? AND NAME = $1"));
    }

    @Test
    void rejectsZeroOrdinalParameter() {
        assertThrows(CodegenException.class, () -> JdbcSqlParameters.parse("SELECT * FROM POKEMON WHERE ID = $0"));
        assertThrows(CodegenException.class, () -> JdbcSqlParameters.parse("SELECT * FROM POKEMON WHERE ID = ?0"));
    }
}
