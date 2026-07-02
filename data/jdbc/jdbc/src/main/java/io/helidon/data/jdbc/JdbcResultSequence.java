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
import java.util.Objects;

import io.helidon.data.DataException;

/**
 * Immutable ordered sequence of direct JDBC results.
 *
 * The explicit list field makes the sequence boundary clear while the model is being evolved.
 */
final class JdbcResultSequence {
    private final List<JdbcDirectResult> items;

    JdbcResultSequence(List<JdbcDirectResult> items) {
        Objects.requireNonNull(items, "Direct results must not be null");
        this.items = List.copyOf(items);
        for (int i = 0; i < this.items.size(); i++) {
            JdbcDirectResult item = Objects.requireNonNull(this.items.get(i), "Direct result must not be null");
            if (item.ordinal() != i) {
                throw new DataException("Direct-result ordinal " + item.ordinal() + " does not match position " + i);
            }
        }
    }

    /**
     * Return direct results in JDBC encounter order.
     *
     * @return immutable direct-result list
     */
    List<JdbcDirectResult> items() {
        return items;
    }

    /**
     * Return the direct result at the requested ordinal.
     *
     * @param ordinal zero-based direct-result ordinal
     * @return selected result
     * @throws DataException if the ordinal is not present
     */
    JdbcDirectResult at(int ordinal) {
        if (ordinal < 0 || ordinal >= items.size()) {
            throw new DataException("JDBC direct-result ordinal " + ordinal
                                            + " is not present; result count is " + items.size());
        }
        return items.get(ordinal);
    }

    /**
     * Return the only direct result.
     *
     * @return only result
     * @throws DataException if there is not exactly one result
     */
    JdbcDirectResult only() {
        if (items.size() != 1) {
            throw new DataException("Expected exactly one direct JDBC result, but found " + items.size());
        }
        return items.getFirst();
    }
}
