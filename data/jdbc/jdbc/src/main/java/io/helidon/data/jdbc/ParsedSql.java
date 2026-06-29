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

import java.util.List;

record ParsedSql(String sql,
                 ParameterMode parameterMode,
                 List<String> parameterNames,
                 List<Integer> parameterIndexes) {

    ParsedSql {
        parameterNames = List.copyOf(parameterNames);
        parameterIndexes = List.copyOf(parameterIndexes);
        switch (parameterMode) {
        case NONE:
            if (!parameterNames.isEmpty() || !parameterIndexes.isEmpty()) {
                throw new IllegalArgumentException("No-parameter SQL must not contain parameter metadata");
            }
            break;
        case NAMED:
            if (parameterNames.isEmpty() || !parameterIndexes.isEmpty()) {
                throw new IllegalArgumentException("Named SQL parameters require names only");
            }
            break;
        case POSITIONAL, ORDINAL:
            if (!parameterNames.isEmpty() || parameterIndexes.isEmpty()) {
                throw new IllegalArgumentException("Positional SQL parameters require indexes only");
            }
            break;
        default:
            throw new IllegalArgumentException("Unsupported SQL parameter mode " + parameterMode);
        }
    }

    static ParsedSql none(String sql) {
        return new ParsedSql(sql, ParameterMode.NONE, List.of(), List.of());
    }

    static ParsedSql named(String sql, List<String> parameterNames) {
        return new ParsedSql(sql, ParameterMode.NAMED, parameterNames, List.of());
    }

    static ParsedSql positional(String sql, int parameterCount) {
        return new ParsedSql(sql, ParameterMode.POSITIONAL, List.of(), indexes(parameterCount));
    }

    static ParsedSql ordinal(String sql, List<Integer> parameterIndexes) {
        return new ParsedSql(sql, ParameterMode.ORDINAL, List.of(), parameterIndexes);
    }

    private static List<Integer> indexes(int parameterCount) {
        return java.util.stream.IntStream.rangeClosed(1, parameterCount)
                .boxed()
                .toList();
    }

    enum ParameterMode {
        NONE,
        NAMED,
        POSITIONAL,
        ORDINAL
    }
}
