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
import java.util.Optional;

import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;

final class JdbcOperationsImpl implements JdbcOperations {

    private final JdbcKernel kernel;

    JdbcOperationsImpl(DataSource dataSource) {
        this.kernel = JdbcKernel.create(requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public long update(JdbcStatementPlan plan, JdbcBinder binder) {
        return kernel.update(plan, binder);
    }

    @Override
    public <T> List<T> list(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return kernel.list(plan, binder, mapper);
    }

    @Override
    public <T> List<T> listReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return kernel.listReduced(plan, binder, reducer);
    }

    @Override
    public <T> Optional<T> optional(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return kernel.optional(plan, binder, mapper);
    }

    @Override
    public <T> Optional<T> optionalReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return kernel.optionalReduced(plan, binder, reducer);
    }

    @Override
    public <T> T one(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        return kernel.one(plan, binder, mapper);
    }

    @Override
    public <T> T oneReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        return kernel.oneReduced(plan, binder, reducer);
    }

    @Override
    public <T> Optional<T> generatedKey(JdbcStatementPlan plan,
                                        JdbcBinder binder,
                                        JdbcRowMapper<? extends T> mapper) {
        return kernel.generatedKey(plan, binder, mapper);
    }
}
