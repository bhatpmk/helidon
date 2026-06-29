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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamedSqlParserTest {

    @Test
    void parsesNamedParameters() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT * FROM POKEMON WHERE NAME = :name OR ALIAS = :name");

        assertThat(parsed.sql(), is("SELECT * FROM POKEMON WHERE NAME = ? OR ALIAS = ?"));
        assertThat(parsed.parameterMode(), is(ParsedSql.ParameterMode.NAMED));
        assertThat(parsed.parameterNames(), contains("name", "name"));
    }

    @Test
    void ignoresQuotedTextCommentsAndCasts() {
        ParsedSql parsed = NamedSqlParser.parse("""
                SELECT ':literal', "schema:column", `schema:param`, value::text
                FROM pokemon -- :comment
                // :slash_comment
                WHERE name = :name
                /* outer /* inner :ignored */ :block */
                """);

        assertThat(parsed.sql(), is("""
                SELECT ':literal', "schema:column", `schema:param`, value::text
                FROM pokemon -- :comment
                // :slash_comment
                WHERE name = ?
                /* outer /* inner :ignored */ :block */
                """));
        assertThat(parsed.parameterNames(), contains("name"));
    }

    @Test
    void rewritesOrdinalParameters() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT * FROM POKEMON WHERE ID = ?1 AND NAME = ?2");

        assertThat(parsed.sql(), is("SELECT * FROM POKEMON WHERE ID = ? AND NAME = ?"));
        assertThat(parsed.parameterMode(), is(ParsedSql.ParameterMode.ORDINAL));
        assertThat(parsed.parameterNames().isEmpty(), is(true));
        assertThat(parsed.parameterIndexes(), contains(1, 2));
    }

    @Test
    void rewritesDollarOrdinalParameters() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT * FROM POKEMON WHERE ID = $2 AND NAME = $1");

        assertThat(parsed.sql(), is("SELECT * FROM POKEMON WHERE ID = ? AND NAME = ?"));
        assertThat(parsed.parameterMode(), is(ParsedSql.ParameterMode.ORDINAL));
        assertThat(parsed.parameterIndexes(), contains(2, 1));
    }

    @Test
    void ignoresDialectQuotedText() {
        ParsedSql parsed = NamedSqlParser.parse("""
                SELECT $tag$:hidden ?$tag$, $$:hidden ?$$, q'[:hidden ?]', q'!can't :id?!'
                FROM [Schema Name]
                WHERE name = :name
                """);

        assertThat(parsed.sql(), is("""
                SELECT $tag$:hidden ?$tag$, $$:hidden ?$$, q'[:hidden ?]', q'!can't :id?!'
                FROM [Schema Name]
                WHERE name = ?
                """));
        assertThat(parsed.parameterNames(), contains("name"));
    }

    @Test
    void preservesSqlOperatorsThatLookLikeMarkers() {
        ParsedSql parsed = NamedSqlParser.parse("""
                SELECT doc ?| array['a', 'b'], doc ?& array['c'], ??, value := :value
                FROM pokemon
                WHERE type_id = :typeId AND payload::text IS NOT NULL
                """);

        assertThat(parsed.sql(), is("""
                SELECT doc ?| array['a', 'b'], doc ?& array['c'], ??, value := ?
                FROM pokemon
                WHERE type_id = ? AND payload::text IS NOT NULL
                """));
        assertThat(parsed.parameterNames(), contains("value", "typeId"));
    }

    @Test
    void doesNotHideArraySubscriptNamedParametersAsBracketIdentifiers() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT arr[:i], [Column Name] FROM pokemon");

        assertThat(parsed.sql(), is("SELECT arr[?], [Column Name] FROM pokemon"));
        assertThat(parsed.parameterNames(), contains("i"));
    }

    @Test
    void doesNotHideArraySubscriptOrdinalParametersAsBracketIdentifiers() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT arr[?2], arr[?1], [Column Name] FROM pokemon");

        assertThat(parsed.sql(), is("SELECT arr[?], arr[?], [Column Name] FROM pokemon"));
        assertThat(parsed.parameterIndexes(), contains(2, 1));
    }

    @Test
    void doesNotRewriteColonWithoutIdentifier() {
        ParsedSql parsed = NamedSqlParser.parse("SELECT ':', :1, :_name, :name-more FROM pokemon");

        assertThat(parsed.sql(), is("SELECT ':', :1, ?, ?-more FROM pokemon"));
        assertThat(parsed.parameterNames(), contains("_name", "name"));
    }

    @Test
    void rejectsMixedParameters() {
        assertThrows(io.helidon.data.DataException.class,
                     () -> NamedSqlParser.parse("SELECT * FROM POKEMON WHERE ID = :id AND NAME = ?1"));
    }
}
