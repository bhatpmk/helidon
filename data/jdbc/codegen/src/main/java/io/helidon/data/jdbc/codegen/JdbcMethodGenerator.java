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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.classmodel.Annotation;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.RepositoryInfo;

/**
 * Emits validated repository methods as direct calls to the public JDBC client.
 */
final class JdbcMethodGenerator {
    private JdbcMethodGenerator() {
    }

    static void generate(RepositoryInfo repositoryInfo,
                         ClassModel.Builder classModel,
                         CodegenContext context) {
        List<TypedElementInfo> methods = repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.METHOD)
                .filter(element -> element.elementModifiers().contains(Modifier.ABSTRACT))
                .toList();
        Map<String, Integer> generatedNames = new HashMap<>();
        List<JdbcMethodPlan> plans = new ArrayList<>(methods.size());
        for (TypedElementInfo method : methods) {
            JdbcMethodPlan plan = JdbcMethodPlan.create(method, context);
            String suffix = uniqueSuffix(method.elementName(), generatedNames);
            plan.sqlFieldName("SQL_" + suffix);
            plan.mapperFieldName("MAPPER_" + suffix);
            plan.callFieldName("CALL_" + suffix);
            plans.add(plan);
        }

        List<MapperDependency> mapperDependencies = mapperDependencies(plans, classModel, context);
        JdbcRepositoryClassGenerator.generateConstructor(classModel, repositoryInfo, mapperDependencies);

        for (JdbcMethodPlan plan : plans) {
            String suffix = plan.sqlFieldName().substring("SQL_".length());
            classModel.addField(field -> field.name(plan.sqlFieldName())
                    .type(TypeNames.STRING)
                    .isStatic(true)
                    .isFinal(true)
                    .addContentLiteral(plan.jdbcSql()));
            if (plan.operation() == JdbcMethodPlan.Operation.CALL) {
                generateCallLayout(plan, classModel);
            }
            generateMapping(plan, classModel, context, suffix);
            classModel.addMethod(builder -> generateMethod(plan, builder));
        }
    }

    private static void generateCallLayout(JdbcMethodPlan plan, ClassModel.Builder classModel) {
        classModel.addField(field -> {
            field.name(plan.callFieldName())
                    .type(JdbcCodegenTypes.JDBC_CALL)
                    .isStatic(true)
                    .isFinal(true)
                    .addContent(JdbcCodegenTypes.JDBC_CALL)
                    .addContent(".builder()");
            for (JdbcCallParameterPlan.Parameter parameter : plan.callParameterPlan().parameters()) {
                field.addContent(".");
                switch (parameter.direction()) {
                case IN -> {
                    field.addContent("in(")
                            .addContent(String.valueOf(parameter.position()));
                    if (parameter.jdbcType() != Integer.MIN_VALUE) {
                        field.addContent(", ").addContent(String.valueOf(parameter.jdbcType()));
                    }
                    field.addContent(")");
                }
                case OUT, INOUT -> {
                    field.addContent(parameter.direction() == JdbcCallParameterPlan.Direction.OUT ? "out(" : "inOut(")
                            .addContent(String.valueOf(parameter.position()))
                            .addContent(", ")
                            .addContentLiteral(parameter.name())
                            .addContent(", ")
                            .addContent(String.valueOf(parameter.jdbcType()))
                            .addContent(", ")
                            .addContent(parameter.javaType())
                            .addContent(".class");
                    addTypeName(field, parameter.typeName());
                }
                case CURSOR -> {
                    field.addContent("cursor(")
                            .addContent(String.valueOf(parameter.position()))
                            .addContent(", ")
                            .addContentLiteral(parameter.name());
                    if (parameter.jdbcType() != 2012 || !parameter.typeName().isEmpty()) {
                        field.addContent(", ").addContent(String.valueOf(parameter.jdbcType()));
                    }
                    if (!parameter.typeName().isEmpty()) {
                        field.addContent(", ").addContentLiteral(parameter.typeName());
                    }
                    field.addContent(")");
                }
                case RETURN -> {
                    field.addContent("returns(")
                            .addContentLiteral(parameter.name())
                            .addContent(", ")
                            .addContent(String.valueOf(parameter.jdbcType()))
                            .addContent(", ")
                            .addContent(parameter.javaType())
                            .addContent(".class");
                    addTypeName(field, parameter.typeName());
                }
                default -> throw new AssertionError("Unknown JDBC call direction: " + parameter.direction());
                }
            }
            field.addContent(".build()");
        });
    }

    private static void addTypeName(io.helidon.codegen.classmodel.Field.Builder field, String typeName) {
        if (!typeName.isEmpty()) {
            field.addContent(", ").addContentLiteral(typeName);
        }
        field.addContent(")");
    }

    private static void generateMapping(JdbcMethodPlan plan,
                                        ClassModel.Builder classModel,
                                        CodegenContext context,
                                        String suffix) {
        switch (plan.mappingKind()) {
        case NONE, SCALAR -> {
            // Scalar results use JdbcClient's fixed codec and do not need generated mapper state.
        }
        case RECORD -> {
            if (plan.operation() == JdbcMethodPlan.Operation.GENERATED_KEYS) {
                JdbcRecordMapperGenerator.generate(plan, plan.mapperFieldName(), classModel, context);
            }
        }
        case SERVICE, EXPLICIT -> {
            // Repository construction resolves these mappers once from the service registry.
        }
        case REDUCER -> validateExplicitReducer(plan, context);
        case IDENTITY_REDUCTION -> {
            plan.mapperFieldName("Reducer_" + mixedCase(suffix));
            JdbcIdentityReducerGenerator.generate(plan, classModel, context);
        }
        default -> throw new AssertionError("Unknown JDBC mapping kind: " + plan.mappingKind());
        }
    }

    private static void validateExplicitMapper(JdbcMethodPlan plan, CodegenContext context) {
        TypeName mapperType = plan.explicitMapper();
        TypeInfo mapperInfo = context.typeInfo(mapperType)
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Mapper type information is unavailable: "
                                                                  + mapperType.resolvedName()));
        boolean samePackage = samePackage(plan.method(), mapperType);
        if (mapperInfo.kind() != ElementKind.CLASS
                || mapperInfo.elementModifiers().contains(Modifier.ABSTRACT)) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper must be a concrete class: "
                    + mapperType.resolvedName());
        }
        if (!mapperType.enclosingNames().isEmpty() && !mapperInfo.elementModifiers().contains(Modifier.STATIC)) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper must not be a non-static nested class: "
                    + mapperType.resolvedName());
        }
        if (mapperInfo.accessModifier() != AccessModifier.PUBLIC
                && !(samePackage && (mapperInfo.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || mapperInfo.accessModifier() == AccessModifier.PROTECTED))) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper is not accessible to generated code: "
                    + mapperType.resolvedName());
        }
        TypeName mappedInterface = findImplementedInterface(mapperInfo, JdbcCodegenTypes.ROW_MAPPER);
        if (mappedInterface == null
                || mappedInterface.typeArguments().size() != 1
                || !mappedInterface.typeArguments().getFirst().equals(plan.mappedType())) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper must implement JdbcClient.RowMapper<"
                    + plan.mappedType().resolvedName() + "> directly or through its type hierarchy");
        }
    }

    private static List<MapperDependency> mapperDependencies(List<JdbcMethodPlan> plans,
                                                             ClassModel.Builder classModel,
                                                             CodegenContext context) {
        Map<MapperDependencyKey, List<JdbcMethodPlan>> groupedPlans = new LinkedHashMap<>();
        for (JdbcMethodPlan plan : plans) {
            if (plan.mappingKind() == JdbcMethodPlan.MappingKind.EXPLICIT) {
                validateExplicitMapper(plan, context);
                MapperDependencyKey key = new MapperDependencyKey(plan.explicitMapper(), plan.mappedType(), true, false);
                groupedPlans.computeIfAbsent(key, ignored -> new ArrayList<>()).add(plan);
            } else if ((plan.operation() == JdbcMethodPlan.Operation.QUERY
                    && (plan.mappingKind() == JdbcMethodPlan.MappingKind.RECORD
                    || plan.mappingKind() == JdbcMethodPlan.MappingKind.SERVICE))
                    || (plan.operation() == JdbcMethodPlan.Operation.GENERATED_KEYS
                    && plan.mappingKind() == JdbcMethodPlan.MappingKind.SERVICE)) {
                boolean optional = plan.operation() == JdbcMethodPlan.Operation.QUERY
                        && plan.mappingKind() == JdbcMethodPlan.MappingKind.RECORD;
                MapperDependencyKey key = new MapperDependencyKey(plan.mappedType(),
                                                                  plan.mappedType(),
                                                                  false,
                                                                  optional);
                groupedPlans.computeIfAbsent(key, ignored -> new ArrayList<>()).add(plan);
            }
        }

        Map<String, Integer> fieldNames = new HashMap<>();
        fieldNames.put("jdbcClient", 1);
        List<MapperDependency> dependencies = new ArrayList<>(groupedPlans.size());
        for (Map.Entry<MapperDependencyKey, List<JdbcMethodPlan>> entry : groupedPlans.entrySet()) {
            MapperDependencyKey key = entry.getKey();
            List<JdbcMethodPlan> mappedPlans = entry.getValue();
            TypeName mapperContract = TypeName.builder(JdbcCodegenTypes.ROW_MAPPER)
                    .addTypeArgument(key.mappedType())
                    .build();
            String baseName = lowerCamel(key.explicit() ? key.serviceType().className() : key.mappedType().className())
                    + (key.explicit() ? "" : "RowMapper");
            String fieldName = uniqueVariable(baseName, fieldNames);
            String fallbackFieldName = "";
            boolean optional = key.optional();

            if (optional) {
                fallbackFieldName = "DEFAULT_" + constantCase(fieldName);
                JdbcRecordMapperGenerator.generate(mappedPlans.getFirst(), fallbackFieldName, classModel, context);
            }
            classModel.addField(field -> field.name(fieldName)
                    .type(mapperContract)
                    .isFinal(true));
            mappedPlans.forEach(plan -> plan.mapperFieldName(fieldName));

            TypeName parameterType;
            if (key.explicit()) {
                parameterType = key.serviceType();
            } else if (optional) {
                parameterType = TypeName.builder(JdbcCodegenTypes.OPTIONAL)
                        .addTypeArgument(mapperContract)
                        .build();
            } else {
                parameterType = mapperContract;
            }
            dependencies.add(new MapperDependency(parameterType,
                                                   fieldName,
                                                   fieldName,
                                                   optional,
                                                   fallbackFieldName));
        }
        return dependencies;
    }

    private static void validateExplicitReducer(JdbcMethodPlan plan, CodegenContext context) {
        TypeName reducerType = plan.explicitReducer();
        TypeInfo reducerInfo = context.typeInfo(reducerType)
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Reducer type information is unavailable: "
                                                                  + reducerType.resolvedName()));
        boolean samePackage = samePackage(plan.method(), reducerType);
        if (reducerInfo.kind() != ElementKind.CLASS
                || reducerInfo.elementModifiers().contains(Modifier.ABSTRACT)) {
            throw JdbcMethodPlan.failure(plan.method(), "Reducer must be a concrete class: "
                    + reducerType.resolvedName());
        }
        if (!reducerType.enclosingNames().isEmpty() && !reducerInfo.elementModifiers().contains(Modifier.STATIC)) {
            throw JdbcMethodPlan.failure(plan.method(), "Reducer must not be a non-static nested class: "
                    + reducerType.resolvedName());
        }
        if (reducerInfo.accessModifier() != AccessModifier.PUBLIC
                && !(samePackage && (reducerInfo.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || reducerInfo.accessModifier() == AccessModifier.PROTECTED))) {
            throw JdbcMethodPlan.failure(plan.method(), "Reducer is not accessible to generated code: "
                    + reducerType.resolvedName());
        }
        boolean constructor = reducerInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.CONSTRUCTOR)
                .filter(element -> element.parameterArguments().isEmpty())
                .anyMatch(element -> element.accessModifier() == AccessModifier.PUBLIC
                        || (samePackage && (element.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                        || element.accessModifier() == AccessModifier.PROTECTED)));
        if (!constructor) {
            throw JdbcMethodPlan.failure(plan.method(), "Reducer requires an accessible no-argument constructor: "
                    + reducerType.resolvedName());
        }
        TypeName reducerInterface = findImplementedInterface(reducerInfo, JdbcCodegenTypes.ROW_REDUCER);
        if (reducerInterface == null
                || reducerInterface.typeArguments().size() != 1
                || !reducerInterface.typeArguments().getFirst().equals(plan.method().typeName())) {
            throw JdbcMethodPlan.failure(plan.method(), "Reducer must implement JdbcClient.RowReducer<"
                    + plan.method().typeName().resolvedName() + "> directly or through its type hierarchy");
        }
    }

    private static TypeName findImplementedInterface(TypeInfo typeInfo, TypeName contract) {
        for (TypeInfo interfaceInfo : typeInfo.interfaceTypeInfo()) {
            if (interfaceInfo.typeName().genericTypeName().equals(contract)) {
                return interfaceInfo.typeName();
            }
            TypeName nested = findImplementedInterface(interfaceInfo, contract);
            if (nested != null) {
                return nested;
            }
        }
        return typeInfo.superTypeInfo()
                .map(superType -> findImplementedInterface(superType, contract))
                .orElse(null);
    }

    private static void generateMethod(JdbcMethodPlan plan, Method.Builder method) {
        method.name(plan.method().elementName())
                .returnType(plan.method().typeName())
                .addAnnotation(Annotation.create(Override.class));
        plan.method().parameterArguments()
                .forEach(parameter -> method.addParameter(Parameter.builder()
                                                            .name(parameter.elementName())
                                                            .type(parameter.typeName())
                                                            .build()));
        plan.method().throwsChecked().forEach(method::addThrows);
        for (TypeName txAnnotation : JdbcCodegenTypes.TX_ANNOTATIONS) {
            plan.method().findAnnotation(txAnnotation).ifPresent(method::addAnnotation);
        }

        if (plan.operation() == JdbcMethodPlan.Operation.CALL && plan.callResultPlan().detached()) {
            generateDetachedCall(plan, method);
            return;
        }

        boolean returnsValue = !plan.method().typeName().equals(TypeNames.PRIMITIVE_VOID);
        boolean intUpdate = plan.operation() == JdbcMethodPlan.Operation.UPDATE
                && plan.method().typeName().equals(TypeNames.PRIMITIVE_INT);
        if (returnsValue) {
            method.addContent("return ");
            if (intUpdate) {
                // JDBC reports an update count as long. Keep the narrower repository contract checked instead of truncating.
                method.addContent(Math.class)
                        .addContent(".toIntExact(");
            }
        }
        method.addContent("jdbcClient.create(")
                .addContent(plan.sqlFieldName())
                .addContent(")");
        if (plan.optionsParameter() != null) {
            method.addContent(".options(")
                    .addContent(plan.optionsParameter().elementName())
                    .addContent(")");
        }
        if (plan.operation() == JdbcMethodPlan.Operation.CALL) {
            for (JdbcCallParameterPlan.Bind bind : plan.callParameterPlan().binds()) {
                addBind(method, bind.position(), bind.parameter());
            }
        } else {
            for (JdbcSqlParameterPlan.Bind bind : plan.parameterPlan().binds()) {
                addBind(method, bind.position(), bind.parameter());
            }
        }

        if (plan.operation() == JdbcMethodPlan.Operation.CALL) {
            method.addContent(".call(")
                    .addContent(plan.callFieldName());
            if (plan.requestParameter() != null) {
                method.addContent(", ").addContent(plan.requestParameter().elementName());
            }
            method.addContentLine(");");
            return;
        }

        if (plan.operation() == JdbcMethodPlan.Operation.UPDATE) {
            method.addContent(".execute()");
            if (intUpdate) {
                method.addContent(")");
            }
            method.addContentLine(";");
            return;
        }
        if (plan.mappingKind() == JdbcMethodPlan.MappingKind.REDUCER) {
            method.addContent(".reduce(new ")
                    .addContent(plan.explicitReducer())
                    .addContent("()");
            addReducerRequest(method);
            return;
        }
        if (plan.mappingKind() == JdbcMethodPlan.MappingKind.IDENTITY_REDUCTION) {
            method.addContent(".reduce(new ")
                    .addContent(plan.mapperFieldName())
                    .addContent("()");
            addReducerRequest(method);
            return;
        }
        addMappingStage(plan, method);
        addTerminal(plan, method);
    }

    private static void generateDetachedCall(JdbcMethodPlan plan, Method.Builder method) {
        String outputVariable = localName(plan, "callOutputValues");
        method.addContent(JdbcCodegenTypes.JDBC_CALL_OUTPUT_VALUES)
                .addContent(" ")
                .addContent(outputVariable)
                .addContent(" = jdbcClient.create(")
                .addContent(plan.sqlFieldName())
                .addContent(")");
        if (plan.optionsParameter() != null) {
            method.addContent(".options(")
                    .addContent(plan.optionsParameter().elementName())
                    .addContent(")");
        }
        for (JdbcCallParameterPlan.Bind bind : plan.callParameterPlan().binds()) {
            addBind(method, bind.position(), bind.parameter());
        }
        method.addContent(".callForOutputs(")
                .addContent(plan.callFieldName())
                .addContentLine(");");

        JdbcCallResultPlan result = plan.callResultPlan();
        method.addContent("return ");
        if (result.kind() == JdbcCallResultPlan.Kind.RECORD) {
            method.addContent("new ")
                    .addContent(result.resultType())
                    .addContent("(");
            for (int i = 0; i < result.components().size(); i++) {
                if (i > 0) {
                    method.addContent(", ");
                }
                addOutputRead(method, outputVariable, result.components().get(i));
            }
            method.addContentLine(");");
            return;
        }
        addOutputRead(method, outputVariable, result.components().getFirst());
        method.addContentLine(";");
    }

    private static void addOutputRead(Method.Builder method,
                                      String outputVariable,
                                      JdbcCallResultPlan.Component component) {
        method.addContent(outputVariable)
                .addContent(component.optional() ? ".optional(" : ".required(")
                .addContentLiteral(component.name())
                .addContent(", ")
                .addContent(component.valueType().boxed())
                .addContent(".class)");
    }

    private static String localName(JdbcMethodPlan plan, String base) {
        Set<String> parameterNames = plan.method().parameterArguments()
                .stream()
                .map(TypedElementInfo::elementName)
                .collect(Collectors.toSet());
        String candidate = base;
        int suffix = 2;
        while (parameterNames.contains(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private static void addBind(Method.Builder method, int position, TypedElementInfo parameter) {
        method.addContent(".bind(")
                .addContent(String.valueOf(position))
                .addContent(", ")
                .addContent(parameter.elementName())
                .addContent(")");
    }

    private static void addMappingStage(JdbcMethodPlan plan, Method.Builder method) {
        if (plan.operation() == JdbcMethodPlan.Operation.GENERATED_KEYS) {
            if (plan.mappingKind() == JdbcMethodPlan.MappingKind.SCALAR) {
                JdbcScalarMapperGenerator.addGeneratedKeyMapping(method,
                                                                 plan.mappedType(),
                                                                 plan.generatedColumns());
            } else {
                method.addContent(".generatedKeys(")
                        .addContent(plan.mapperFieldName());
                JdbcScalarMapperGenerator.addColumnNames(method, plan.generatedColumns());
                method.addContent(")");
            }
        } else if (plan.mappingKind() == JdbcMethodPlan.MappingKind.SCALAR) {
            JdbcScalarMapperGenerator.addQueryMapping(method, plan.mappedType());
        } else {
            method.addContent(".map(")
                    .addContent(plan.mapperFieldName())
                    .addContent(")");
        }
    }

    private static void addTerminal(JdbcMethodPlan plan, Method.Builder method) {
        switch (plan.returnShape()) {
        case ITEM -> addTerminal(method, "one");
        case OPTIONAL -> addTerminal(method, "optional");
        case LIST -> addTerminal(method, "list");
        case VISIT_ALL -> method.addContent(".visitAll(")
                .addContent(plan.requestParameter().elementName())
                .addContentLine(");");
        case VISIT_WHILE -> method.addContent(".visitWhile(")
                .addContent(plan.requestParameter().elementName())
                .addContentLine(");");
        default -> throw new AssertionError("Unknown JDBC return shape: " + plan.returnShape());
        }
    }

    private static void addTerminal(Method.Builder method, String terminal) {
        method.addContent(".")
                .addContent(terminal)
                .addContentLine("();");
    }

    private static void addReducerRequest(Method.Builder method) {
        method.addContentLine(");");
    }

    private static String uniqueSuffix(String methodName, Map<String, Integer> names) {
        String base = constantCase(methodName);
        int count = names.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }

    private static String uniqueVariable(String baseName, Map<String, Integer> names) {
        int count = names.merge(baseName, 1, Integer::sum);
        return count == 1 ? baseName : baseName + count;
    }

    private static String lowerCamel(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String constantCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (i > 0 && Character.isUpperCase(current) && Character.isLowerCase(value.charAt(i - 1))) {
                result.append('_');
            }
            result.append(Character.toUpperCase(current));
        }
        return result.toString();
    }

    private static String mixedCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (String part : value.toLowerCase(Locale.ROOT).split("_")) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private static boolean samePackage(TypedElementInfo method, TypeName type) {
        return method.enclosingType()
                .map(TypeName::packageName)
                .filter(type.packageName()::equals)
                .isPresent();
    }

    /**
     * One statically typed mapper injection point and its repository field.
     *
     * @param parameterType injected service type, optionally wrapped in {@code Optional}
     * @param parameterName generated constructor parameter name
     * @param fieldName generated effective mapper field name
     * @param optional whether a missing service uses the generated record mapper
     * @param fallbackFieldName generated record-mapper field, or an empty string when the service is required
     */
    record MapperDependency(TypeName parameterType,
                            String parameterName,
                            String fieldName,
                            boolean optional,
                            String fallbackFieldName) {
    }

    /**
     * Groups repository methods that share one exact mapper-service resolution.
     *
     * @param serviceType explicit mapper implementation type, or mapped type for automatic selection
     * @param mappedType mapper result type
     * @param explicit whether {@code Jdbc.RowMapper} selected the service type
     * @param optional whether record mapping supplies a generated fallback
     */
    private record MapperDependencyKey(TypeName serviceType, TypeName mappedType, boolean explicit, boolean optional) {
    }
}
