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
package io.helidon.data.plan.jdbc;

import java.sql.SQLException;

import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;

// Provisional for now. May go away. This is the kind of generic binder you would have to use at runtime if the
// generator didn't make one. But really you should define your own record.
final class GenericBinder implements JdbcPreparedStatementBinder<Iterable<?>> {

    @Override
    public void bind(JdbcPreparedStatementBindingView psView, Iterable<?> args) throws SQLException {
        int i = 1;
        for (Object arg : args) {
            psView.setObject(i++, arg);
        }
    }

}
