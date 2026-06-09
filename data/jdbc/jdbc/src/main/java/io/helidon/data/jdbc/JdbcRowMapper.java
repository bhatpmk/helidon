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

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Locale;

import io.helidon.data.DataException;

/**
 * Minimal result mapper used by the POC runtime.
 * <p>
 * The mapper supports scalar results and Java records. Record components are matched against JDBC column labels,
 * case-insensitive labels, or snake-case labels. This lets SQL aliases such as {@code AS typeId} map directly to
 * record components such as {@code typeId}.
 * </p>
 * <p>
 * Production code should prefer generated mappers, as documented in the proposal. Reflection keeps this POC small
 * while still proving the repository/codegen/runtime path end to end.
 * </p>
 */
final class JdbcRowMapper {

    private JdbcRowMapper() {
    }

    static <T> T map(ResultSet resultSet, Class<T> resultType) throws SQLException {
        if (isSimpleType(resultType)) {
            return resultType.cast(convert(resultSet.getObject(1), resultType));
        }
        if (resultType.isRecord()) {
            return mapRecord(resultSet, resultType);
        }
        throw new DataException("JDBC POC mapper supports simple scalar values and Java records only: "
                                        + resultType.getName());
    }

    private static <T> T mapRecord(ResultSet resultSet, Class<T> resultType) throws SQLException {
        RecordComponent[] components = resultType.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            args[i] = convert(columnValue(resultSet, component.getName()), component.getType());
        }

        try {
            Constructor<T> constructor = resultType.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new DataException("Could not map JDBC row to record " + resultType.getName(), e);
        }
    }

    private static Object columnValue(ResultSet resultSet, String componentName) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column);
            if (componentName.equals(label)
                    || componentName.equalsIgnoreCase(label)
                    || toSnakeCase(componentName).equalsIgnoreCase(label)) {
                return resultSet.getObject(column);
            }
        }
        throw new DataException("Result set does not contain a column for record component " + componentName);
    }

    private static Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return ((Number) value).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return ((Number) value).longValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return ((Number) value).shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return ((Number) value).byteValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return ((Number) value).floatValue();
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
        }
        return value;
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class;
    }

    private static String toSnakeCase(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(ch));
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }
}
