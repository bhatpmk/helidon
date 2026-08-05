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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import io.helidon.data.DataException;

/**
 * Very small classpath SQL script runner for the JDBC POC.
 * <p>
 * This class exists only so the JDBC sample can initialize schema and data without JPA schema generation.
 * It is not a general SQL migration engine. Production support should define idempotency, statement splitting,
 * error handling, transaction behavior, ordering, and database-specific script rules before making this feature
 * part of the supported provider contract.
 * </p>
 */
final class JdbcScriptRunner {

    private JdbcScriptRunner() {
    }

    static void run(DataSource dataSource, Path script) {
        String scriptName = script.toString();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = JdbcScriptRunner.class.getClassLoader();
        }

        try (InputStream stream = classLoader.getResourceAsStream(scriptName)) {
            if (stream == null) {
                throw new DataException("JDBC init script was not found on the classpath: " + scriptName);
            }
            execute(dataSource, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DataException("Could not read JDBC init script: " + scriptName, e);
        }
    }

    private static void execute(DataSource dataSource, String script) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                String trimmed = stripLineComments(sql).trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new DataException("JDBC init script execution failed.", e);
        }
    }

    private static String stripLineComments(String sql) {
        StringBuilder builder = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--")) {
                builder.append(line).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }
}
