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

import java.util.ArrayList;
import java.util.List;

import io.helidon.data.DataException;

final class NamedSqlParser {

    private NamedSqlParser() {
    }

    static ParsedSql parse(String sql) {
        Parser parser = new Parser(sql);
        return parser.parse();
    }

    private static final class Parser {

        private final String sql;
        private final StringBuilder rewritten;
        private final List<String> parameterNames = new ArrayList<>();
        private final List<Integer> parameterIndexes = new ArrayList<>();

        private int index;
        private ParsedSql.ParameterMode parameterMode = ParsedSql.ParameterMode.NONE;

        private Parser(String sql) {
            this.sql = sql;
            this.rewritten = new StringBuilder(sql.length());
        }

        private ParsedSql parse() {
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
                    append(2);
                } else if (ch == ':' && next == '=') {
                    append(2);
                } else if (ch == ':' && isIdentifierStart(next)) {
                    namedParameter();
                } else if (ch == '?' && Character.isDigit(next)) {
                    ordinalParameter();
                } else if (ch == '?' && next == '?') {
                    append(2);
                } else if (ch == '?' && positionalParameter(next)) {
                    positionalParameter();
                } else {
                    append();
                }
            }
            return switch (parameterMode) {
            case NONE -> ParsedSql.none(rewritten.toString());
            case NAMED -> ParsedSql.named(rewritten.toString(), parameterNames);
            case POSITIONAL -> ParsedSql.positional(rewritten.toString(), parameterIndexes.size());
            case ORDINAL -> ParsedSql.ordinal(rewritten.toString(), parameterIndexes);
            };
        }

        private void namedParameter() {
            parameterMode(ParsedSql.ParameterMode.NAMED);
            int start = index + 1;
            int end = start + 1;
            while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                end++;
            }
            parameterNames.add(sql.substring(start, end));
            rewritten.append('?');
            index = end;
        }

        private void ordinalParameter() {
            parameterMode(ParsedSql.ParameterMode.ORDINAL);
            int start = index + 1;
            int end = start + 1;
            while (end < sql.length() && Character.isDigit(sql.charAt(end))) {
                end++;
            }
            int parameterIndex;
            try {
                parameterIndex = Integer.parseInt(sql.substring(start, end));
            } catch (NumberFormatException e) {
                throw new DataException("Ordinal SQL parameter " + sql.substring(index, end)
                                                + " does not contain a valid numeric index.", e);
            }
            if (parameterIndex < 1) {
                throw new DataException("Ordinal SQL parameter indexes start with 1: " + sql.substring(index, end));
            }
            parameterIndexes.add(parameterIndex);
            rewritten.append('?');
            index = end;
        }

        private void positionalParameter() {
            parameterMode(ParsedSql.ParameterMode.POSITIONAL);
            parameterIndexes.add(parameterIndexes.size() + 1);
            rewritten.append('?');
            index++;
        }

        private void parameterMode(ParsedSql.ParameterMode mode) {
            if (parameterMode == ParsedSql.ParameterMode.NONE) {
                parameterMode = mode;
                return;
            }
            if (parameterMode != mode) {
                throw new DataException("SQL statement must not mix " + parameterMode + " and " + mode + " parameters");
            }
        }

        private void quoted(char quote, String escapedQuote, boolean backslashEscapes) {
            append();
            while (index < sql.length()) {
                if (matches(escapedQuote)) {
                    append(escapedQuote.length());
                } else {
                    char ch = current();
                    if (backslashEscapes && ch == '\\' && has(1)) {
                        append(2);
                    } else {
                        append();
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
                        rewritten.append(sql, index, end + 1);
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
            rewritten.append(sql, index, end + delimiter.length());
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
                    rewritten.append(sql, index, end + 2);
                    index = end + 2;
                    return true;
                }
                end++;
            }
            return false;
        }

        private void lineComment() {
            append(2);
            while (index < sql.length()) {
                char ch = current();
                append();
                if (ch == '\n' || ch == '\r') {
                    return;
                }
            }
        }

        private void blockComment() {
            int depth = 1;
            append(2);
            while (index < sql.length()) {
                if (matches("/*")) {
                    append(2);
                    depth++;
                } else if (matches("*/")) {
                    append(2);
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                } else {
                    append();
                }
            }
        }

        private void append() {
            rewritten.append(current());
            index++;
        }

        private void append(int count) {
            rewritten.append(sql, index, index + count);
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

        private static boolean isDollarQuoteTagPart(char ch) {
            return ch == '_' || Character.isLetterOrDigit(ch);
        }

        private static boolean isSimpleBracketIdentifierPart(char ch) {
            return ch == '_' || ch == ' ' || Character.isLetterOrDigit(ch);
        }
    }
}
