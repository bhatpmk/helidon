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
import java.util.Locale;
import java.util.Set;

/**
 * Extracts explicit projection aliases without attempting to parse a complete SELECT grammar.
 */
final class JdbcProjectionAliasLexer {
    private static final Set<String> PROJECTION_TERMINATORS = Set.of("except",
                                                                      "fetch",
                                                                      "for",
                                                                      "from",
                                                                      "group",
                                                                      "having",
                                                                      "intersect",
                                                                      "into",
                                                                      "limit",
                                                                      "minus",
                                                                      "offset",
                                                                      "order",
                                                                      "qualify",
                                                                      "union");

    private JdbcProjectionAliasLexer() {
    }

    static List<String> aliases(String sql) {
        int projectionStart = projectionStart(sql);
        if (projectionStart < 0) {
            return List.of();
        }

        List<String> aliases = new ArrayList<>();
        int index = projectionStart;
        int length = sql.length();
        int depth = 0;
        while (index < length) {
            int protectedEnd = protectedRegionEnd(sql, index);
            if (protectedEnd >= 0) {
                index = protectedEnd;
                continue;
            }
            char current = sql.charAt(index);
            if (current == '(') {
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int end = index + 1;
                while (end < length && Character.isJavaIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String token = sql.substring(index, end).toLowerCase(Locale.ROOT);
                if (depth == 0 && PROJECTION_TERMINATORS.contains(token)) {
                    return List.copyOf(aliases);
                }
                if (depth == 0 && "as".equals(token)) {
                    Alias alias = readAlias(sql, end);
                    if (alias != null) {
                        aliases.add(alias.value());
                        index = alias.end();
                        continue;
                    }
                }
                index = end;
            } else {
                index++;
            }
        }
        return List.copyOf(aliases);
    }

    /**
     * Finds the outer SELECT without parsing database SQL. Parenthesized CTE and subquery SELECT tokens are skipped so
     * their projection aliases do not leak into the repository method's result shape.
     */
    private static int projectionStart(String sql) {
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            int protectedEnd = protectedRegionEnd(sql, index);
            if (protectedEnd >= 0) {
                index = protectedEnd;
                continue;
            }
            char current = sql.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (Character.isJavaIdentifierStart(current)) {
                int end = index + 1;
                while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                if (depth == 0 && "select".equalsIgnoreCase(sql.substring(index, end))) {
                    return end;
                }
                index = end;
            } else {
                index++;
            }
        }
        return -1;
    }

    private static Alias readAlias(String sql, int start) {
        int index = skipTrivia(sql, start);
        if (index >= sql.length()) {
            return null;
        }
        char delimiter = sql.charAt(index);
        if (delimiter == '"' || delimiter == '`' || delimiter == '[') {
            char closing = delimiter == '[' ? ']' : delimiter;
            StringBuilder value = new StringBuilder();
            int end = index + 1;
            while (end < sql.length()) {
                char current = sql.charAt(end++);
                if (current != closing) {
                    value.append(current);
                } else if (end < sql.length() && sql.charAt(end) == closing) {
                    value.append(closing);
                    end++;
                } else {
                    return new Alias(value.toString(), end);
                }
            }
            throw new IllegalArgumentException("Unterminated quoted projection alias near offset " + index);
        }
        if (!Character.isJavaIdentifierStart(delimiter)) {
            return null;
        }
        int end = index + 1;
        while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) {
            end++;
        }
        return new Alias(sql.substring(index, end), end);
    }

    private static int skipTrivia(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            if (Character.isWhitespace(sql.charAt(index))) {
                index++;
            } else if (sql.charAt(index) == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2);
            } else if (sql.charAt(index) == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index + 2);
            } else {
                return index;
            }
        }
        return index;
    }

    private static int protectedRegionEnd(String sql, int index) {
        char current = sql.charAt(index);
        if (current == '\'' || current == '"' || current == '`') {
            return skipQuoted(sql, index, current);
        }
        if (current == '[') {
            return skipBracketIdentifier(sql, index);
        }
        if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
            return skipLineComment(sql, index + 2);
        }
        if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
            return skipBlockComment(sql, index + 2);
        }
        if ((current == 'q' || current == 'Q')
                && index + 2 < sql.length()
                && sql.charAt(index + 1) == '\'') {
            return skipOracleQuoted(sql, index);
        }
        return current == '$' ? skipDollarQuoted(sql, index) : -1;
    }

    private static int skipQuoted(String sql, int start, char delimiter) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == delimiter) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == delimiter) {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        throw new IllegalArgumentException("Unterminated quoted SQL region near offset " + start);
    }

    private static int skipBracketIdentifier(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == ']') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == ']') {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        throw new IllegalArgumentException("Unterminated bracket-quoted identifier near offset " + start);
    }

    private static int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String sql, int index) {
        int depth = 1;
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                index += 2;
                if (--depth == 0) {
                    return index;
                }
            } else {
                index++;
            }
        }
        throw new IllegalArgumentException("Unterminated block comment near offset " + (index - 2));
    }

    private static int skipOracleQuoted(String sql, int start) {
        char opening = sql.charAt(start + 2);
        char closing = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        int index = start + 3;
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == closing && sql.charAt(index + 1) == '\'') {
                return index + 2;
            }
            index++;
        }
        throw new IllegalArgumentException("Unterminated Oracle quoted string near offset " + start);
    }

    private static int skipDollarQuoted(String sql, int start) {
        int delimiterEnd = start + 1;
        while (delimiterEnd < sql.length() && sql.charAt(delimiterEnd) != '$') {
            if (!Character.isJavaIdentifierPart(sql.charAt(delimiterEnd))) {
                return -1;
            }
            delimiterEnd++;
        }
        if (delimiterEnd >= sql.length()) {
            return -1;
        }
        String delimiter = sql.substring(start, delimiterEnd + 1);
        int contentEnd = sql.indexOf(delimiter, delimiterEnd + 1);
        if (contentEnd < 0) {
            throw new IllegalArgumentException("Unterminated PostgreSQL dollar-quoted string near offset " + start);
        }
        return contentEnd + delimiter.length();
    }

    private record Alias(String value, int end) {
    }
}
