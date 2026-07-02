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

import java.util.ArrayList;
import java.util.List;

import io.helidon.codegen.CodegenException;

final class JdbcSqlParameters {

    private final Mode mode;
    private final List<String> names;
    private final List<Integer> indexes;

    private JdbcSqlParameters(Mode mode, List<String> names, List<Integer> indexes) {
        this.mode = mode;
        this.names = List.copyOf(names);
        this.indexes = List.copyOf(indexes);
    }

    static JdbcSqlParameters parse(String sql) {
        return new Parser(sql).parse();
    }

    Mode mode() {
        return mode;
    }

    List<String> names() {
        return names;
    }

    List<Integer> indexes() {
        return indexes;
    }

    int count() {
        return mode == Mode.NAMED ? names.size() : indexes.size();
    }

    enum Mode {
        NONE,
        NAMED,
        POSITIONAL,
        ORDINAL
    }

    private static final class Parser {

        private final String sql;
        private final List<String> names = new ArrayList<>();
        private final List<Integer> indexes = new ArrayList<>();

        private int index;
        private Mode mode = Mode.NONE;

        private Parser(String sql) {
            this.sql = sql;
        }

        private JdbcSqlParameters parse() {
            while (index < sql.length()) {
                char ch = current();
                char next = peek(1);
                if (ch == '\'') {
                    quoted('\'', "''", true);
                } else if (ch == '"') {
                    quoted('"', "\"\"", false);
                } else if (ch == '`') {
                    quoted('`', "``", false);
                } else if (ch == '[' && bracketQuotedIdentifier()) {
                    // Handled by bracketQuotedIdentifier().
                } else if ((ch == 'q' || ch == 'Q') && next == '\'' && oracleQuotedString()) {
                    // Handled by oracleQuotedString().
                } else if (ch == '$' && dollarQuotedString()) {
                    // Handled by dollarQuotedString().
                } else if (ch == '$' && Character.isDigit(next)) {
                    ordinalParameter();
                } else if ((ch == '-' && next == '-') || (ch == '/' && next == '/')) {
                    lineComment();
                } else if (ch == '/' && next == '*') {
                    blockComment();
                } else if (ch == ':' && next == ':') {
                    skip(2);
                } else if (ch == ':' && next == '=') {
                    skip(2);
                } else if (ch == ':' && isIdentifierStart(next)) {
                    namedParameter();
                } else if (ch == '?' && Character.isDigit(next)) {
                    ordinalParameter();
                } else if (ch == '?' && next == '?') {
                    skip(2);
                } else if (ch == '?' && positionalParameter(next)) {
                    positionalParameter();
                } else {
                    skip();
                }
            }
            return new JdbcSqlParameters(mode, names, indexes);
        }

        private void namedParameter() {
            parameterMode(Mode.NAMED);
            int start = index + 1;
            int end = start + 1;
            while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                end++;
            }
            names.add(sql.substring(start, end));
            index = end;
        }

        private void ordinalParameter() {
            parameterMode(Mode.ORDINAL);
            int start = index + 1;
            int end = start + 1;
            while (end < sql.length() && Character.isDigit(sql.charAt(end))) {
                end++;
            }
            int parameterIndex;
            try {
                parameterIndex = Integer.parseInt(sql.substring(start, end));
            } catch (NumberFormatException e) {
                throw new CodegenException("Ordinal SQL parameter "
                                                   + sql.substring(index, end)
                                                   + " does not contain a valid numeric index",
                                           e);
            }
            if (parameterIndex < 1) {
                throw new CodegenException("Ordinal SQL parameter indexes start with 1: "
                                                   + sql.substring(index, end));
            }
            indexes.add(parameterIndex);
            index = end;
        }

        private void positionalParameter() {
            parameterMode(Mode.POSITIONAL);
            indexes.add(indexes.size() + 1);
            index++;
        }

        private void parameterMode(Mode newMode) {
            if (mode == Mode.NONE) {
                mode = newMode;
                return;
            }
            if (mode != newMode) {
                throw new CodegenException("SQL statement must not mix " + mode + " and " + newMode + " parameters");
            }
        }

        private void quoted(char quote, String escapedQuote, boolean backslashEscapes) {
            skip();
            while (index < sql.length()) {
                if (matches(escapedQuote)) {
                    skip(escapedQuote.length());
                } else {
                    char ch = current();
                    if (backslashEscapes && ch == '\\' && has(1)) {
                        skip(2);
                    } else {
                        skip();
                        if (ch == quote) {
                            return;
                        }
                    }
                }
            }
        }

        private boolean bracketQuotedIdentifier() {
            int end = index + 1;
            boolean hasContent = false;
            while (end < sql.length()) {
                char ch = sql.charAt(end);
                if (ch == ']') {
                    if (end + 1 < sql.length() && sql.charAt(end + 1) == ']') {
                        end += 2;
                        hasContent = true;
                    } else if (hasContent) {
                        index = end + 1;
                        return true;
                    } else {
                        return false;
                    }
                } else if (isSimpleBracketIdentifierPart(ch)) {
                    end++;
                    hasContent = true;
                } else {
                    return false;
                }
            }
            return false;
        }

        private boolean dollarQuotedString() {
            int tagEnd = index + 1;
            while (tagEnd < sql.length() && isDollarQuoteTagPart(sql.charAt(tagEnd))) {
                tagEnd++;
            }
            if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$') {
                return false;
            }
            String delimiter = sql.substring(index, tagEnd + 1);
            int end = sql.indexOf(delimiter, tagEnd + 1);
            if (end < 0) {
                return false;
            }
            index = end + delimiter.length();
            return true;
        }

        private boolean oracleQuotedString() {
            if (!has(2)) {
                return false;
            }
            char delimiter = peek(2);
            if (delimiter == '\'' || Character.isWhitespace(delimiter)) {
                return false;
            }
            char terminator = switch (delimiter) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> delimiter;
            };
            int end = index + 3;
            while (end + 1 < sql.length()) {
                if (sql.charAt(end) == terminator && sql.charAt(end + 1) == '\'') {
                    index = end + 2;
                    return true;
                }
                end++;
            }
            return false;
        }

        private void lineComment() {
            skip(2);
            while (index < sql.length()) {
                char ch = current();
                skip();
                if (ch == '\n' || ch == '\r') {
                    return;
                }
            }
        }

        private void blockComment() {
            int depth = 1;
            skip(2);
            while (index < sql.length()) {
                if (matches("/*")) {
                    skip(2);
                    depth++;
                } else if (matches("*/")) {
                    skip(2);
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                } else {
                    skip();
                }
            }
        }

        private void skip() {
            index++;
        }

        private void skip(int count) {
            index += count;
        }

        private boolean matches(String text) {
            return sql.startsWith(text, index);
        }

        private boolean has(int offset) {
            return index + offset < sql.length();
        }

        private char current() {
            return sql.charAt(index);
        }

        private char peek(int offset) {
            return has(offset) ? sql.charAt(index + offset) : '\0';
        }

        private static boolean isIdentifierStart(char ch) {
            return ch == '_' || Character.isLetter(ch);
        }

        private static boolean isIdentifierPart(char ch) {
            return ch == '_' || Character.isLetterOrDigit(ch);
        }

        private static boolean positionalParameter(char next) {
            return next != '?' && next != '|' && next != '&';
        }

        private static boolean isSimpleBracketIdentifierPart(char ch) {
            return ch == '_' || ch == '$' || ch == '#' || Character.isLetterOrDigit(ch);
        }

        private static boolean isDollarQuoteTagPart(char ch) {
            return ch == '_' || Character.isLetterOrDigit(ch);
        }
    }
}
