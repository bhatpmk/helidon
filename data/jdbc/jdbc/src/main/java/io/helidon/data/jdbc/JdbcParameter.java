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
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable JDBC statement parameter used by generated repository code and imperative applications.
 * <p>
 * A parameter is named when {@link #name()} is non-empty and positional otherwise. The value may be {@code null}; callers
 * should provide an explicit SQL type with {@link #withSqlType(int)} when portable SQL {@code NULL} binding is required.
 * Optional scale and database type-name metadata select the corresponding JDBC typed binding overloads without exposing
 * {@link java.sql.PreparedStatement} to application code.
 */
public final class JdbcParameter {
    private static final int UNSPECIFIED = -1;

    private final String name;
    private final Object value;
    private final Integer sqlType;
    private final int scale;
    private final String typeName;

    /**
     * Create a named or positional parameter with driver-inferred SQL type.
     *
     * @param name  parameter name, or an empty string for a positional parameter
     * @param value parameter value
     */
    public JdbcParameter(String name, Object value) {
        this(name, value, null, UNSPECIFIED, "");
    }

    private JdbcParameter(String name, Object value, Integer sqlType, int scale, String typeName) {
        this.name = Objects.requireNonNull(name, "Parameter name must not be null");
        this.value = value;
        this.sqlType = sqlType;
        this.scale = scale;
        this.typeName = Objects.requireNonNull(typeName, "Database type name must not be null");
        if (scale < UNSPECIFIED) {
            throw new IllegalArgumentException("Parameter scale or length must be non-negative or -1");
        }
        if ((scale != UNSPECIFIED || !typeName.isBlank()) && sqlType == null) {
            throw new IllegalArgumentException("Parameter scale and database type name require an explicit SQL type");
        }
    }

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

    /**
     * Return this parameter's bind-marker name, or an empty string for a positional parameter.
     *
     * @return parameter name
     */
    public String name() {
        return name;
    }

    /**
     * Return the value to bind.
     *
     * @return parameter value, possibly {@code null}
     */
    public Object value() {
        return value;
    }

    /**
     * Return a copy with an explicit JDBC SQL type.
     *
     * @param sqlType SQL type from {@link java.sql.Types}
     * @return typed parameter
     */
    public JdbcParameter withSqlType(int sqlType) {
        return new JdbcParameter(name, value, sqlType, scale, typeName);
    }

    /**
     * Return a copy with scale or length metadata for JDBC typed object binding.
     * <p>
     * An explicit SQL type must be configured first.
     *
     * @param scale scale or length, at least zero
     * @return parameter with scale or length
     */
    public JdbcParameter withScale(int scale) {
        return new JdbcParameter(name, value, sqlType, scale, typeName);
    }

    /**
     * Return a copy with the database type name used when binding a typed SQL {@code NULL}.
     * <p>
     * An explicit SQL type must be configured first.
     *
     * @param typeName database type name
     * @return parameter with database type name
     */
    public JdbcParameter withTypeName(String typeName) {
        Objects.requireNonNull(typeName, "Database type name must not be null");
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("Database type name must not be blank");
        }
        return new JdbcParameter(name, value, sqlType, scale, typeName);
    }

    OptionalInt sqlType() {
        return sqlType == null ? OptionalInt.empty() : OptionalInt.of(sqlType);
    }

    OptionalInt scale() {
        return scale == UNSPECIFIED ? OptionalInt.empty() : OptionalInt.of(scale);
    }

    Optional<String> typeName() {
        return typeName.isBlank() ? Optional.empty() : Optional.of(typeName);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof JdbcParameter other)) {
            return false;
        }
        return scale == other.scale
                && name.equals(other.name)
                && Objects.equals(value, other.value)
                && Objects.equals(sqlType, other.sqlType)
                && typeName.equals(other.typeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, sqlType, scale, typeName);
    }

    /**
     * Return a structural description that deliberately omits the parameter value.
     *
     * @return non-sensitive parameter description
     */
    @Override
    public String toString() {
        return "JdbcParameter{name='" + name + "', typed=" + (sqlType != null) + '}';
    }
}
