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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.helidon.data.DataException;

/**
 * Callback-scoped row implementation backed by one provider-owned result set.
 */
final class JdbcRow implements JdbcClient.Row {
    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(Boolean.class,
                                                                 Byte.class,
                                                                 Short.class,
                                                                 Integer.class,
                                                                 Long.class,
                                                                 Float.class,
                                                                 Double.class,
                                                                 BigDecimal.class,
                                                                 BigInteger.class,
                                                                 String.class,
                                                                 byte[].class,
                                                                 UUID.class,
                                                                 LocalDate.class,
                                                                 LocalTime.class,
                                                                 LocalDateTime.class,
                                                                 OffsetTime.class,
                                                                 OffsetDateTime.class,
                                                                 Instant.class,
                                                                 Date.class,
                                                                 Time.class,
                                                                 Timestamp.class);
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(boolean.class, Boolean.class,
                                                                             byte.class, Byte.class,
                                                                             short.class, Short.class,
                                                                             int.class, Integer.class,
                                                                             long.class, Long.class,
                                                                             float.class, Float.class,
                                                                             double.class, Double.class);

    private final ResultSet resultSet;
    private final JdbcColumnLayout columns;
    private final JdbcOperation operation;
    private boolean active;

    JdbcRow(ResultSet resultSet, JdbcColumnLayout columns, JdbcOperation operation) {
        this.resultSet = resultSet;
        this.columns = columns;
        this.operation = operation;
    }

    static boolean supportedScalar(Class<?> type) {
        return SUPPORTED_TYPES.contains(normalized(type));
    }

    static Class<?> normalizedScalar(Class<?> type) {
        Class<?> normalized = normalized(type);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported JDBC scalar type: " + type.getTypeName());
        }
        return normalized;
    }

    void activate() {
        active = true;
    }

    void deactivate() {
        active = false;
    }

    @Override
    public <T> Optional<T> optional(int index, Class<T> type) {
        ensureActive();
        validateIndex(index);
        return Optional.ofNullable(read(index, type));
    }

    @Override
    public <T> Optional<T> optional(String label, Class<T> type) {
        ensureActive();
        Objects.requireNonNull(label, "Column label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Column label must not be blank");
        }
        return Optional.ofNullable(read(columns.index(label), type));
    }

    @Override
    public <T> T required(int index, Class<T> type) {
        ensureActive();
        validateIndex(index);
        T value = read(index, type);
        if (value == null) {
            throw new DataException("Required result column " + index + " contains SQL NULL");
        }
        return value;
    }

    @Override
    public <T> T required(String label, Class<T> type) {
        ensureActive();
        Objects.requireNonNull(label, "Column label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Column label must not be blank");
        }
        T value = read(columns.index(label), type);
        if (value == null) {
            throw new DataException("Required result column '" + label + "' contains SQL NULL");
        }
        return value;
    }

    private <T> T read(int index, Class<T> requestedType) {
        Objects.requireNonNull(requestedType, "Target type must not be null");
        Class<?> targetType = normalized(requestedType);
        if (!SUPPORTED_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("Unsupported JDBC scalar type: " + requestedType.getTypeName());
        }
        try {
            Object value;
            if (targetType == BigInteger.class) {
                Object raw = resultSet.getObject(index);
                value = toBigInteger(raw, index);
            } else {
                value = resultSet.getObject(index, targetType);
            }
            @SuppressWarnings("unchecked")
            T result = (T) value;
            return result;
        } catch (SQLException e) {
            throw JdbcExceptionTranslator.translate(operation, e);
        }
    }

    private static BigInteger toBigInteger(Object value, int index) {
        if (value == null || value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new DataException("Result column " + index + " is not an exact BigInteger", e);
            }
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            Number number = (Number) value;
            return BigInteger.valueOf(number.longValue());
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).toBigIntegerExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new DataException("Result column " + index + " is not an exact BigInteger", e);
            }
        }
        throw new DataException("Result column " + index + " cannot be converted to BigInteger from "
                                        + value.getClass().getTypeName());
    }

    private void validateIndex(int index) {
        if (index < 1 || index > columns.columnCount()) {
            throw new IllegalArgumentException("Result column index must be between 1 and "
                                                       + columns.columnCount() + ": " + index);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("JDBC row is valid only during its mapper or reducer callback");
        }
    }

    private static Class<?> normalized(Class<?> type) {
        Objects.requireNonNull(type, "Target type must not be null");
        return type.isPrimitive() ? PRIMITIVE_WRAPPERS.getOrDefault(type, type) : type;
    }
}
