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

import java.util.Optional;

/**
 * Event containing a detached set of rows.
 * <p>
 * Rows can come from an ordinary query result set, a direct result set returned by a callable statement, an OUT cursor,
 * or a row-shaped view of a provider-specific result. The {@link RowRole role} tells reducers why the rows exist, while
 * the optional {@code name} identifies named row results such as OUT cursors.
 *
 * @param step owning transcript step
 * @param ordinal event order within the step
 * @param role source and intended meaning of the row set
 * @param name optional row result name, used for named cursors or named synthetic row sets
 * @param rowSet detached rows and shared column layout
 */
record RowsEvent(StepRef step, int ordinal, RowRole role, Optional<String> name, RowSet rowSet) implements JdbcEvent {

    RowsEvent {
        name = name == null ? Optional.empty() : name;
    }

    /**
     * Describes why a row set was captured.
     */
    enum RowRole {
        /**
         * Rows returned directly from statement execution, normally from {@code executeQuery()} or
         * {@code Statement#getResultSet()}.
         */
        DIRECT_RESULT_SET,

        /**
         * Rows returned by SQL that combines update semantics with returned row data, for example database-specific
         * returning clauses.
         */
        RETURNING_RESULT_SET,

        /**
         * Rows read from a callable statement OUT cursor.
         */
        OUT_CURSOR,

        /**
         * Row view over generated-key data when generated keys need to be treated as an ordinary row result.
         */
        GENERATED_KEYS_VIEW
    }
}
