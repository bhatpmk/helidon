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

/**
 * Narrow lexical rewriter for declarative {@code :name} markers.
 * <p>
 * This class deliberately recognizes only regions that protect marker-like text. It does not parse or validate database
 * SQL; the JDBC driver and database retain that responsibility.
 */
final class JdbcSqlMarkerLexer {
    private final String source;
    private final int length;
    private final StringBuilder jdbcSql;
    private final List<String> markers = new ArrayList<>();
    private int index;

    private JdbcSqlMarkerLexer(String source) {
        this.source = source;
        this.length = source.length();
        this.jdbcSql = new StringBuilder(source.length());
    }

    static Result parse(String sql) {
        if (sql == null) {
            throw new NullPointerException("SQL must not be null");
        }
        if (sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        JdbcSqlMarkerLexer lexer = new JdbcSqlMarkerLexer(sql);
        lexer.scan();
        return new Result(lexer.jdbcSql.toString(), List.copyOf(lexer.markers));
    }

    private void scan() {
        while (index < length) {
            char current = source.charAt(index);
            if (current == '\'') {
                copyQuoted('\'');
            } else if (current == '"') {
                copyQuoted('"');
            } else if (current == '`') {
                copyQuoted('`');
            } else if (current == '[') {
                copyBracketIdentifier();
            } else if (current == '-' && peek(1) == '-') {
                copyLineComment();
            } else if (current == '/' && peek(1) == '*') {
                copyBlockComment();
            } else if ((current == 'q' || current == 'Q') && peek(1) == '\'' && index + 2 < length) {
                copyOracleQuoted();
            } else if (current == '$' && copyDollarQuoted()) {
                // The helper copied and advanced past the complete region.
            } else if (current == ':') {
                namedMarker();
            } else if (current == '?') {
                positionalMarker();
            } else if (current == '#' && Character.isJavaIdentifierStart(peek(1))) {
                throw malformed("Hash-prefixed parameters are not supported");
            } else if (current == '<' && templateAttribute()) {
                throw malformed("SQL template attributes are not supported");
            } else {
                jdbcSql.append(current);
                index++;
            }
        }
    }

    private void namedMarker() {
        char next = peek(1);
        if (next == ':' || next == '=') {
            jdbcSql.append(':').append(next);
            index += 2;
            return;
        }
        if (!Character.isJavaIdentifierStart(next)) {
            jdbcSql.append(':');
            index++;
            return;
        }
        int start = index + 1;
        int end = start + 1;
        while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) {
            end++;
        }
        if (end < length && source.charAt(end) == '.') {
            throw malformed("Dotted named parameters are not supported");
        }
        markers.add(source.substring(start, end));
        jdbcSql.append('?');
        index = end;
    }

    private void positionalMarker() {
        char next = peek(1);
        if (next == '?' || next == '|' || next == '&') {
            jdbcSql.append('?').append(next);
            index += 2;
            return;
        }
        throw malformed("Declarative SQL accepts named ':name' markers only");
    }

    private void copyQuoted(char delimiter) {
        jdbcSql.append(delimiter);
        index++;
        while (index < length) {
            char current = source.charAt(index);
            jdbcSql.append(current);
            index++;
            if (current == delimiter) {
                if (index < length && source.charAt(index) == delimiter) {
                    jdbcSql.append(delimiter);
                    index++;
                } else {
                    return;
                }
            }
        }
        throw malformed("Unterminated quoted SQL region");
    }

    private void copyBracketIdentifier() {
        jdbcSql.append('[');
        index++;
        while (index < length) {
            char current = source.charAt(index);
            jdbcSql.append(current);
            index++;
            if (current == ']') {
                if (index < length && source.charAt(index) == ']') {
                    jdbcSql.append(']');
                    index++;
                } else {
                    return;
                }
            }
        }
        throw malformed("Unterminated bracket-quoted identifier");
    }

    private void copyLineComment() {
        jdbcSql.append("--");
        index += 2;
        while (index < length) {
            char current = source.charAt(index++);
            jdbcSql.append(current);
            if (current == '\n' || current == '\r') {
                return;
            }
        }
    }

    private void copyBlockComment() {
        jdbcSql.append("/*");
        index += 2;
        int depth = 1;
        while (index < length) {
            if (source.charAt(index) == '/' && peek(1) == '*') {
                jdbcSql.append("/*");
                index += 2;
                depth++;
            } else if (source.charAt(index) == '*' && peek(1) == '/') {
                jdbcSql.append("*/");
                index += 2;
                if (--depth == 0) {
                    return;
                }
            } else {
                jdbcSql.append(source.charAt(index++));
            }
        }
        throw malformed("Unterminated block comment");
    }

    private void copyOracleQuoted() {
        char opening = source.charAt(index + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        jdbcSql.append(source, index, index + 3);
        index += 3;
        while (index + 1 < length) {
            char current = source.charAt(index);
            jdbcSql.append(current);
            index++;
            if (current == closing && source.charAt(index) == '\'') {
                jdbcSql.append('\'');
                index++;
                return;
            }
        }
        throw malformed("Unterminated Oracle quoted string");
    }

    private boolean copyDollarQuoted() {
        int delimiterEnd = index + 1;
        while (delimiterEnd < length && source.charAt(delimiterEnd) != '$') {
            if (!Character.isJavaIdentifierPart(source.charAt(delimiterEnd))) {
                return false;
            }
            delimiterEnd++;
        }
        if (delimiterEnd >= length) {
            return false;
        }
        String delimiter = source.substring(index, delimiterEnd + 1);
        int contentEnd = source.indexOf(delimiter, delimiterEnd + 1);
        if (contentEnd < 0) {
            throw malformed("Unterminated PostgreSQL dollar-quoted string");
        }
        int end = contentEnd + delimiter.length();
        jdbcSql.append(source, index, end);
        index = end;
        return true;
    }

    private boolean templateAttribute() {
        int current = index + 1;
        if (current >= length || !Character.isJavaIdentifierStart(source.charAt(current))) {
            return false;
        }
        current++;
        while (current < length && Character.isJavaIdentifierPart(source.charAt(current))) {
            current++;
        }
        return current < length && source.charAt(current) == '>';
    }

    private char peek(int offset) {
        int target = index + offset;
        return target < length ? source.charAt(target) : '\0';
    }

    private IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message + " near SQL offset " + index);
    }

    record Result(String sql, List<String> markers) {
    }
}
