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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final TypeName JDBC_TYPE = TypeName.create("java.sql.JDBCType");

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
        for (TypedElementInfo method : methods) {
            JdbcMethodPlan plan = JdbcMethodPlan.create(method, context);
            String suffix = uniqueSuffix(method.elementName(), generatedNames);
            plan.sqlFieldName("SQL_" + suffix);
            plan.mapperFieldName("MAPPER_" + suffix);

            classModel.addField(field -> field.name(plan.sqlFieldName())
                    .type(TypeNames.STRING)
                    .isStatic(true)
                    .isFinal(true)
                    .addContentLiteral(plan.parameterPlan().sql()));
            generateMapping(plan, classModel, context, suffix);
            classModel.addMethod(builder -> generateMethod(plan, builder));
        }
    }

    private static void generateMapping(JdbcMethodPlan plan,
                                        ClassModel.Builder classModel,
                                        CodegenContext context,
                                        String suffix) {
        switch (plan.mappingKind()) {
        case NONE, SCALAR -> {
            // Scalar results use JdbcClient's fixed codec and do not need generated mapper state.
        }
        case RECORD -> JdbcRecordMapperGenerator.generate(plan, classModel, context);
        case BEAN -> JdbcBeanMapperGenerator.generate(plan, classModel, context);
        case EXPLICIT -> generateExplicitMapper(plan, classModel, context);
        case REDUCER -> validateExplicitReducer(plan, context);
        case GRAPH -> {
            plan.mapperFieldName("Reducer_" + mixedCase(suffix));
            JdbcGraphReducerGenerator.generate(plan, classModel, context);
        }
        default -> throw new AssertionError("Unknown JDBC mapping kind: " + plan.mappingKind());
        }
    }

    private static void generateExplicitMapper(JdbcMethodPlan plan,
                                               ClassModel.Builder classModel,
                                               CodegenContext context) {
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
        boolean constructor = mapperInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.CONSTRUCTOR)
                .filter(element -> element.parameterArguments().isEmpty())
                .anyMatch(element -> element.accessModifier() == AccessModifier.PUBLIC
                        || (samePackage && (element.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                        || element.accessModifier() == AccessModifier.PROTECTED)));
        if (!constructor) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper requires an accessible no-argument constructor: "
                    + mapperType.resolvedName());
        }
        TypeName mappedInterface = findImplementedInterface(mapperInfo, JdbcCodegenTypes.ROW_MAPPER);
        if (mappedInterface == null
                || mappedInterface.typeArguments().size() != 1
                || !mappedInterface.typeArguments().getFirst().equals(plan.mappedType())) {
            throw JdbcMethodPlan.failure(plan.method(), "Mapper must implement JdbcClient.RowMapper<"
                    + plan.mappedType().resolvedName() + "> directly or through its type hierarchy");
        }
        classModel.addField(field -> field.name(plan.mapperFieldName())
                .type(mapperType)
                .isStatic(true)
                .isFinal(true)
                .addContent("new ")
                .addContent(mapperType)
                .addContent("()"));
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

        boolean returnsValue = !plan.method().typeName().equals(TypeNames.PRIMITIVE_VOID);
        if (returnsValue) {
            method.addContent("return ");
        }
        method.addContent("jdbcClient.create(")
                .addContent(plan.sqlFieldName())
                .addContent(")");
        for (JdbcSqlParameterPlan.Bind bind : plan.parameterPlan().binds()) {
            method.addContent(".bind(")
                    .addContent(String.valueOf(bind.position()))
                    .addContent(", ")
                    .addContent(bind.parameter().elementName());
            if (bind.typed()) {
                method.addContent(", ")
                        .addContent(JDBC_TYPE)
                        .addContent(".")
                        .addContent(bind.jdbcType());
            }
            method.addContent(")");
        }

        if (plan.operation() == JdbcMethodPlan.Operation.UPDATE) {
            method.addContentLine(".execute();");
            return;
        }
        if (plan.mappingKind() == JdbcMethodPlan.MappingKind.REDUCER) {
            method.addContent(".reduce(new ")
                    .addContent(plan.explicitReducer())
                    .addContent("()");
            addReducerRequest(plan, method);
            return;
        }
        if (plan.mappingKind() == JdbcMethodPlan.MappingKind.GRAPH) {
            method.addContent(".reduce(new ")
                    .addContent(plan.mapperFieldName())
                    .addContent("()");
            addReducerRequest(plan, method);
            return;
        }
        addMappingStage(plan, method);
        addTerminal(plan, method);
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
        String requestArgument = plan.requestKind() == JdbcMethodPlan.RequestKind.REGULAR
                ? plan.requestParameter().elementName()
                : null;
        switch (plan.returnShape()) {
        case ITEM -> addTerminal(method, "one", requestArgument);
        case OPTIONAL -> addTerminal(method, "optional", requestArgument);
        case LIST -> addTerminal(method, "list", requestArgument);
        case VISIT_ALL -> method.addContent(".visitAll(")
                .addContent(plan.requestParameter().elementName())
                .addContentLine(");");
        case VISIT_WHILE -> method.addContent(".visitWhile(")
                .addContent(plan.requestParameter().elementName())
                .addContentLine(");");
        default -> throw new AssertionError("Unknown JDBC return shape: " + plan.returnShape());
        }
    }

    private static void addTerminal(Method.Builder method, String terminal, String requestArgument) {
        method.addContent(".")
                .addContent(terminal)
                .addContent("(");
        if (requestArgument != null) {
            method.addContent(requestArgument);
        }
        method.addContentLine(");");
    }

    private static void addReducerRequest(JdbcMethodPlan plan, Method.Builder method) {
        if (plan.requestKind() == JdbcMethodPlan.RequestKind.REGULAR) {
            method.addContent(", ")
                    .addContent(plan.requestParameter().elementName());
        }
        method.addContentLine(");");
    }

    private static String uniqueSuffix(String methodName, Map<String, Integer> names) {
        String base = constantCase(methodName);
        int count = names.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
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
}
