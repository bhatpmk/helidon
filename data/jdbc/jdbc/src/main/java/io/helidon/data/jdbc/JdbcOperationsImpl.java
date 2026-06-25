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

    private static final boolean TRACE = Boolean.getBoolean("helidon.data.jdbc.trace");

    private final JdbcKernel kernel;

    JdbcOperationsImpl(DataSource dataSource) {
        trace(">>> ENTER JdbcOperationsImpl.<init>(dataSource=" + dataSource + ")");
        this.kernel = JdbcKernel.create(requireNonNull(dataSource, "dataSource"));
        trace("<<< EXIT  JdbcOperationsImpl.<init>()");
    }

    @Override
    public long update(JdbcStatementPlan plan, JdbcBinder binder) {
        trace(">>> ENTER JdbcOperationsImpl.update(plan=" + plan + ", binder=" + binder + ")");
        long result = kernel.update(plan, binder);
        trace("<<< EXIT  JdbcOperationsImpl.update() result=" + result);
        return result;
    }

    @Override
    public <T> List<T> list(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcOperationsImpl.list(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        List<T> result = kernel.list(plan, binder, mapper);
        trace("<<< EXIT  JdbcOperationsImpl.list() resultSize=" + result.size());
        return result;
    }

    @Override
    public <T> List<T> listReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcOperationsImpl.listReduced(plan=" + plan + ", binder=" + binder
                      + ", reducer=" + reducer + ")");
        List<T> result = kernel.listReduced(plan, binder, reducer);
        trace("<<< EXIT  JdbcOperationsImpl.listReduced() resultSize=" + result.size());
        return result;
    }

    @Override
    public <T> Optional<T> optional(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcOperationsImpl.optional(plan=" + plan + ", binder=" + binder
                      + ", mapper=" + mapper + ")");
        Optional<T> result = kernel.optional(plan, binder, mapper);
        trace("<<< EXIT  JdbcOperationsImpl.optional() present=" + result.isPresent());
        return result;
    }

    @Override
    public <T> Optional<T> optionalReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcOperationsImpl.optionalReduced(plan=" + plan + ", binder=" + binder
                      + ", reducer=" + reducer + ")");
        Optional<T> result = kernel.optionalReduced(plan, binder, reducer);
        trace("<<< EXIT  JdbcOperationsImpl.optionalReduced() present=" + result.isPresent());
        return result;
    }

    @Override
    public <T> T one(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcOperationsImpl.one(plan=" + plan + ", binder=" + binder + ", mapper=" + mapper + ")");
        T result = kernel.one(plan, binder, mapper);
        trace("<<< EXIT  JdbcOperationsImpl.one() result=" + result);
        return result;
    }

    @Override
    public <T> T oneReduced(JdbcStatementPlan plan, JdbcBinder binder, JdbcRowReducer<T> reducer) {
        trace(">>> ENTER JdbcOperationsImpl.oneReduced(plan=" + plan + ", binder=" + binder
                      + ", reducer=" + reducer + ")");
        T result = kernel.oneReduced(plan, binder, reducer);
        trace("<<< EXIT  JdbcOperationsImpl.oneReduced() result=" + result);
        return result;
    }

    @Override
    public <T> Optional<T> generatedKey(JdbcStatementPlan plan,
                                        JdbcBinder binder,
                                        JdbcRowMapper<? extends T> mapper) {
        trace(">>> ENTER JdbcOperationsImpl.generatedKey(plan=" + plan + ", binder=" + binder
                      + ", mapper=" + mapper + ")");
        Optional<T> result = kernel.generatedKey(plan, binder, mapper);
        trace("<<< EXIT  JdbcOperationsImpl.generatedKey() present=" + result.isPresent());
        return result;
    }

    private static void trace(String message) {
        if (TRACE) {
            System.out.println("\n----- JDBC TRACE [JdbcOperationsImpl] " + message + "\n");
        }
    }
}
