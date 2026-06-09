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

/**
 * Converts named SQL parameters to JDBC positional parameters.
 * <p>
 * Generated repositories can use named parameters such as {@code :name}, while JDBC requires positional
 * bind markers. This parser turns named parameters into {@code ?} markers and records the names in bind order.
 * It skips single-quoted strings, double-quoted identifiers, line comments, and block comments so SQL text that
 * only looks like a parameter is preserved.
 * </p>
 * <p>
 * This is intentionally compact for the POC. Production code should either share the mature DbClient parser or
 * promote this logic to a tested statement-plan component.
 * </p>
 */
record NamedSql(String jdbcSql, List<String> parameterNames) {

    static NamedSql parse(String sql) {
        StringBuilder jdbcSql = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();

        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (lineComment) {
                jdbcSql.append(ch);
                if (ch == '\n' || ch == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                jdbcSql.append(ch);
                if (ch == '*' && next == '/') {
                    jdbcSql.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (singleQuote) {
                jdbcSql.append(ch);
                if (ch == '\'' && next == '\'') {
                    jdbcSql.append(next);
                    i++;
                } else if (ch == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                jdbcSql.append(ch);
                if (ch == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (ch == '-' && next == '-') {
                jdbcSql.append(ch).append(next);
                i++;
                lineComment = true;
                continue;
            }
            if (ch == '/' && next == '*') {
                jdbcSql.append(ch).append(next);
                i++;
                blockComment = true;
                continue;
            }
            if (ch == '\'') {
                jdbcSql.append(ch);
                singleQuote = true;
                continue;
            }
            if (ch == '"') {
                jdbcSql.append(ch);
                doubleQuote = true;
                continue;
            }
            if (ch == ':' && Character.isJavaIdentifierStart(next)) {
                int start = i + 1;
                int end = start + 1;
                while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                names.add(sql.substring(start, end));
                jdbcSql.append('?');
                i = end - 1;
                continue;
            }
            jdbcSql.append(ch);
        }
        return new NamedSql(jdbcSql.toString(), List.copyOf(names));
    }
}
