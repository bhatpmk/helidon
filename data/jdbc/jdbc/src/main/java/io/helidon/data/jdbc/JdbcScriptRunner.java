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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import io.helidon.data.DataException;

import static java.util.Objects.requireNonNull;

final class JdbcScriptRunner {

    private JdbcScriptRunner() {
        throw new UnsupportedOperationException("No instances of JdbcScriptRunner are allowed");
    }

    static void run(DataSource dataSource, Path script) {
        requireNonNull(dataSource, "dataSource");
        requireNonNull(script, "script");

        String scriptContent = readScript(script);
        List<String> statements = statements(scriptContent);
        if (statements.isEmpty()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new DataException("JDBC script execution failed: " + script, e);
        }
    }

    private static String readScript(Path script) {
        String resourceName = normalize(script);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = JdbcScriptRunner.class.getClassLoader();
        }

        try (InputStream inputStream = classLoader.getResourceAsStream(resourceName)) {
            if (inputStream != null) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new DataException("JDBC script read failed: " + script, e);
        }

        if (Files.exists(script)) {
            try {
                return Files.readString(script, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DataException("JDBC script read failed: " + script, e);
            }
        }

        throw new DataException("JDBC script is not available on the classpath or file system: " + script);
    }

    private static String normalize(Path script) {
        String name = script.toString().replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    private static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }

            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    current.append(' ');
                    i++;
                }
                continue;
            }

            if (!singleQuoted && !doubleQuoted && !backtickQuoted) {
                if (c == '-' && next == '-') {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
                if (c == ';') {
                    add(result, current);
                    current.setLength(0);
                    continue;
                }
            }

            current.append(c);

            if (!doubleQuoted && !backtickQuoted && c == '\'') {
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    singleQuoted = !singleQuoted;
                }
            } else if (!singleQuoted && !backtickQuoted && c == '"') {
                if (doubleQuoted && next == '"') {
                    current.append(next);
                    i++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
            } else if (!singleQuoted && !doubleQuoted && c == '`') {
                backtickQuoted = !backtickQuoted;
            }
        }

        add(result, current);
        return List.copyOf(result);
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}
