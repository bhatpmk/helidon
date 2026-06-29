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
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

import io.helidon.data.DataException;

final class JdbcValues {

    private JdbcValues() {
    }

    @SuppressWarnings("unchecked")
    static <T> T convert(Object value, Class<T> requestedType) {
        Objects.requireNonNull(requestedType, "Requested type must not be null");
        Class<?> type = wrapper(requestedType);
        if (value == null) {
            if (requestedType.isPrimitive()) {
                throw new DataException("Cannot map SQL NULL to primitive " + requestedType.getName());
            }
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        if (Number.class.isAssignableFrom(type) && value instanceof Number number) {
            return (T) convertNumber(number, type);
        }
        if (type == String.class) {
            return (T) value.toString();
        }
        if (type == Boolean.class) {
            return (T) convertBoolean(value);
        }
        if (type == LocalDate.class && value instanceof Date date) {
            return (T) date.toLocalDate();
        }
        if (type == LocalTime.class && value instanceof Time time) {
            return (T) time.toLocalTime();
        }
        if (type == LocalDateTime.class && value instanceof Timestamp timestamp) {
            return (T) timestamp.toLocalDateTime();
        }
        throw new DataException("Cannot map JDBC value of type " + value.getClass().getName()
                                        + " to " + requestedType.getName());
    }

    private static Object convertNumber(Number number, Class<?> type) {
        if (type == Number.class) {
            return number;
        }
        if (type == Long.class) {
            return number.longValue();
        }
        if (type == Integer.class) {
            long value = number.longValue();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new DataException("Numeric value " + value + " cannot be represented as int");
            }
            return (int) value;
        }
        if (type == Short.class) {
            long value = number.longValue();
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw new DataException("Numeric value " + value + " cannot be represented as short");
            }
            return (short) value;
        }
        if (type == Byte.class) {
            long value = number.longValue();
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new DataException("Numeric value " + value + " cannot be represented as byte");
            }
            return (byte) value;
        }
        if (type == Double.class) {
            return number.doubleValue();
        }
        if (type == Float.class) {
            return number.floatValue();
        }
        if (type == BigInteger.class) {
            if (number instanceof BigInteger bigInteger) {
                return bigInteger;
            }
            if (number instanceof BigDecimal bigDecimal) {
                return bigDecimal.toBigIntegerExact();
            }
            return BigInteger.valueOf(number.longValue());
        }
        if (type == BigDecimal.class) {
            return createBigDecimal(number);
        }
        throw new DataException("Unsupported numeric target type " + type.getName());
    }

    private static BigDecimal createBigDecimal(Number number) {
        return switch (number) {
        case BigDecimal bd -> bd;
        case BigInteger bi -> new BigDecimal(bi);
        case Long l -> BigDecimal.valueOf(l);
        case Integer i -> BigDecimal.valueOf(i);
        case Short s -> BigDecimal.valueOf(s);
        case Byte b -> BigDecimal.valueOf(b);
        case Float f -> BigDecimal.valueOf(f);
        case Double d -> BigDecimal.valueOf(d);
        default -> new BigDecimal(number.toString());
        };
    }

    private static Boolean convertBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        throw new DataException("Cannot map JDBC value of type " + value.getClass().getName() + " to boolean");
    }

    private static Class<?> wrapper(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
