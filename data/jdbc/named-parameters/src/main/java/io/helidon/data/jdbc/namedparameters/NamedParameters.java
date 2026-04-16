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

import java.util.function.Consumer;

import io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer;

import org.antlr.v4.runtime.Token;

import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.BACKTICK_QUOTED_STRING;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.BLOCK_COMMENT;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.BRACKET_QUOTED_IDENTIFIER;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.CHUNK;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.COLON_EQUALS_ASSIGNMENT;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.DOLLAR_QUOTED_STRING;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.DOUBLE_QUESTION_MARK;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.DOUBLE_QUOTED_STRING;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.LINE_COMMENT;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.ORACLE_QUOTED_STRING;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.OTHER;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.PARAMETER_MARKER;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.PARAMETER_NAME;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.POSTGRESQL_CAST;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.SINGLE_QUOTED_STRING;
import static io.helidon.data.jdbc.namedparameters.antlr4.ColonPrefixedNamedParameterLexer.WHITESPACE;
import static java.util.Objects.requireNonNull;
import static org.antlr.v4.runtime.CharStreams.fromString;
import static org.antlr.v4.runtime.Recognizer.EOF;

/**
 * A utility class providing named parameter marker support for JDBC {@link java.sql.PreparedStatement}s.
 *
 * @see #rewrite(String, Consumer)
 */
public final class NamedParameters {

    private NamedParameters() {
        super();
    }

    /**
     * Converts a pseudo-JDBC statement featuring colon-prefixed <dfn>named parameter markers</dfn> (<i>e.g.</i> {@code
     * :name}) into a valid JDBC statement featuring only valid JDBC (positional) parameter markers (`?`), passing any
     * encountered parameter markers, named or positional, to the supplied {@link Consumer}, and returns the rewritten
     * statement.
     *
     * <p>If no colon-prefixed named parameter markers are found, a statement equal to the supplied statement is
     * returned.</p>
     *
     * @param pseudoStatement a non-{@code null} pseudo-JDBC statement possibly featuring colon-prefixed named parameter
     * markers (<i>e.g.</i> {@code :name})
     * @param parameterMarkers a non-{@code null} {@link Consumer} that is notified of each named or positional
     * parameter marker encountered; the marker itself is supplied verbatim
     * @return a JDBC-compatible pseudo-SQL statement suitable for supplying to {@link
     * java.sql.Connection#prepareStatement(String)} and all analogous methods
     * @see java.sql.Connection#prepareCall(String)
     * @see java.sql.Connection#prepareCall(String, int, int)
     * @see java.sql.Connection#prepareCall(String, int, int, int)
     * @see java.sql.Connection#prepareStatement(String)
     * @see java.sql.Connection#prepareStatement(String, int)
     * @see java.sql.Connection#prepareStatement(String, int, int)
     * @see java.sql.Connection#prepareStatement(String, int, int, int)
     * @see java.sql.Connection#prepareStatement(String, int[])
     * @see java.sql.Connection#prepareStatement(String, String[])
     */
    public static String rewrite(String pseudoStatement, Consumer<? super String> parameterMarkers) {
        requireNonNull(parameterMarkers, "parameterMarkers");
        StringBuilder sb = new StringBuilder();
        ColonPrefixedNamedParameterLexer lexer = new ColonPrefixedNamedParameterLexer(fromString(pseudoStatement));
        Token t = lexer.nextToken();
        while (t.getType() != EOF) {
            switch (t.getType()) {
            case PARAMETER_NAME -> {
                parameterMarkers.accept(t.getText());
                sb.append('?');
            }
            case PARAMETER_MARKER -> {
                parameterMarkers.accept(t.getText()); // "?"
                sb.append(t.getText()); // "?"
            }
            case
                BACKTICK_QUOTED_STRING,
                BLOCK_COMMENT,
                BRACKET_QUOTED_IDENTIFIER,
                CHUNK,
                COLON_EQUALS_ASSIGNMENT,
                DOLLAR_QUOTED_STRING,
                DOUBLE_QUESTION_MARK,
                DOUBLE_QUOTED_STRING,
                LINE_COMMENT,
                ORACLE_QUOTED_STRING,
                OTHER,
                POSTGRESQL_CAST,
                SINGLE_QUOTED_STRING,
                WHITESPACE -> sb.append(t.getText());
            default -> throw new AssertionError();
            }
            t = lexer.nextToken();
        }
        return sb.toString();
    }

}
