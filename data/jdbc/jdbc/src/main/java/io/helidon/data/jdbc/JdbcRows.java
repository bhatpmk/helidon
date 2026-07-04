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
 * Mapped cardinality and traversal stage sharing the owning statement's terminal guard.
 *
 * @param <T> mapped value type
 */
final class JdbcRows<T> implements JdbcClient.Rows<T> {
    private final JdbcStatement statement;
    private final JdbcClient.RowMapper<T> mapper;
    private final JdbcPreparationPlan plan;

    JdbcRows(JdbcStatement statement, JdbcClient.RowMapper<T> mapper, JdbcPreparationPlan plan) {
        this.statement = statement;
        this.mapper = mapper;
        this.plan = plan;
    }

    @Override
    public T one() {
        return statement.one(mapper, plan);
    }

    @Override
    public Optional<T> optional() {
        return statement.optional(mapper, plan);
    }

    @Override
    public List<T> list() {
        return statement.list(mapper, plan);
    }

    @Override
    public void withRows(Consumer<? super Iterable<T>> action) {
        statement.withRows(mapper, plan, Objects.requireNonNull(action, "Row action must not be null"));
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        statement.forEach(mapper, plan, Objects.requireNonNull(action, "Row action must not be null"));
    }

    @Override
    public boolean forEachWhile(Predicate<? super T> action) {
        return statement.forEachWhile(mapper,
                                      plan,
                                      Objects.requireNonNull(action, "Row continuation predicate must not be null"));
    }
}
