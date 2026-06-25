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
package io.helidon.data.jdbc.namedparameters;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.antlr.v4.runtime.Token.DEFAULT_CHANNEL;
import static org.antlr.v4.runtime.Token.HIDDEN_CHANNEL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;

final class ColonPrefixedNamedParameterLexerTest {

    private ColonPrefixedNamedParameterLexerTest() {
        super();
    }

    @ParameterizedTest
    @MethodSource("standaloneTokenCases")
    void shouldRecognizeStandaloneTokens(final String input,
                                         final String expectedSymbolicName,
                                         final String expectedText) {
        assertThat(visibleTokens(input),
                   is(List.of(token(expectedSymbolicName, expectedText))));
    }

    @Test
    void shouldTreatColonByItselfAsOther() {
        assertThat(visibleTokens(":"),
                   is(List.of(token("OTHER", ":"))));
    }

    @Test
    void shouldNotTreatColonDigitAsNamedParameter() {
        assertThat(visibleTokens(":1"),
                   is(List.of(token("OTHER", ":"),
                              token("CHUNK", "1"))));
    }

    @Test
    void shouldStopNamedParameterAtIdentifierBoundary() {
        assertThat(visibleTokens(":name-more"),
                   is(List.of(token("PARAMETER_NAME", ":name"),
                              token("CHUNK", "-m"),
                              token("CHUNK", "ore"))));
    }

    @Test
    void shouldRecognizeDottedNamedParameterPath() {
        assertThat(visibleTokens(":q.idRange.from"),
                   is(List.of(token("PARAMETER_NAME", ":q.idRange.from"))));
    }

    @Test
    void shouldStopDottedNamedParameterAtIncompletePath() {
        assertThat(visibleTokens(":q."),
                   is(List.of(token("PARAMETER_NAME", ":q"),
                              token("CHUNK", "."))));
    }

    @Test
    void shouldPreferPostgresqlCastOverNamedParameter() {
        assertThat(visibleTokens("::foo"),
                   is(List.of(token("POSTGRESQL_CAST", "::"),
                              token("CHUNK", "foo"))));
    }

    @Test
    void shouldPreferColonEqualsAssignmentOverNamedParameter() {
        assertThat(visibleTokens(":=foo"),
                   is(List.of(token("COLON_EQUALS_ASSIGNMENT", ":="),
                              token("CHUNK", "foo"))));
    }

    @Test
    void shouldPreferDoubleQuestionMarkOverTwoParameterMarkers() {
        // Somewhat surprising, perhaps; not sure what else to do here
        assertThat(visibleTokens("???"),
                   is(List.of(token("DOUBLE_QUESTION_MARK", "??"),
                              token("PARAMETER_MARKER", "?"))));
    }

    @Test
    void shouldNotSwallowNamedParameterAfterMinus() {
        assertThat(visibleTokens("x-:offset"),
                   is(List.of(token("CHUNK", "x"),
                              token("OTHER", "-"),
                              token("PARAMETER_NAME", ":offset"))));
    }

    @Test
    void shouldNotSwallowParameterMarkerAfterMinus() {
        assertThat(visibleTokens("1-?"),
                   is(List.of(token("CHUNK", "1"),
                              token("OTHER", "-"),
                              token("PARAMETER_MARKER", "?"))));
    }

    @Test
    void shouldNotSwallowNamedParameterAfterSlash() {
        assertThat(visibleTokens("1/:denominator"),
                   is(List.of(token("CHUNK", "1"),
                              token("OTHER", "/"),
                              token("PARAMETER_NAME", ":denominator"))));
    }

    @Test
    void shouldPreferPostgresqlJsonbExistsAnyOverParameterMarker() {
        assertThat(visibleTokens("doc ?| array['a','b']"),
                   is(List.of(token("CHUNK", "doc"),
                              token("POSTGRESQL_JSONB_EXISTS_ANY", "?|"),
                              token("CHUNK", "array"),
                              token("OTHER", "["),
                              token("CHUNK", ","),
                              token("OTHER", "]"))));
    }

    @Test
    void shouldPreferPostgresqlJsonbExistsAllOverParameterMarker() {
        assertThat(visibleTokens("doc ?& array['a','b']"),
                   is(List.of(token("CHUNK", "doc"),
                              token("POSTGRESQL_JSONB_EXISTS_ALL", "?&"),
                              token("CHUNK", "array"),
                              token("OTHER", "["),
                              token("CHUNK", ","),
                              token("OTHER", "]"))));
    }

    @Test
    void shouldNotHideParameterMarkerInsideBrackets() {
        assertThat(visibleTokens("arr[?]"),
                   is(List.of(token("CHUNK", "arr"),
                              token("OTHER", "["),
                              token("PARAMETER_MARKER", "?"),
                              token("OTHER", "]"))));
    }

    @Test
    void shouldNotHideNamedParameterInsideBrackets() {
        assertThat(visibleTokens("arr[:i]"),
                   is(List.of(token("CHUNK", "arr"),
                              token("OTHER", "["),
                              token("PARAMETER_NAME", ":i"),
                              token("OTHER", "]"))));
    }

    @Test
    void shouldHideBracketQuotedIdentifier() {
        assertThat(allTokens("[Column Name]"),
                   is(List.of(token("BRACKET_QUOTED_IDENTIFIER", "[Column Name]", HIDDEN_CHANNEL))));
    }

    @Test
    void shouldHideBracketQuotedIdentifierWithEscapedBracket() {
        assertThat(allTokens("[weird]]name]"),
                   is(List.of(token("BRACKET_QUOTED_IDENTIFIER", "[weird]]name]", HIDDEN_CHANNEL))));
    }

    @ParameterizedTest
    @MethodSource("hiddenParameterCases")
    void shouldHideParameterLookingTextInsideQuotedOrCommentedRegions(String input,
                                                                      String expectedHiddenTokenName) {
        List<LexedToken> allTokens = allTokens(input);
        List<String> visibleTokenSymbolicNames = allTokens.stream()
            .filter(ColonPrefixedNamedParameterLexerTest::visible)
            .map(LexedToken::symbolicName)
            .toList();
        assertThat(visibleTokenSymbolicNames,
                   everyItem(not(isIn(List.of("PARAMETER_NAME",
                                              "PARAMETER_MARKER")))));
        assertThat(allTokens,
                   is(List.of(token(expectedHiddenTokenName, input, HIDDEN_CHANNEL))));
    }

    @Test
    void shouldHideWhitespace() {
        assertThat(allTokens(" \t\r\n"),
                   is(List.of(token("WHITESPACE", " \t\r\n", Token.HIDDEN_CHANNEL))));
    }

    @Test
    void shouldTokenizeMixedSqlInVisibleOrder() {
        assertThat(visibleTokens("select :id, ? from t where c::text = '-- :hidden'"),
                   is(List.of(token("CHUNK", "select"),
                              token("PARAMETER_NAME", ":id"),
                              token("CHUNK", ","),
                              token("PARAMETER_MARKER", "?"),
                              token("CHUNK", "from"),
                              token("CHUNK", "t"),
                              token("CHUNK", "where"),
                              token("CHUNK", "c"),
                              token("POSTGRESQL_CAST", "::"),
                              token("CHUNK", "text"),
                              token("CHUNK", "="))));
    }

    @Test
    void shouldRewriteOnlyVisibleParameterMarkers() {
        List<String> markers = new ArrayList<>();

        String sql = NamedParameters.rewrite("""
                SELECT ':not_a_parameter',
                       "quoted:id",
                       c::text,
                       q'[:also_hidden]',
                       :id,
                       ?
                FROM pokemon
                WHERE name = :name
                -- :commented
                """,
                                             markers::add);

        assertThat(markers, is(List.of(":id", "?", ":name")));
        assertThat(sql, is("""
                SELECT ':not_a_parameter',
                       "quoted:id",
                       c::text,
                       q'[:also_hidden]',
                       ?,
                       ?
                FROM pokemon
                WHERE name = ?
                -- :commented
                """));
    }

    @Test
    void shouldRewriteNamedParametersAroundPostgresqlJsonbExistsOperators() {
        List<String> markers = new ArrayList<>();

        String sql = NamedParameters.rewrite("""
                SELECT *
                FROM document
                WHERE payload ?| array[:anyKey]
                  AND payload ?& array[:allKey]
                  AND type = :type
                """,
                                             markers::add);

        assertThat(markers, is(List.of(":anyKey", ":allKey", ":type")));
        assertThat(sql, is("""
                SELECT *
                FROM document
                WHERE payload ?| array[?]
                  AND payload ?& array[?]
                  AND type = ?
                """));
    }


    /*
     * Static methods.
     */


    private static Stream<Arguments> standaloneTokenCases() {
        return
            Stream.of(Arguments.of("::", "POSTGRESQL_CAST", "::"),
                      Arguments.of(":=", "COLON_EQUALS_ASSIGNMENT", ":="),
                      Arguments.of("??", "DOUBLE_QUESTION_MARK", "??"),
                                      Arguments.of("?", "PARAMETER_MARKER", "?"),
                                      Arguments.of(":name", "PARAMETER_NAME", ":name"),
                                      Arguments.of(":_name", "PARAMETER_NAME", ":_name"),
                                      Arguments.of(":name1", "PARAMETER_NAME", ":name1"),
                                      Arguments.of(":name.one", "PARAMETER_NAME", ":name.one"),
                                      Arguments.of(":name.one2.two", "PARAMETER_NAME", ":name.one2.two"),
                                      Arguments.of("select", "CHUNK", "select"),
                                      Arguments.of(";", "CHUNK", ";"));
    }

    private static Stream<Arguments> hiddenParameterCases() {
        return
            Stream.of(Arguments.of("-- :hidden ?", "LINE_COMMENT"),
                      Arguments.of("// :hidden ?", "LINE_COMMENT"),
                      Arguments.of("/* :hidden ? */", "BLOCK_COMMENT"),
                      Arguments.of("/* outer /* inner */ :hidden ? */", "BLOCK_COMMENT"),
                      Arguments.of("':hidden ?'", "SINGLE_QUOTED_STRING"),
                      Arguments.of("'can\\'t :id'", "SINGLE_QUOTED_STRING"),
                      Arguments.of("\":hidden ?\"", "DOUBLE_QUOTED_STRING"),
                      Arguments.of("`:hidden ?`", "BACKTICK_QUOTED_STRING"),
                      Arguments.of("$$:hidden ?$$", "DOLLAR_QUOTED_STRING"),
                      Arguments.of("$foo$ text $bar$ :hidden ? $foo$", "DOLLAR_QUOTED_STRING"),
                      Arguments.of("q'[:hidden ?]'", "ORACLE_QUOTED_STRING"),
                      Arguments.of("q'!can't :id?!'", "ORACLE_QUOTED_STRING"));
    }

    private static List<LexedToken> visibleTokens(String input) {
        return allTokens(input)
            .stream()
            .filter(ColonPrefixedNamedParameterLexerTest::visible)
            .toList();
    }

    private static List<LexedToken> allTokens(String input) {
        return new ColonPrefixedNamedParameterLexer(CharStreams.fromString(input))
            .getAllTokens()
            .stream()
            .map(token -> new LexedToken(ColonPrefixedNamedParameterLexer.VOCABULARY.getSymbolicName(token.getType()),
                                         token.getText(),
                                         token.getChannel()))
            .toList();
    }

    private static boolean visible(LexedToken token) {
        return token.channel() == DEFAULT_CHANNEL;
    }

    private static LexedToken token(String symbolicName, String text) {
        return token(symbolicName, text, DEFAULT_CHANNEL);
    }

    private static LexedToken token(String symbolicName, String text, int channel) {
        return new LexedToken(symbolicName, text, channel);
    }


    /*
     * Inner and nested classes.
     */


    private record LexedToken(String symbolicName, String text, int channel) {
    }

}
