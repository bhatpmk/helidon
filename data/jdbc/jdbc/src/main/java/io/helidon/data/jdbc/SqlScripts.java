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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.helidon.data.DataException;

final class SqlScripts {

    private SqlScripts() {
    }

    static List<String> statements(String persistenceUnitName, Path script) {
        String content = read(persistenceUnitName, script);
        return split(content);
    }

    private static String read(String persistenceUnitName, Path script) {
        try {
            if (Files.exists(script)) {
                return Files.readString(script);
            }
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = SqlScripts.class.getClassLoader();
            }
            try (InputStream resource = classLoader.getResourceAsStream(script.toString())) {
                if (resource != null) {
                    return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new DataException("Initialization script \"" + script + "\" for JDBC persistence unit \""
                                            + persistenceUnitName + "\" was not found.");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JDBC initialization script " + script, e);
        }
    }

    private static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        State state = State.DEFAULT;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            switch (state) {
            case DEFAULT:
                if (ch == '\'') {
                    state = State.SINGLE_QUOTE;
                    current.append(ch);
                } else if (ch == '-' && next == '-') {
                    state = State.LINE_COMMENT;
                    i++;
                } else if (ch == '/' && next == '*') {
                    state = State.BLOCK_COMMENT;
                    i++;
                } else if (ch == ';') {
                    add(statements, current);
                } else {
                    current.append(ch);
                }
                break;
            case SINGLE_QUOTE:
                current.append(ch);
                if (ch == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (ch == '\'') {
                    state = State.DEFAULT;
                }
                break;
            case LINE_COMMENT:
                if (ch == '\n' || ch == '\r') {
                    state = State.DEFAULT;
                    current.append(ch);
                }
                break;
            case BLOCK_COMMENT:
                if (ch == '*' && next == '/') {
                    i++;
                    state = State.DEFAULT;
                }
                break;
            default:
                throw new IllegalStateException("Unhandled SQL script parser state " + state);
            }
        }
        add(statements, current);
        return List.copyOf(statements);
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private enum State {
        DEFAULT,
        SINGLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
