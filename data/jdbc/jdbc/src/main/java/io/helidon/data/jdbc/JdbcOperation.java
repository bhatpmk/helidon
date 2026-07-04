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

import java.sql.SQLType;
import java.util.Objects;

/**
 * Immutable execution snapshot created immediately before a terminal operation.
 */
final class JdbcOperation {

    private final String sql;
    private final Bind[] binds;
    private final JdbcExecutionOptions options;
    private final JdbcPreparationPlan preparationPlan;

    JdbcOperation(String sql,
                  Bind[] binds,
                  JdbcExecutionOptions options,
                  JdbcPreparationPlan preparationPlan) {
        this.sql = sql;
        this.binds = binds;
        this.options = options;
        this.preparationPlan = preparationPlan;
    }

    static int parameterCount(String sql) {
        Objects.requireNonNull(sql, "SQL must not be null");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        return MarkerScanner.count(sql);
    }

    String sql() {
        return sql;
    }

    Bind[] binds() {
        return binds;
    }

    JdbcExecutionOptions options() {
        return options;
    }

    JdbcPreparationPlan preparationPlan() {
        return preparationPlan;
    }

    @Override
    public String toString() {
        return "JdbcOperation[" + preparationPlan.resultKind() + ", parameters=" + binds.length + "]";
    }

    static final class Bind {
        private final Object value;
        private final SQLType type;

        Bind(Object value, SQLType type) {
            this.value = value;
            this.type = type;
        }

        Object value() {
            return value;
        }

        SQLType type() {
            return type;
        }

        boolean typed() {
            return type != null;
        }
    }

    /**
     * Counts imperative positional markers without interpreting SQL grammar. Quoted text, comments, and common vendor
     * operators are copied conceptually as opaque regions so their question marks are not mistaken for bind markers.
     */
    private static final class MarkerScanner {
        private final String sql;
        private final int length;
        private int index;
        private int count;

        private MarkerScanner(String sql) {
            this.sql = sql;
            this.length = sql.length();
        }

        static int count(String sql) {
            MarkerScanner scanner = new MarkerScanner(sql);
            scanner.scan();
            return scanner.count;
        }

        private void scan() {
            while (index < length) {
                char current = sql.charAt(index);
                if (current == '\'') {
                    quoted('\'');
                } else if (current == '"') {
                    quoted('"');
                } else if (current == '`') {
                    quoted('`');
                } else if (current == '[') {
                    bracketIdentifier();
                } else if (current == '-' && peek(1) == '-') {
                    lineComment();
                } else if (current == '/' && peek(1) == '*') {
                    blockComment();
                } else if ((current == 'q' || current == 'Q') && peek(1) == '\'' && index + 2 < length) {
                    oracleQuoted();
                } else if (current == '$' && dollarQuoted()) {
                    // The helper advances past the complete dollar-quoted region.
                } else if (current == '?') {
                    positionalMarker();
                } else if (current == ':') {
                    namedMarker();
                } else {
                    index++;
                }
            }
        }

        private void positionalMarker() {
            char next = peek(1);
            if (next == '?' || next == '|' || next == '&') {
                index += 2;
            } else {
                count++;
                index++;
            }
        }

        private void namedMarker() {
            char next = peek(1);
            if (next == ':' || next == '=') {
                index += 2;
                return;
            }
            if (Character.isJavaIdentifierStart(next)) {
                throw new IllegalArgumentException(
                        "Imperative SQL accepts positional '?' markers only; named marker found at offset " + index);
            }
            index++;
        }

        private void quoted(char delimiter) {
            index++;
            while (index < length) {
                if (sql.charAt(index) == delimiter) {
                    if (peek(1) == delimiter) {
                        index += 2;
                    } else {
                        index++;
                        return;
                    }
                } else {
                    index++;
                }
            }
            throw malformed("Unterminated quoted SQL region");
        }

        private void bracketIdentifier() {
            index++;
            while (index < length) {
                if (sql.charAt(index) == ']') {
                    if (peek(1) == ']') {
                        index += 2;
                    } else {
                        index++;
                        return;
                    }
                } else {
                    index++;
                }
            }
            throw malformed("Unterminated bracket-quoted identifier");
        }

        private void lineComment() {
            index += 2;
            while (index < length) {
                char current = sql.charAt(index++);
                if (current == '\n' || current == '\r') {
                    return;
                }
            }
        }

        private void blockComment() {
            index += 2;
            int depth = 1;
            while (index < length) {
                if (sql.charAt(index) == '/' && peek(1) == '*') {
                    depth++;
                    index += 2;
                } else if (sql.charAt(index) == '*' && peek(1) == '/') {
                    depth--;
                    index += 2;
                    if (depth == 0) {
                        return;
                    }
                } else {
                    index++;
                }
            }
            throw malformed("Unterminated block comment");
        }

        private void oracleQuoted() {
            char opening = sql.charAt(index + 2);
            char closing = switch (opening) {
                case '[' -> ']';
                case '(' -> ')';
                case '{' -> '}';
                case '<' -> '>';
                default -> opening;
            };
            index += 3;
            while (index + 1 < length) {
                if (sql.charAt(index) == closing && sql.charAt(index + 1) == '\'') {
                    index += 2;
                    return;
                }
                index++;
            }
            throw malformed("Unterminated Oracle quoted string");
        }

        private boolean dollarQuoted() {
            int delimiterEnd = index + 1;
            while (delimiterEnd < length && sql.charAt(delimiterEnd) != '$') {
                if (!Character.isJavaIdentifierPart(sql.charAt(delimiterEnd))) {
                    return false;
                }
                delimiterEnd++;
            }
            if (delimiterEnd >= length) {
                return false;
            }
            String delimiter = sql.substring(index, delimiterEnd + 1);
            int contentEnd = sql.indexOf(delimiter, delimiterEnd + 1);
            if (contentEnd < 0) {
                throw malformed("Unterminated PostgreSQL dollar-quoted string");
            }
            index = contentEnd + delimiter.length();
            return true;
        }

        private char peek(int offset) {
            int target = index + offset;
            return target < length ? sql.charAt(target) : '\0';
        }

        private IllegalArgumentException malformed(String message) {
            return new IllegalArgumentException(message + " near offset " + index);
        }
    }
}
