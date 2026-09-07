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
import java.util.function.Consumer;

import javax.sql.DataSource;

import io.helidon.builder.api.RuntimeType;
import io.helidon.data.jdbc.JdbcResults;

/**
 * A plan for the execution of JDBC operations and the transformation of their results.
 *
 * @param <T> the transformed results type
 * @see #execute()
 */
public interface JdbcPlan<T> extends RuntimeType.Api<JdbcPlanConfig<T>> {

    /**
     * Executes this plan and returns the results.
     *
     * @return the non-{@code null} results
     * @throws SQLException if a database error occurs
     */
    T execute() throws SQLException;


    /*
     * Helidon Builder-required static methods.
     */


    /**
     * Returns a builder for this interface.
     *
     * @param <T> the results type
     * @return a non-{@code null} {@link JdbcPlanConfig.Builder}
     */
    static <T> JdbcPlanConfig.Builder<T> builder() {
        return JdbcPlanConfig.builder();
    }

    /**
     * Returns a new {@link JdbcPlan} implementation.
     *
     * @param <T> the transformed results type
     * @param prototype the prototype
     * @return a new {@link JdbcPlan}
     * @throws NullPointerException if {@code prototype} is {@code null}
     * @throws IllegalArgumentException if the prototype is invalid
     */
    static <T> JdbcPlan<T> create(JdbcPlanConfig<T> prototype) {
        return new JdbcPlanImpl<>(prototype);
    }

    /**
     * Customizes the {@link JdbcPlanConfig.Builder} returned by the {@link #builder()} method, and uses it to
     * {@linkplain JdbcPlanConfig.Builder#build() build} a new {@link JdbcPlan}, which is then returned.
     *
     * @param <T> the results type
     * @param builderCustomizer a non-{@code null} {@link Consumer} of {@link JdbcPlanConfig.Builder} instances that is
     * intended to customize them
     * @return a new {@link JdbcPlan}
     * @throws NullPointerException if {@code builderCustomizer} is {@code null}
     * @see #builder()
     */
    static <T> JdbcPlan<T> create(Consumer<JdbcPlanConfig.Builder<T>> builderCustomizer) {
        return JdbcPlanConfig.<T>builder().update(builderCustomizer).build();
    }


    /*
     * Static convenience methods.
     */


    /**
     * A convenience method that builds a {@link JdbcPlan} from the inputs, executes it, and returns the
     * (identity-transformed) results.
     *
     * @param ds a non-{@code null} {@link DataSource}
     * @param jdbcStatementText a non-{@code null} JDBC statement
     * @param arguments any arguments to be bound
     * @return the non-{@code null} results
     * @throws SQLException if a database error occurs
     */
    static JdbcResults execute(DataSource ds, String jdbcStatementText, Object... arguments) throws SQLException {
        return JdbcPlanConfig.<JdbcResults>builder()
            .addConnectionPlan(ConnectionPlanConfig.builder()
                               .dataSource(ds)
                               .addStatementPlan(StatementPlanConfig.builder()
                                                 .statement(jdbcStatementText)
                                                 .argumentsBinder(ps -> {
                                                         for (int i = 0; i < arguments.length; i++) {
                                                             ps.setObject(i + 1, arguments[i]);
                                                         }
                                                     })
                                                 .build())
                               .build())
            .transformer(jr -> jr)
            .build()
            .execute();
    }

}
