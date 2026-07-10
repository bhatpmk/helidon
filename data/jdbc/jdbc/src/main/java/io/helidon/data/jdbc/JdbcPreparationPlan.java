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
 * Validated statement-preparation and primary-result contract.
 *
 * <p>The plan keeps preparation decisions separate from SQL text and bind
 * values. A terminal selects one of the shared immutable plans before the
 * runner borrows JDBC resources. This makes generated query, update, and
 * generated-key calls use one execution engine while still selecting the
 * correct JDBC preparation overload.</p>
 *
 * <p>The current contract expects one primary result channel. The explicit
 * result kind and generated-column data leave a narrow extension point for a
 * future callable mode without adding speculative public API now.</p>
 */
final class JdbcPreparationPlan {

    /** Primary JDBC result expected from the operation. */
    enum ResultKind {
        /** A query must produce a result set. */
        QUERY,
        /** A DML operation must produce an update count. */
        UPDATE,
        /** An update must produce a generated-key result set. */
        GENERATED_KEYS
    }

    /** Shared empty generated-column array for query and update plans. */
    private static final String[] NO_COLUMNS = new String[0];
    /** Shared immutable query plan. */
    private static final JdbcPreparationPlan QUERY = new JdbcPreparationPlan(ResultKind.QUERY, NO_COLUMNS);
    /** Shared immutable update plan. */
    private static final JdbcPreparationPlan UPDATE = new JdbcPreparationPlan(ResultKind.UPDATE, NO_COLUMNS);

    /** Primary result kind. */
    private final ResultKind resultKind;
    /** Requested generated-key columns, copied at construction. */
    private final String[] generatedColumns;

    /**
     * Creates a validated plan.
     *
     * @param resultKind primary result kind
     * @param generatedColumns generated-key column names
     */
    private JdbcPreparationPlan(ResultKind resultKind, String[] generatedColumns) {
        this.resultKind = resultKind;
        this.generatedColumns = generatedColumns;
    }

    /**
     * Returns the shared query plan.
     *
     * @return query plan
     */
    static JdbcPreparationPlan query() {
        return QUERY;
    }

    /**
     * Returns the shared update plan.
     *
     * @return update plan
     */
    static JdbcPreparationPlan update() {
        return UPDATE;
    }

    /**
     * Creates a generated-key plan with a defensive column-name copy.
     *
     * <p>An empty array requests the driver's default generated keys. Named
     * columns select JDBC's named generated-column overload. Names are checked
     * case-insensitively because duplicate requests would make result mapping
     * ambiguous.</p>
     *
     * @param columnNames requested generated-key columns
     * @return generated-key plan
     */
    static JdbcPreparationPlan generatedKeys(String[] columnNames) {
        Objects.requireNonNull(columnNames, "Generated column names must not be null");
        // The caller owns the varargs array; the plan must not observe later mutations.
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

    /**
     * Returns the primary result kind.
     *
     * @return result kind
     */
    ResultKind resultKind() {
        return resultKind;
    }

    /**
     * Returns the generated-column names captured by this plan.
     *
     * <p>The array is package-private execution state and must be treated as
     * read-only by callers.</p>
     *
     * @return generated columns, empty for query and update plans
     */
    String[] generatedColumns() {
        return generatedColumns;
    }
}
