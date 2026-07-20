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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

/**
 * Compile-time callable SQL, direction, registration, and input-binding plan.
 */
final class JdbcCallParameterPlan {
    private static final int REF_CURSOR = 2012;
    private static final int INFERRED_TYPE = Integer.MIN_VALUE;
    private static final Pattern PROCEDURE = Pattern.compile("(?is)^\\s*\\{\\s*call\\b.*}\\s*$");
    private static final Pattern FUNCTION = Pattern.compile(
            "(?is)^\\s*\\{\\s*(?:\\?|:[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)"
                    + "\\s*=\\s*call\\b.*}\\s*$");

    private final String sql;
    private final List<Parameter> parameters;
    private final List<Bind> binds;

    private JdbcCallParameterPlan(String sql, List<Parameter> parameters, List<Bind> binds) {
        this.sql = sql;
        this.parameters = parameters;
        this.binds = binds;
    }

    static boolean callableAnnotations(TypedElementInfo method) {
        if (method.hasAnnotation(JdbcCodegenTypes.JDBC_OUT_PARAMETER)
                || method.hasAnnotation(JdbcCodegenTypes.JDBC_OUT_PARAMETERS)
                || method.hasAnnotation(JdbcCodegenTypes.JDBC_RETURN_PARAMETER)) {
            return true;
        }
        return method.parameterArguments().stream()
                .anyMatch(parameter -> parameter.hasAnnotation(JdbcCodegenTypes.JDBC_IN_PARAMETER)
                        || parameter.hasAnnotation(JdbcCodegenTypes.JDBC_IN_OUT_PARAMETER));
    }

    static JdbcCallParameterPlan create(String sourceSql,
                                        List<TypedElementInfo> inputParameters,
                                        TypedElementInfo method) {
        boolean function = FUNCTION.matcher(sourceSql).matches();
        if (!function && !PROCEDURE.matcher(sourceSql).matches()) {
            throw JdbcMethodPlan.failure(method,
                                         "CALL execution requires standard JDBC procedure or function escape syntax");
        }

        JdbcSqlMarkerLexer.Result parsed;
        try {
            parsed = JdbcSqlMarkerLexer.parseCall(sourceSql);
        } catch (IllegalArgumentException e) {
            throw JdbcMethodPlan.failure(method, e.getMessage());
        }
        if (parsed.markers().isEmpty()) {
            if (!inputParameters.isEmpty() || callableAnnotations(method)) {
                throw JdbcMethodPlan.failure(method, "JDBC call has declarations but no parameter markers");
            }
            return new JdbcCallParameterPlan(parsed.sql(), List.of(), List.of());
        }

        boolean named = !parsed.markers().getFirst().isEmpty();
        validateUniqueNamedMarkers(parsed.markers(), named, method);

        List<Parameter> parameters = new ArrayList<>(parsed.markers().size());
        List<Bind> binds = new ArrayList<>(inputParameters.size());
        Set<Integer> positions = new HashSet<>();
        Set<String> outputNames = new HashSet<>();

        for (TypedElementInfo input : inputParameters) {
            Annotation in = input.findAnnotation(JdbcCodegenTypes.JDBC_IN_PARAMETER).orElse(null);
            Annotation inOut = input.findAnnotation(JdbcCodegenTypes.JDBC_IN_OUT_PARAMETER).orElse(null);
            if (in != null && inOut != null) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC call parameter '" + input.elementName()
                                                     + "' cannot be both IN and INOUT");
            }
            if (in == null && inOut == null) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC call input parameter requires @Jdbc.InParameter or "
                                                     + "@Jdbc.InOutParameter: " + input.elementName());
            }
            if (!JdbcMethodPlan.isScalar(input.typeName())) {
                throw JdbcMethodPlan.failure(method,
                                             "Unsupported JDBC call input type: " + input.typeName().resolvedName());
            }

            Annotation annotation = in == null ? inOut : in;
            String declaredName = annotation.stringValue("name").orElse("");
            String locator = declaredName.isBlank() ? input.elementName() : declaredName;
            int declaredIndex = annotation.intValue("index").orElse(-1);
            int position = resolve(locator, declaredIndex, parsed.markers(), named, method);
            Direction direction = in == null ? Direction.INOUT : Direction.IN;
            int jdbcType = annotation.intValue("jdbcType").orElse(INFERRED_TYPE);
            if (direction == Direction.INOUT && jdbcType == INFERRED_TYPE) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC INOUT parameter requires an explicit jdbcType: "
                                                     + input.elementName());
            }
            String outputName = direction == Direction.INOUT ? locator : "";
            Parameter parameter = new Parameter(position,
                                                direction,
                                                outputName,
                                                jdbcType,
                                                direction == Direction.INOUT ? input.typeName() : TypeNames.BOXED_VOID,
                                                "");
            add(parameter, parameters, positions, outputNames, method);
            binds.add(new Bind(position, input));
        }

        for (Annotation annotation : outAnnotations(method)) {
            String name = annotation.stringValue("name").orElse("");
            if (name.isBlank()) {
                throw JdbcMethodPlan.failure(method, "@Jdbc.OutParameter requires a non-blank output name");
            }
            int position = resolve(name,
                                   annotation.intValue("index").orElse(-1),
                                   parsed.markers(),
                                   named,
                                   method);
            int jdbcType = requiredInt(annotation, "jdbcType", method, "@Jdbc.OutParameter");
            TypeName javaType = annotation.typeValue("javaType").orElse(TypeNames.BOXED_VOID);
            String typeName = typeName(annotation, method, "@Jdbc.OutParameter");
            Direction direction;
            if (jdbcType == REF_CURSOR) {
                if (!javaType.equals(TypeNames.BOXED_VOID)) {
                    throw JdbcMethodPlan.failure(method,
                                                 "A REF_CURSOR output must leave javaType as Void.class: " + name);
                }
                direction = Direction.CURSOR;
            } else {
                if (javaType.equals(TypeNames.BOXED_VOID) || !JdbcMethodPlan.isScalar(javaType)) {
                    throw JdbcMethodPlan.failure(method,
                                                 "JDBC scalar OUT parameter requires a supported javaType: " + name);
                }
                direction = Direction.OUT;
            }
            add(new Parameter(position, direction, name, jdbcType, javaType, typeName),
                parameters,
                positions,
                outputNames,
                method);
        }

        Annotation returnAnnotation = method.findAnnotation(JdbcCodegenTypes.JDBC_RETURN_PARAMETER).orElse(null);
        if (function != (returnAnnotation != null)) {
            throw JdbcMethodPlan.failure(method,
                                         function
                                                 ? "JDBC function syntax requires @Jdbc.ReturnParameter"
                                                 : "@Jdbc.ReturnParameter requires JDBC function escape syntax");
        }
        if (returnAnnotation != null) {
            String name = returnAnnotation.stringValue("name").orElse("");
            if (name.isBlank()) {
                throw JdbcMethodPlan.failure(method, "@Jdbc.ReturnParameter requires a non-blank name");
            }
            int position = resolve(name, 1, parsed.markers(), named, method);
            TypeName javaType = returnAnnotation.typeValue("javaType")
                    .orElseThrow(() -> JdbcMethodPlan.failure(method,
                                                              "@Jdbc.ReturnParameter javaType is missing"));
            if (!JdbcMethodPlan.isScalar(javaType)) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC function return requires a supported scalar javaType: "
                                                     + javaType.resolvedName());
            }
            int jdbcType = requiredInt(returnAnnotation, "jdbcType", method, "@Jdbc.ReturnParameter");
            add(new Parameter(position,
                              Direction.RETURN,
                              name,
                              jdbcType,
                              javaType,
                              typeName(returnAnnotation, method, "@Jdbc.ReturnParameter")),
                parameters,
                positions,
                outputNames,
                method);
        }

        for (int position = 1; position <= parsed.markers().size(); position++) {
            if (!positions.contains(position)) {
                String marker = parsed.markers().get(position - 1);
                String locator = marker.isEmpty() ? String.valueOf(position) : ":" + marker;
                throw JdbcMethodPlan.failure(method, "JDBC call marker " + locator + " has no parameter declaration");
            }
        }
        parameters.sort(Comparator.comparingInt(Parameter::position));
        binds.sort(Comparator.comparingInt(Bind::position));
        return new JdbcCallParameterPlan(parsed.sql(), List.copyOf(parameters), List.copyOf(binds));
    }

    private static void validateUniqueNamedMarkers(List<String> markers,
                                                   boolean named,
                                                   TypedElementInfo method) {
        if (!named) {
            return;
        }
        Set<String> unique = new HashSet<>();
        for (String marker : markers) {
            if (!unique.add(marker)) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC call marker ':" + marker
                                                     + "' must identify exactly one physical position");
            }
        }
    }

    private static List<Annotation> outAnnotations(TypedElementInfo method) {
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : method.annotations()) {
            if (annotation.typeName().equals(JdbcCodegenTypes.JDBC_OUT_PARAMETER)) {
                result.add(annotation);
            } else if (annotation.typeName().equals(JdbcCodegenTypes.JDBC_OUT_PARAMETERS)) {
                result.addAll(annotation.annotationValues().orElse(List.of()));
            }
        }
        return result;
    }

    private static int resolve(String name,
                               int index,
                               List<String> markers,
                               boolean named,
                               TypedElementInfo method) {
        if (named) {
            if (name.isBlank()) {
                throw JdbcMethodPlan.failure(method, "Named JDBC call parameter requires a name");
            }
            int resolved = markers.indexOf(name) + 1;
            if (resolved == 0) {
                throw JdbcMethodPlan.failure(method, "JDBC call marker ':" + name + "' is not present");
            }
            if (index != -1 && index != resolved) {
                throw JdbcMethodPlan.failure(method,
                                             "JDBC call parameter name '" + name + "' resolves to position "
                                                     + resolved + " but index is " + index);
            }
            return resolved;
        }
        if (index < 1 || index > markers.size()) {
            throw JdbcMethodPlan.failure(method,
                                         "Positional JDBC call parameter index must be between 1 and "
                                                 + markers.size() + ": " + index);
        }
        return index;
    }

    private static void add(Parameter parameter,
                            List<Parameter> parameters,
                            Set<Integer> positions,
                            Set<String> outputNames,
                            TypedElementInfo method) {
        if (!positions.add(parameter.position())) {
            throw JdbcMethodPlan.failure(method,
                                         "Duplicate JDBC call parameter position: " + parameter.position());
        }
        if (parameter.output() && !outputNames.add(parameter.name())) {
            throw JdbcMethodPlan.failure(method, "Duplicate JDBC call output name: " + parameter.name());
        }
        parameters.add(parameter);
    }

    private static int requiredInt(Annotation annotation,
                                   String property,
                                   TypedElementInfo method,
                                   String declaration) {
        int value = annotation.intValue(property)
                .orElseThrow(() -> JdbcMethodPlan.failure(method, declaration + " " + property + " is missing"));
        if (value == INFERRED_TYPE) {
            throw JdbcMethodPlan.failure(method, declaration + " requires an explicit " + property);
        }
        return value;
    }

    private static String typeName(Annotation annotation, TypedElementInfo method, String declaration) {
        String typeName = annotation.stringValue("typeName").orElse("");
        if (!typeName.isEmpty() && typeName.isBlank()) {
            throw JdbcMethodPlan.failure(method, declaration + " typeName must not be blank");
        }
        return typeName;
    }

    String sql() {
        return sql;
    }

    List<Parameter> parameters() {
        return parameters;
    }

    List<Bind> binds() {
        return binds;
    }

    enum Direction {
        IN,
        OUT,
        INOUT,
        CURSOR,
        RETURN
    }

    record Parameter(int position,
                     Direction direction,
                     String name,
                     int jdbcType,
                     TypeName javaType,
                     String typeName) {
        boolean output() {
            return direction != Direction.IN;
        }
    }

    record Bind(int position, TypedElementInfo parameter) {
    }
}
