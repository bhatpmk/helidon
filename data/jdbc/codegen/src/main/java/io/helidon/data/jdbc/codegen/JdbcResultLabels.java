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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative result label discovery for JDBC select statements.
 * <p>
 * This helper is intentionally not a general SQL parser. It extracts labels from
 * simple top-level {@code SELECT ... FROM ...} statements so mapper and reducer
 * generation can report useful build-time errors when a declared source label is
 * clearly missing. When a statement is too complex to analyze safely, the result
 * is marked incomplete and callers must not treat the label set as exhaustive.
 */
final class JdbcResultLabels {

    /**
     * Unknown label set used when the SQL shape is outside the conservative parser.
     */
    private static final JdbcResultLabels UNKNOWN = new JdbcResultLabels(Set.of(), false);

    private final Set<String> labels;
    private final boolean complete;

    private JdbcResultLabels(Set<String> labels, boolean complete) {
        this.labels = Collections.unmodifiableSet(new LinkedHashSet<>(labels));
        this.complete = complete;
    }

    static JdbcResultLabels parse(String statement) {
        // Only top-level SELECT and FROM keywords are considered; nested queries are left untouched.
        int select = topLevelKeyword(statement, "select", 0);
        if (select < 0) {
            return UNKNOWN;
        }
        int from = topLevelKeyword(statement, "from", select + "select".length());
        String selectList = from < 0
                ? statement.substring(select + "select".length())
                : statement.substring(select + "select".length(), from);
        return parseSelectList(selectList);
    }

    boolean complete() {
        return complete;
    }

    boolean contains(String label) {
        return labels.contains(label);
    }

    Set<String> labels() {
        return labels;
    }

    private static JdbcResultLabels parseSelectList(String selectList) {
        List<String> items = splitSelectList(selectList);
        if (items.isEmpty()) {
            return UNKNOWN;
        }

        Set<String> labels = new LinkedHashSet<>();
        boolean complete = true;
        for (String item : items) {
            String label = label(item);
            if (label == null) {
                // Preserve discovered labels, but mark the result incomplete if any item is ambiguous.
                complete = false;
            } else {
                labels.add(label);
            }
        }
        return new JdbcResultLabels(labels, complete);
    }

    private static List<String> splitSelectList(String value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        // Split on commas only at the top SQL level so functions and quoted strings stay intact.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (singleQuoted) {
                if (c == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (c == '"') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        doubleQuoted = false;
                    }
                }
                continue;
            }
            switch (c) {
            case '\'' -> singleQuoted = true;
            case '"' -> doubleQuoted = true;
            case '(' -> depth++;
            case ')' -> {
                if (depth > 0) {
                    depth--;
                }
            }
            case ',' -> {
                if (depth == 0) {
                    result.add(value.substring(start, i).strip());
                    start = i + 1;
                }
            }
            default -> {
            }
            }
        }
        String last = value.substring(start).strip();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return List.copyOf(result);
    }

    private static String label(String item) {
        List<String> tokens = topLevelTokens(item);
        if (tokens.isEmpty()) {
            return null;
        }
        // Accept either "expression AS label" or a direct column reference.
        if (tokens.size() >= 3 && "as".equalsIgnoreCase(tokens.get(tokens.size() - 2))) {
            return identifier(tokens.getLast(), false);
        }
        if (tokens.size() == 1) {
            return identifier(tokens.getFirst(), true);
        }
        return null;
    }

    private static List<String> topLevelTokens(String value) {
        List<String> tokens = new ArrayList<>();
        int start = -1;
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (singleQuoted) {
                if (c == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (c == '"') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        doubleQuoted = false;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (start >= 0) {
                    tokens.add(value.substring(start, i));
                    start = -1;
                }
                continue;
            }
            if (start < 0) {
                start = i;
            }
            switch (c) {
            case '\'' -> singleQuoted = true;
            case '"' -> doubleQuoted = true;
            case '(' -> depth++;
            case ')' -> {
                if (depth > 0) {
                    depth--;
                }
            }
            default -> {
            }
            }
        }
        if (start >= 0) {
            tokens.add(value.substring(start));
        }
        return List.copyOf(tokens);
    }

    private static String identifier(String token, boolean allowQualified) {
        String stripped = token.strip();
        if (stripped.isEmpty() || "*".equals(stripped) || stripped.endsWith(".*")) {
            return null;
        }
        if (stripped.startsWith("\"")) {
            return quotedIdentifier(stripped);
        }
        if (!isIdentifierPath(stripped, allowQualified)) {
            return null;
        }
        int dot = stripped.lastIndexOf('.');
        return dot < 0 ? stripped : stripped.substring(dot + 1);
    }

    private static String quotedIdentifier(String token) {
        if (token.length() < 2 || token.charAt(token.length() - 1) != '"') {
            return null;
        }
        StringBuilder builder = new StringBuilder(token.length() - 2);
        for (int i = 1; i < token.length() - 1; i++) {
            char c = token.charAt(i);
            if (c == '"' && i + 1 < token.length() - 1 && token.charAt(i + 1) == '"') {
                builder.append('"');
                i++;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static boolean isIdentifierPath(String token, boolean allowQualified) {
        boolean segmentStart = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.') {
                if (!allowQualified || segmentStart) {
                    return false;
                }
                segmentStart = true;
                continue;
            }
            if (segmentStart) {
                if (!isIdentifierStart(c)) {
                    return false;
                }
                segmentStart = false;
            } else if (!isIdentifierPart(c)) {
                return false;
            }
        }
        return !segmentStart;
    }

    private static int topLevelKeyword(String statement, String keyword, int start) {
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        String lower = keyword.toLowerCase(Locale.ROOT);
        for (int i = start; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (singleQuoted) {
                if (c == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (c == '"') {
                    if (i + 1 < statement.length() && statement.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        doubleQuoted = false;
                    }
                }
                continue;
            }
            switch (c) {
            case '\'' -> singleQuoted = true;
            case '"' -> doubleQuoted = true;
            case '(' -> depth++;
            case ')' -> {
                if (depth > 0) {
                    depth--;
                }
            }
            default -> {
                if (depth == 0 && startsWithKeyword(statement, i, lower)) {
                    return i;
                }
            }
            }
        }
        return -1;
    }

    private static boolean startsWithKeyword(String statement, int index, String keyword) {
        int end = index + keyword.length();
        if (end > statement.length() || !statement.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        boolean before = index == 0 || !isIdentifierPart(statement.charAt(index - 1));
        boolean after = end == statement.length() || !isIdentifierPart(statement.charAt(end));
        return before && after;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
