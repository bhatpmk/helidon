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
package io.helidon.data.jdbc.executor;

import java.sql.SQLException;

import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;

/**
 * An interface whose implementations install arguments on a {@link JdbcPreparedStatementBindingView}.
 *
 * @param <A> the arguments type
 */
public interface JdbcPreparedStatementBinder<A> {

    /**
     * Binds the supplied {@code args} to the supplied {@link JdbcPreparedStatementBindingView}.
     *
     * @param psView a non-{@code null} {@link JdbcPreparedStatementBindingView}
     * @param args the arguments
     * @exception SQLException if a database error occurs
     */
    void bind(JdbcPreparedStatementBindingView psView, A args) throws SQLException;

}
