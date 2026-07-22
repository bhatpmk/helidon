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

    // This method rejects unused parameters, missing parameters, duplicate parameter names, collection-valued parameters,
    // and types outside the provider's fixed declarative scalar table.
    static JdbcSqlParameterPlan create(String sql,
                                       List<TypedElementInfo> bindableParameters,
                                       TypedElementInfo method) {
        JdbcSqlMarkerLexer.Result parsed;
        try {
            parsed = JdbcSqlMarkerLexer.parse(sql);
        } catch (IllegalArgumentException e) {
            throw failure(method, e.getMessage());
        }

        Map<String, Parameter> byName = new HashMap<>(bindableParameters.size());
        for (TypedElementInfo parameter : bindableParameters) {
            if (byName.containsKey(parameter.elementName())) {
                throw failure(method, "Duplicate repository parameter name: " + parameter.elementName());
            }
            if (parameter.typeName().isList()
                    || "java.util.Collection".equals(parameter.typeName().genericTypeName().fqName())
                    || "java.util.Set".equals(parameter.typeName().genericTypeName().fqName())) {
                throw failure(method, "Collection-valued SQL parameters are not supported: "
                        + parameter.elementName());
            }
            if (!JdbcMethodPlan.isScalar(parameter.typeName())) {
                throw failure(method, "Unsupported declarative SQL parameter type: "
                        + parameter.typeName().resolvedName());
            }
            byName.put(parameter.elementName(), new Parameter(parameter, JdbcBindTypePlan.create(parameter, method)));
        }

        List<Bind> binds = new ArrayList<>(parsed.markers().size());
        Set<String> used = new HashSet<>();
        int position = 1;
        for (String marker : parsed.markers()) {
            Parameter parameter = byName.get(marker);
            if (parameter == null) {
                throw failure(method, "SQL marker ':" + marker + "' has no matching repository parameter");
            }
            used.add(marker);
            binds.add(new Bind(position++, parameter.element(), parameter.bindType()));
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

    private static CodegenException failure(TypedElementInfo method, String message) {
        return new CodegenException(message, method.originatingElementValue());
    }

    private record Parameter(TypedElementInfo element, JdbcBindTypePlan bindType) {
    }

    record Bind(int position, TypedElementInfo parameter, JdbcBindTypePlan bindType) {
    }
}
