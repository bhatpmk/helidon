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
package io.helidon.data.jdbc.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.helidon.codegen.CodegenException;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;

/**
 * Compile-time correspondence between rewritten JDBC positions and repository parameters.
 */
final class JdbcSqlParameterPlan {
    private final String sql;
    private final List<Bind> binds;

    private JdbcSqlParameterPlan(String sql, List<Bind> binds) {
        this.sql = sql;
        this.binds = binds;
    }

    static JdbcSqlParameterPlan create(String sql,
                                       List<TypedElementInfo> bindableParameters,
                                       TypedElementInfo method) {
        JdbcSqlMarkerLexer.Result parsed;
        try {
            parsed = JdbcSqlMarkerLexer.parse(sql);
        } catch (IllegalArgumentException e) {
            throw failure(method, e.getMessage());
        }

        Map<String, TypedElementInfo> byName = new HashMap<>(bindableParameters.size());
        for (TypedElementInfo parameter : bindableParameters) {
            if (byName.put(parameter.elementName(), parameter) != null) {
                throw failure(method, "Duplicate repository parameter name: " + parameter.elementName());
            }
            if (parameter.typeName().isList()
                    || "java.util.Collection".equals(parameter.typeName().genericTypeName().fqName())
                    || "java.util.Set".equals(parameter.typeName().genericTypeName().fqName())) {
                throw failure(method, "Collection-valued SQL parameters are not supported: "
                        + parameter.elementName());
            }
            if (!JdbcMethodPlan.isScalar(parameter.typeName())
                    && !parameter.hasAnnotation(JdbcCodegenTypes.DATA_JDBC_TYPE)) {
                throw failure(method, "Unsupported declarative SQL parameter type without @Data.JdbcType: "
                        + parameter.typeName().resolvedName());
            }
        }

        List<Bind> binds = new ArrayList<>(parsed.markers().size());
        Set<String> used = new HashSet<>();
        int position = 1;
        for (String marker : parsed.markers()) {
            TypedElementInfo parameter = byName.get(marker);
            if (parameter == null) {
                throw failure(method, "SQL marker ':" + marker + "' has no matching repository parameter");
            }
            used.add(marker);
            binds.add(new Bind(position++, parameter, jdbcType(parameter)));
        }
        for (TypedElementInfo parameter : bindableParameters) {
            if (!used.contains(parameter.elementName())) {
                throw failure(method, "Repository parameter is not used by SQL: " + parameter.elementName());
            }
        }
        return new JdbcSqlParameterPlan(parsed.sql(), List.copyOf(binds));
    }

    String sql() {
        return sql;
    }

    List<Bind> binds() {
        return binds;
    }

    private static String jdbcType(TypedElementInfo parameter) {
        String explicit = parameter.findAnnotation(JdbcCodegenTypes.DATA_JDBC_TYPE)
                .flatMap(Annotation::value)
                .map(value -> {
                    int separator = Math.max(value.lastIndexOf('.'), value.lastIndexOf('#'));
                    return separator < 0 ? value : value.substring(separator + 1);
                })
                .orElse(null);
        if (explicit != null || parameter.typeName().primitive()) {
            return explicit;
        }
        return inferredJdbcType(parameter.typeName());
    }

    private static String inferredJdbcType(TypeName type) {
        if (type.array() && type.componentType().map(TypeName::fqName).filter("byte"::equals).isPresent()) {
            return "VARBINARY";
        }
        return switch (type.boxed().genericTypeName().fqName()) {
            case "java.lang.Boolean" -> "BOOLEAN";
            case "java.lang.Byte" -> "TINYINT";
            case "java.lang.Short" -> "SMALLINT";
            case "java.lang.Integer" -> "INTEGER";
            case "java.lang.Long" -> "BIGINT";
            case "java.lang.Float" -> "REAL";
            case "java.lang.Double" -> "DOUBLE";
            case "java.math.BigDecimal" -> "DECIMAL";
            case "java.math.BigInteger" -> "NUMERIC";
            case "java.lang.String" -> "VARCHAR";
            case "java.util.UUID" -> "OTHER";
            case "java.time.LocalDate", "java.sql.Date" -> "DATE";
            case "java.time.LocalTime", "java.sql.Time" -> "TIME";
            case "java.time.LocalDateTime", "java.sql.Timestamp" -> "TIMESTAMP";
            case "java.time.OffsetTime" -> "TIME_WITH_TIMEZONE";
            case "java.time.OffsetDateTime", "java.time.Instant" -> "TIMESTAMP_WITH_TIMEZONE";
            default -> null;
        };
    }

    private static CodegenException failure(TypedElementInfo method, String message) {
        return new CodegenException(message, method.originatingElementValue());
    }

    record Bind(int position, TypedElementInfo parameter, String jdbcType) {
        boolean typed() {
            return jdbcType != null;
        }
    }
}
