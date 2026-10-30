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
import java.util.function.Consumer;

import io.helidon.codegen.RoundContext;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

record JdbcCallablePlan(List<Output> outputs, List<Output> components, TypedElementInfo control,
                        boolean consumer, boolean snapshot, boolean aggregate) {
    private static final Set<String> SNAPSHOT_TYPES = Set.of("BOOLEAN", "BIT", "TINYINT", "SMALLINT", "INTEGER",
            "BIGINT", "REAL", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR",
            "NCHAR", "NVARCHAR", "LONGNVARCHAR", "BINARY", "VARBINARY", "LONGVARBINARY", "DATE", "TIME", "TIMESTAMP");

    private static final Map<String, Set<String>> INPUT_TYPES = Map.ofEntries(
            Map.entry("BIT", Set.of("BOOLEAN")),
            Map.entry("NUMERIC", Set.of("DECIMAL")),
            Map.entry("DECFLOAT", Set.of("DECIMAL")),
            Map.entry("FLOAT", Set.of("DOUBLE")),
            Map.entry("CHAR", Set.of("VARCHAR")),
            Map.entry("LONGVARCHAR", Set.of("VARCHAR")),
            Map.entry("NCHAR", Set.of("VARCHAR")),
            Map.entry("NVARCHAR", Set.of("VARCHAR")),
            Map.entry("LONGNVARCHAR", Set.of("VARCHAR")),
            Map.entry("JSON", Set.of("VARCHAR")),
            Map.entry("BINARY", Set.of("VARBINARY")),
            Map.entry("LONGVARBINARY", Set.of("VARBINARY")));

    static boolean selected(TypedElementInfo method, RoundContext context) {
        return JdbcExecutionSelection.create(method) == JdbcExecutionSelection.CALL
                || !declarations(method).isEmpty()
                || components(method.typeName(), context).stream().anyMatch(component -> !declarations(component).isEmpty());
    }

    static List<Annotation> declarations(TypedElementInfo declaration) {
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : declaration.annotations()) {
            if (annotation.typeName().equals(JdbcCodegenTypes.JDBC_OUT)) {
                result.add(annotation);
            } else if (annotation.typeName().equals(JdbcCodegenTypes.JDBC_OUTS)) {
                result.addAll(annotation.annotationValues().orElse(List.of()));
            }
        }
        return List.copyOf(result);
    }

    static JdbcMethodPlan create(TypedElementInfo method, RoundContext context, String sql) {
        JdbcExecutionSelection execution = JdbcExecutionSelection.create(method);
        if (execution == JdbcExecutionSelection.QUERY || execution == JdbcExecutionSelection.UPDATE
                || method.hasAnnotation(JdbcCodegenTypes.JDBC_GENERATED_KEYS)) {
            throw JdbcMethodPlan.failure(method, "Callable outputs require CALL execution and cannot request generated keys.");
        }
        List<TypedElementInfo> recordComponents = components(method.typeName(), context);
        boolean aggregate = recordComponents.stream().anyMatch(component -> !declarations(component).isEmpty());
        if (aggregate && (!declarations(method).isEmpty() || method.hasAnnotation(JdbcCodegenTypes.JDBC_ROW_MAPPER))) {
            throw JdbcMethodPlan.failure(method, "An output record cannot accompany method outputs or a method row mapper.");
        }
        List<Output> outputs = aggregate
                ? recordComponents.stream().flatMap(component -> outputs(component, component.elementName()).stream()).toList()
                : outputs(method, "return");
        TypedElementInfo control = null;
        boolean consumer = false;
        List<TypedElementInfo> inputs = new ArrayList<>();
        for (TypedElementInfo parameter : method.parameterArguments()) {
            TypeName type = parameter.typeName();
            boolean isConsumer = type.genericTypeName().equals(TypeName.create(Consumer.class));
            boolean isMapper = type.genericTypeName().equals(JdbcCodegenTypes.CALL_MAPPER);
            if (isConsumer || isMapper) {
                if (control != null || type.typeArguments().size() != 1) {
                    throw JdbcMethodPlan.failure(parameter, "A callable method requires at most one fully typed callback.");
                }
                TypeName expected = isConsumer ? JdbcCodegenTypes.CALL_RESULT : method.typeName().boxed();
                if (!ResolvedType.create(type.typeArguments().getFirst()).equals(ResolvedType.create(expected))
                        || (isConsumer && !method.typeName().equals(TypeNames.PRIMITIVE_VOID))
                        || (!isConsumer && method.typeName().equals(TypeNames.PRIMITIVE_VOID))) {
                    throw JdbcMethodPlan.failure(parameter, "The callable callback type must match the method return contract.");
                }
                control = parameter;
                consumer = isConsumer;
            } else {
                if (!JdbcScalarTypes.isScalar(type)) {
                    throw JdbcMethodPlan.failure(parameter, "A callable input must have a supported scalar type.");
                }
                inputs.add(parameter);
            }
        }
        if (control != null && (aggregate || method.hasAnnotation(JdbcCodegenTypes.JDBC_ROW_MAPPER))) {
            throw JdbcMethodPlan.failure(method, "A callable callback cannot accompany automatic output records or row mappers.");
        }
        JdbcSqlMarkerLexer.Result parsed;
        try {
            parsed = JdbcSqlMarkerLexer.parse(sql, true);
        } catch (IllegalArgumentException failure) {
            throw JdbcMethodPlan.failure(method, failure.getMessage());
        }
        Set<String> names = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        Map<String, Output> byName = new HashMap<>();
        List<Output> positioned = new ArrayList<>();
        for (Output output : outputs) {
            if (!names.add(output.name())) {
                throw JdbcMethodPlan.failure(output.declaration(), "Callable output names must be unique.");
            }
            int index;
            if (output.marker().isEmpty()) {
                if (!parsed.leadingReturn() || aggregate) {
                    throw JdbcMethodPlan.failure(method, "An unnamed method output requires a leading callable return slot.");
                }
                index = 1;
            } else {
                index = parsed.markers().indexOf(output.marker()) + 1;
                if (index == 0 || parsed.markers().lastIndexOf(output.marker()) != index - 1) {
                    throw JdbcMethodPlan.failure(output.declaration(),
                            "An OUT or INOUT marker must occur exactly once. Output " + output.name()
                                    + " has first and last JDBC positions " + index + " and "
                                    + (parsed.markers().lastIndexOf(output.marker()) + 1) + ".");
                }
                if (parsed.leadingReturn() && output.name().equals("return")) {
                    throw JdbcMethodPlan.failure(method, "The leading return reserves the logical output name return.");
                }
            }
            positions.add(index);
            Output located = new Output(output.name(), output.marker(), output.type(), output.typeName(), output.scale(),
                                        index, output.declaration(), null);
            positioned.add(located);
            byName.put(output.marker(), located);
        }
        if (parsed.leadingReturn() && !positions.contains(1)) {
            throw JdbcMethodPlan.failure(method, "A leading callable return requires an unnamed method output declaration.");
        }
        List<JdbcSqlParameterPlan.Bind> binds = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int inputIndex = 0;
        for (int index = 0; index < parsed.markers().size(); index++) {
            String marker = parsed.markers().get(index);
            TypedElementInfo input = null;
            if (index == 0 && parsed.leadingReturn()) {
                continue;
            }
            if (parsed.style() == JdbcSqlMarkerLexer.MarkerStyle.NAMED) {
                input = inputs.stream().filter(parameter -> parameter.elementName().equals(marker)).findFirst().orElse(null);
            } else if (!positions.contains(index + 1) && inputIndex < inputs.size()) {
                input = inputs.get(inputIndex++);
            }
            if (input != null) {
                used.add(input.elementName());
                Output output = parsed.style() == JdbcSqlMarkerLexer.MarkerStyle.NAMED ? byName.get(marker) : null;
                if (output != null && !INPUT_TYPES.getOrDefault(output.type(), Set.of(output.type()))
                        .contains(JdbcScalarTypes.nullJdbcTypeConstant(input.typeName()))) {
                    throw JdbcMethodPlan.failure(input, "The input type is incompatible with its INOUT registration.");
                }
                binds.add(JdbcSqlParameterPlan.binding(index + 1, input));
            } else if (!positions.contains(index + 1)) {
                throw JdbcMethodPlan.failure(method, "A callable SQL marker has no input or output declaration.");
            }
        }
        if (used.size() != inputs.size()) {
            throw JdbcMethodPlan.failure(method, "Every callable input must be used by its SQL markers.");
        }
        JdbcSqlParameterPlan parameters = new JdbcSqlParameterPlan(parsed.sql(), binds, parsed.markers().size());
        boolean snapshot = method.typeName().equals(JdbcCodegenTypes.OUT_PARAMETERS);
        JdbcMethodPlan plan = JdbcMethodPlan.callableUnmapped(method, parameters);
        List<Output> mappedOutputs = new ArrayList<>();
        boolean discarded = method.typeName().equals(TypeNames.PRIMITIVE_VOID) || control != null;
        for (Output output : positioned) {
            JdbcMethodPlan mapping = null;
            if (snapshot && (!SNAPSHOT_TYPES.contains(output.type()) || !output.typeName().isEmpty())) {
                throw JdbcMethodPlan.failure(method, "A detached output snapshot requires canonical scalar registrations.");
            }
            if (aggregate || (!discarded && !snapshot && positioned.size() == 1)) {
                if (aggregate) {
                    mapping = outputMapping(output, context, output.declaration().typeName(), parameters);
                } else {
                    mapping = outputMapping(output, context, method.typeName(), parameters);
                    plan = mapping == null ? plan : mapping;
                }
            }
            mappedOutputs.add(new Output(output.name(), output.marker(), output.type(), output.typeName(), output.scale(),
                                         output.index(), output.declaration(), mapping));
        }
        if (!discarded && !aggregate && !snapshot) {
            if (outputs.isEmpty()) {
                plan = JdbcMethodPlan.callableMapping(method, context, method.typeName(), parameters);
            } else if (outputs.size() != 1) {
                throw JdbcMethodPlan.failure(method, "Multiple outputs require a snapshot, callback, or output record.");
            }
        }
        if (snapshot && (outputs.isEmpty() || method.hasAnnotation(JdbcCodegenTypes.JDBC_ROW_MAPPER))) {
            throw JdbcMethodPlan.failure(method, "A snapshot requires scalar outputs and cannot select a row mapper.");
        }
        plan.callable(new JdbcCallablePlan(List.copyOf(mappedOutputs), aggregate ? List.copyOf(mappedOutputs) : List.of(),
                                           control, consumer, snapshot, aggregate));
        return plan;
    }

    private static List<Output> outputs(TypedElementInfo declaration, String fallback) {
        List<Annotation> annotations = declarations(declaration);
        if (declaration.kind() == ElementKind.RECORD_COMPONENT && annotations.size() != 1) {
            throw JdbcMethodPlan.failure(declaration, "Every output record component must declare exactly one output.");
        }
        return annotations.stream().map(annotation -> {
            String marker = annotation.stringValue().orElse("");
            String name = marker.isEmpty() ? fallback : marker;
            String type = annotation.stringValue("type").orElseThrow(() ->
                    JdbcMethodPlan.failure(declaration, "Every callable output requires an explicit JDBC type."));
            String typeName = annotation.stringValue("typeName").orElse("");
            int scale = annotation.intValue("scale").orElse(-1);
            if (name.isBlank() || (!typeName.isEmpty() && typeName.isBlank()) || scale < -1
                    || (scale >= 0 && (!typeName.isEmpty() || (!type.equals("NUMERIC") && !type.equals("DECIMAL"))))) {
                throw JdbcMethodPlan.failure(declaration, "The callable output name, database type name, or scale is invalid.");
            }
            return new Output(name, declaration.kind() == ElementKind.RECORD_COMPONENT ? name : marker,
                              type, typeName, scale, 0, declaration, null);
        }).toList();
    }

    private static JdbcMethodPlan outputMapping(Output output, RoundContext context, TypeName type,
                                                JdbcSqlParameterPlan parameters) {
        if (output.type().equals("REF_CURSOR")) {
            return JdbcMethodPlan.callableMapping(output.declaration(), context, type, parameters);
        }
        if ((!JdbcScalarTypes.isScalar(type) && JdbcScalarTypes.optionalScalarType(type).isEmpty())
                || output.declaration().hasAnnotation(JdbcCodegenTypes.JDBC_ROW_MAPPER)) {
            throw JdbcMethodPlan.failure(output.declaration(), "A scalar output requires a supported scalar return type.");
        }
        return null;
    }

    private static List<TypedElementInfo> components(TypeName type, RoundContext context) {
        TypeInfo info = context.typeInfo(type).orElse(null);
        if (info == null || info.kind() != ElementKind.RECORD) {
            return List.of();
        }
        return info.elementInfo().stream().filter(element -> element.kind() == ElementKind.RECORD_COMPONENT)
                .map(element -> TypedElementInfo.builder(element)
                        .typeName(context.typeHierarchyResolver().resolveMemberType(info, type, element.typeName())).build())
                .toList();
    }

    record Output(String name, String marker, String type, String typeName, int scale, int index,
                  TypedElementInfo declaration, JdbcMethodPlan mapping) {
    }
}
