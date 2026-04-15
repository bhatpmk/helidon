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
package io.helidon.data.jdbc.function;

import java.sql.SQLException;

/**
 * Represents a supplier of {@code boolean}-valued results that throws {@link SQLException}s.
 *
 * <p>This is the {@code boolean}-producing primitive specialization of
 * {@link JdbcSupplier}.
 *
 * <p>There is no requirement that a new or distinct result be returned each
 * time the supplier is invoked.
 *
 * <p>This is a functional interface whose functional method is
 * {@link #getAsBoolean()}.
 *
 * @see java.util.function.BooleanSupplier
 */
@FunctionalInterface
public interface JdbcBooleanSupplier {

    /**
     * Gets a result.
     *
     * @return a result
     * @throws SQLException if a database error occurs
     */
    boolean getAsBoolean() throws SQLException;

}
