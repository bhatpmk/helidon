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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.helidon.data.DataException;

/**
 * Loads and lexically splits classpath initialization scripts before delegating execution to {@link JdbcRunner}.
 */
final class JdbcInitScriptRunner {
    private final JdbcRunner runner;
    private final ClassLoader classLoader;

    JdbcInitScriptRunner(JdbcRunner runner) {
        this(runner, contextClassLoader());
    }

    JdbcInitScriptRunner(JdbcRunner runner, ClassLoader classLoader) {
        this.runner = Objects.requireNonNull(runner, "JDBC runner must not be null");
        this.classLoader = Objects.requireNonNull(classLoader, "Class loader must not be null");
    }

    void run(Path script) {
        Objects.requireNonNull(script, "Initialization script path must not be null");
        String resource = script.toString().replace('\\', '/');
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        if (resource.isBlank()) {
            throw new IllegalArgumentException("Initialization script path must not be blank");
        }

        String source;
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new DataException("JDBC initialization script was not found on the classpath: " + resource);
            }
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataException("Could not read JDBC initialization script: " + resource, e);
        }
        runner.executeScript(split(source));
    }

    static List<String> split(String source) {
        Objects.requireNonNull(source, "Initialization script must not be null");
        ScriptLexer lexer = new ScriptLexer(source);
        return lexer.split();
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? JdbcInitScriptRunner.class.getClassLoader() : loader;
    }

    /**
     * Recognizes only statement boundaries and protected lexical regions; database SQL remains opaque.
     */
    private static final class ScriptLexer {
        private final String source;
        private final StringBuilder statement = new StringBuilder();
        private final List<String> statements = new ArrayList<>();
        private int index;
        private boolean hasCode;

        private ScriptLexer(String source) {
            this.source = source;
        }

        private List<String> split() {
            while (index < source.length()) {
                char current = source.charAt(index);
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
                } else if ((current == 'q' || current == 'Q') && peek(1) == '\'' && index + 2 < source.length()) {
                    oracleQuoted();
                } else if (current == '$' && dollarDelimiter()) {
                    throw malformed("Dollar-quoted or procedural script blocks are not supported");
                } else if (current == ';') {
                    finishStatement();
                    index++;
                } else if (Character.isJavaIdentifierStart(current)) {
                    word();
                } else {
                    statement.append(current);
                    if (!Character.isWhitespace(current)) {
                        hasCode = true;
                    }
                    index++;
                }
            }
            finishStatement();
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("JDBC initialization script contains no executable statements");
            }
            return List.copyOf(statements);
        }

        private void word() {
            int start = index++;
            while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
                index++;
            }
            String word = source.substring(start, index);
            if (word.equalsIgnoreCase("begin")) {
                throw malformed("Procedural BEGIN/END script blocks are not supported");
            }
            statement.append(word);
            hasCode = true;
        }

        private void quoted(char delimiter) {
            hasCode = true;
            statement.append(delimiter);
            index++;
            while (index < source.length()) {
                char current = source.charAt(index++);
                statement.append(current);
                if (current == delimiter) {
                    if (index < source.length() && source.charAt(index) == delimiter) {
                        statement.append(delimiter);
                        index++;
                    } else {
                        return;
                    }
                }
            }
            throw malformed("Unterminated quoted region");
        }

        private void bracketIdentifier() {
            hasCode = true;
            statement.append('[');
            index++;
            while (index < source.length()) {
                char current = source.charAt(index++);
                statement.append(current);
                if (current == ']') {
                    if (index < source.length() && source.charAt(index) == ']') {
                        statement.append(']');
                        index++;
                    } else {
                        return;
                    }
                }
            }
            throw malformed("Unterminated bracket-quoted identifier");
        }

        private void lineComment() {
            statement.append("--");
            index += 2;
            while (index < source.length()) {
                char current = source.charAt(index++);
                statement.append(current);
                if (current == '\n' || current == '\r') {
                    return;
                }
            }
        }

        private void blockComment() {
            statement.append("/*");
            index += 2;
            int depth = 1;
            while (index < source.length()) {
                if (source.charAt(index) == '/' && peek(1) == '*') {
                    statement.append("/*");
                    index += 2;
                    depth++;
                } else if (source.charAt(index) == '*' && peek(1) == '/') {
                    statement.append("*/");
                    index += 2;
                    if (--depth == 0) {
                        return;
                    }
                } else {
                    statement.append(source.charAt(index++));
                }
            }
            throw malformed("Unterminated block comment");
        }

        private void oracleQuoted() {
            hasCode = true;
            char opening = source.charAt(index + 2);
            char closing = switch (opening) {
                case '[' -> ']';
                case '(' -> ')';
                case '{' -> '}';
                case '<' -> '>';
                default -> opening;
            };
            statement.append(source, index, index + 3);
            index += 3;
            while (index + 1 < source.length()) {
                char current = source.charAt(index++);
                statement.append(current);
                if (current == closing && source.charAt(index) == '\'') {
                    statement.append('\'');
                    index++;
                    return;
                }
            }
            throw malformed("Unterminated Oracle quoted string");
        }

        private boolean dollarDelimiter() {
            int current = index + 1;
            while (current < source.length() && source.charAt(current) != '$') {
                if (!Character.isJavaIdentifierPart(source.charAt(current))) {
                    return false;
                }
                current++;
            }
            return current < source.length();
        }

        private void finishStatement() {
            if (hasCode) {
                String sql = statement.toString().strip();
                if (!sql.isEmpty()) {
                    statements.add(sql);
                }
            }
            statement.setLength(0);
            hasCode = false;
        }

        private char peek(int offset) {
            int target = index + offset;
            return target < source.length() ? source.charAt(target) : '\0';
        }

        private IllegalArgumentException malformed(String message) {
            return new IllegalArgumentException(message + " near script offset " + index);
        }
    }
}
