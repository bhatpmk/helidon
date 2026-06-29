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

import java.util.Objects;

/**
 * JDBC statement parameter used by generated repository code.
 *
 * @param name  parameter name, or an empty string for positional parameters
 * @param value parameter value
 */
public record JdbcParameter(String name, Object value) {

    /**
     * Create a named parameter.
     *
     * @param name  parameter name
     * @param value parameter value
     * @return new parameter
     */
    public static JdbcParameter create(String name, Object value) {
        return new JdbcParameter(Objects.requireNonNull(name, "Parameter name must not be null"), value);
    }

    /**
     * Create a positional parameter.
     *
     * @param value parameter value
     * @return new parameter
     */
    public static JdbcParameter create(Object value) {
        return new JdbcParameter("", value);
    }
}
