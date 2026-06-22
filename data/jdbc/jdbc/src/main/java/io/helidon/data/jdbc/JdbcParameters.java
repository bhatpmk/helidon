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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Named SQL parameters passed from generated repository methods to the JDBC executor.
 * <p>
 * Generated code creates this object from repository method arguments. The executor later uses the parsed SQL
 * parameter order to read values from this object and bind them to a {@code PreparedStatement}.
 * </p>
 * <p>
 * The POC keeps this object intentionally small. Production code should add compile-time validation for unused
 * method parameters and SQL parameters missing from the method signature.
 * </p>
 */
public final class JdbcParameters {

    private static final JdbcParameters EMPTY = new JdbcParameters(Map.of());

    private final Map<String, Object> values;

    private JdbcParameters(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Empty parameter set.
     *
     * @return empty parameters
     */
    public static JdbcParameters empty() {
        return EMPTY;
    }

    /**
     * Create parameters from alternating name and value items.
     *
     * @param nameValuePairs alternating parameter name and parameter value entries
     * @return parameters
     */
    public static JdbcParameters of(Object... nameValuePairs) {
        Objects.requireNonNull(nameValuePairs, "Parameter entries must not be null");
        if (nameValuePairs.length == 0) {
            return empty();
        }
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Named JDBC parameters require alternating name and value entries.");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (!(nameValuePairs[i] instanceof String name)) {
                throw new IllegalArgumentException("JDBC parameter name at index " + i + " must be a String.");
            }
            values.put(name, nameValuePairs[i + 1]);
        }
        return new JdbcParameters(values);
    }

    Object value(String name) {
        if (!values.containsKey(name)) {
            throw new IllegalArgumentException("SQL parameter :" + name + " does not have a matching method parameter.");
        }
        return values.get(name);
    }
}
