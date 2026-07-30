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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Package-private mapped cardinality and traversal stage.
 *
 * <p>The stage contains no JDBC resources. It keeps the mapper and preparation
 * plan next to the owning statement so every terminal shares the statement's
 * single-use guard and delegates to the same runner.</p>
 *
 * @param <T> mapped value type
 */
final class JdbcRows<T> implements JdbcClient.Rows<T> {
    /** Statement stage that owns the terminal guard and operation state. */
    private final JdbcStatement statement;
    /** Mapper applied to each physical row. */
    private final JdbcClient.RowMapper<T> mapper;
    /** Query or generated-key preparation contract. */
    private final JdbcPreparationPlan plan;

    /**
     * Creates a mapped stage without acquiring JDBC resources.
     *
     * @param statement owning statement stage
     * @param mapper row mapper
     * @param plan preparation plan
     */
    JdbcRows(JdbcStatement statement, JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        this.statement = statement;
        this.mapper = mapper;
        this.plan = plan;
    }

    /**
     * Delegates exactly-one cardinality to the owning statement.
     *
     * @return one mapped value
     */
    @Override
    public T one() {
        return statement.one(mapper, plan);
    }

    /**
     * Delegates zero-or-one cardinality to the owning statement.
     *
     * @return optional mapped value
     */
    @Override
    public Optional<T> optional() {
        return statement.optional(mapper, plan);
    }

    /**
     * Delegates materializing list execution to the owning statement.
     *
     * @return mapped values in encounter order
     */
    @Override
    public List<T> list() {
        return statement.list(mapper, plan);
    }

    /**
     * Delegates callback-scoped pull traversal while retaining resource ownership in the runner.
     *
     * @param action callback receiving the provider-owned iterable facade
     */
    @Override
    public void withRows(Consumer<? super Iterable<T>> action) {
        statement.withRows(mapper, plan, Objects.requireNonNull(action, "Row action must not be null"));
    }

    /**
     * Delegates complete push traversal.
     *
     * @param action callback invoked once per mapped row
     */
    @Override
    public void forEach(Consumer<? super T> action) {
        statement.forEach(mapper, plan, Objects.requireNonNull(action, "Row action must not be null"));
    }

    /**
     * Delegates predicate-controlled push traversal.
     *
     * @param action continuation predicate
     * @return true only after normal exhaustion
     */
    @Override
    public boolean forEachWhile(Predicate<? super T> action) {
        return statement.forEachWhile(mapper,
                                      plan,
                                      Objects.requireNonNull(action, "Row continuation predicate must not be null"));
    }
}
