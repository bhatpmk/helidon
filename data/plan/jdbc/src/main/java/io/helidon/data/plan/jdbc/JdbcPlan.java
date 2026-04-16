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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

import javax.sql.DataSource;

import io.helidon.builder.api.RuntimeType;
import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;
import io.helidon.data.jdbc.JdbcResults;
import io.helidon.data.jdbc.function.JdbcConsumer;
import io.helidon.data.jdbc.function.JdbcSupplier;

import static java.util.Arrays.asList;

/**
 * An executable plan for the presumed execution of a statement using JDBC constructs.
 *
 * @see #execute(JdbcSupplier, JdbcConsumer)
 */
public interface JdbcPlan extends RuntimeType.Api<JdbcPlanConfig> {

    /**
     * Executes this plan, returning a {@link JdbcResults}.
     *
     * @param cs a non-{@code null} {@link JdbcSupplier} of a non-{@code null} {@link Connection}
     * @param argsBinder a non-{@code null} {@link JdbcConsumer} of a non-{@code null} {@link
     * JdbcPreparedStatementBindingView} that is expected to install argument values
     * @return a non-{@code null} {@link JdbcResults}
     * @throws SQLException if a database error occurs
     */
    JdbcResults execute(JdbcSupplier<? extends Connection> cs,
                        JdbcConsumer<? super JdbcPreparedStatementBindingView> argsBinder)
        throws SQLException;

    /*
     * Executes this plan, returning a {@link JdbcResults}.
     *
     * @param cs a non-{@code null} {@link JdbcSupplier} of a non-{@code null} {@link Connection}
     * @return a non-{@code null} {@link JdbcResults}; <strong>callers must {@linkplain JdbcResults#close() close} it
     * when finished</strong>
     * @throws SQLException if a database error occurs
     * @see #execute(JdbcSupplier, JdbcConsumer)
     */
    // default JdbcResults execute(JdbcSupplier<? extends Connection> cs) throws SQLException {
    //     return this.execute(cs, JdbcPlanImpl::doNothing);
    // }

    /**
     * Executes this plan, returning a {@link JdbcResults}.
     *
     * @param cs aa non-{@code null} {@link JdbcSupplier} of a non-{@code null} {@link Connection}
     * @param args arguments
     * @return a non-{@code null} {@link JdbcResults}; <strong>callers must {@linkplain JdbcResults#close() close} it
     * when finished</strong>
     * @throws SQLException if a database error occurs
     * @see #execute(JdbcSupplier, JdbcConsumer)
     */
    default JdbcResults execute(JdbcSupplier<? extends Connection> cs, Object... args) throws SQLException {
        if (args == null || args.length == 0) {
            return this.execute(cs, JdbcPlanImpl::doNothing);
        }
        GenericBinder b = new GenericBinder();
        return this.execute(cs, psView -> b.bind(psView, asList(args)));
    }

    /**
     * Returns a builder for this interface.
     *
     * @return a non-{@code null} {@link JdbcPlanConfig.Builder}
     */
    static JdbcPlanConfig.Builder builder() {
        return JdbcPlanConfig.builder();
    }

    /**
     * Returns a new {@link JdbcPlan} implementation.
     *
     * @param prototype the prototype
     * @return a new {@link JdbcPlan}
     * @throws NullPointerException if {@code prototype} is {@code null}
     * @throws IllegalArgumentException if the prototype is somehow badly assembled
     */
    static JdbcPlan create(JdbcPlanConfig prototype) {
        return new JdbcPlanImpl(prototype);
    }

    /**
     * Customizes the {@link JdbcPlanConfig.Builder} returned by the {@link #builder()} method, and uses it to
     * {@linkplain JdbcPlanConfig.Builder#build() build} a new {@link JdbcPlan}, which is then returned.
     *
     * @param builderCustomizer a non-{@code null} {@link Consumer} of {@link JdbcPlanConfig.Builder} instances that is
     * intended to customize them
     * @return a new {@link JdbcPlan}
     * @throws NullPointerException if {@code builderCustomizer} is {@code null}
     * @see #builder()
     */
    static JdbcPlan create(Consumer<JdbcPlanConfig.Builder> builderCustomizer) {
        return builder().update(builderCustomizer).build();
    }

    /**
     * A convenience method that arranges for the supplied {@code statement}, requiring no arguments, to be {@linkplain
     * #execute(JdbcSupplier, Object...)} executed.
     *
     * <p>This method:</p>
     *
     * <ol>
     * <li>Invokes the {@link #builder()} method</li>
     * <li>Invokes its {@link JdbcPlanConfig.Builder#statement(String)} method with the supplied {@code statement}</li>
     * <li>Invokes the {@link JdbcPlanConfig.Builder#build()} method to build a minimally-configured {@link JdbcPlan}</li>
     * <li>Invokes the {@link #execute(JdbcSupplier, Object...)} method with the supplied {@link JdbcSupplier}</li>
     * <li>Returns the results</li>
     * </ol>
     *
     * @param cs a non-{@code null} {@link JdbcSupplier} of {@link Connection} instances
     * @param statement a non-{@code null} SQL statement
     * @return a non-{@code null} {@link JdbcResults}; <strong>callers must {@linkplain JdbcResults#close() close} it
     * when finished</strong>
     * @throws SQLException if a database error occurs
     * @see #builder()
     * @see JdbcPlanConfig.Builder#statement(String)
     * @see JdbcPlanConfig.Builder#build()
     * @see #execute(JdbcSupplier, Object...)
     * @see JdbcResults
     */
    static JdbcResults execute(JdbcSupplier<? extends Connection> cs, String statement) throws SQLException {
        return builder().statement(statement).build().execute(cs);
    }

    /**
     * A convenience method that arranges for the supplied {@code statement}, requiring no arguments, to be {@linkplain
     * #execute(JdbcSupplier, Object...)} executed.
     *
     * <p>This method:</p>
     *
     * <ol>
     * <li>Invokes the {@link #builder()} method</li>
     * <li>Invokes its {@link JdbcPlanConfig.Builder#statement(String)} method with the supplied {@code statement}</li>
     * <li>Invokes the {@link JdbcPlanConfig.Builder#build()} method to build a minimally-configured {@link JdbcPlan}</li>
     * <li>Invokes the {@link #execute(JdbcSupplier, Object...)} method with {@link DataSource#getConnection()
     * ds::getConnection}</li>
     * <li>Returns the results</li>
     * </ol>
     *
     * @param ds a non-{@code null} {@link DataSource}
     * @param statement a non-{@code null} SQL statement
     * @return a non-{@code null} {@link JdbcResults}; <strong>callers must {@linkplain JdbcResults#close() close} it
     * when finished</strong>
     * @throws SQLException if a database error occurs
     * @see #builder()
     * @see JdbcPlanConfig.Builder#statement(String)
     * @see JdbcPlanConfig.Builder#build()
     * @see #execute(JdbcSupplier, Object...)
     * @see JdbcResults
     */
    static JdbcResults execute(DataSource ds, String statement) throws SQLException {
        return builder().statement(statement).build().execute(ds::getConnection);
    }

}
