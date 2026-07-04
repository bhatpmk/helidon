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

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validated statement preparation and primary-result contract.
 */
final class JdbcPreparationPlan {

    enum ResultKind {
        QUERY,
        UPDATE,
        GENERATED_KEYS
    }

    private static final String[] NO_COLUMNS = new String[0];
    private static final JdbcPreparationPlan QUERY = new JdbcPreparationPlan(ResultKind.QUERY, NO_COLUMNS);
    private static final JdbcPreparationPlan UPDATE = new JdbcPreparationPlan(ResultKind.UPDATE, NO_COLUMNS);

    private final ResultKind resultKind;
    private final String[] generatedColumns;

    private JdbcPreparationPlan(ResultKind resultKind, String[] generatedColumns) {
        this.resultKind = resultKind;
        this.generatedColumns = generatedColumns;
    }

    static JdbcPreparationPlan query() {
        return QUERY;
    }

    static JdbcPreparationPlan update() {
        return UPDATE;
    }

    static JdbcPreparationPlan generatedKeys(String[] columnNames) {
        Objects.requireNonNull(columnNames, "Generated column names must not be null");
        String[] copy = columnNames.clone();
        Set<String> unique = new HashSet<>(copy.length);
        for (int i = 0; i < copy.length; i++) {
            String name = Objects.requireNonNull(copy[i], "Generated column name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Generated column name must not be blank at index " + i);
            }
            if (!unique.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate generated column name: " + name);
            }
        }
        return new JdbcPreparationPlan(ResultKind.GENERATED_KEYS, copy);
    }

    ResultKind resultKind() {
        return resultKind;
    }

    String[] generatedColumns() {
        return generatedColumns;
    }
}
