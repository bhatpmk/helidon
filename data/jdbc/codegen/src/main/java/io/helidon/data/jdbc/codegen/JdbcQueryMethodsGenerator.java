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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.classmodel.Annotation;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.BaseGenerator;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;

import static io.helidon.data.jdbc.codegen.JdbcRepositoryClassGenerator.methodError;
import static io.helidon.data.jdbc.codegen.JdbcTypes.QUERY_ANNOTATION;

final class JdbcQueryMethodsGenerator extends BaseGenerator {

    private static final TypeName PAGE = TypeName.create("io.helidon.data.Page");
    private static final TypeName PAGE_REQUEST = TypeName.create("io.helidon.data.PageRequest");
    private static final TypeName SLICE = TypeName.create("io.helidon.data.Slice");
    private static final TypeName SORT = TypeName.create("io.helidon.data.Sort");
    private static final TypeName STREAM = TypeName.create(Stream.class);

    private final ClassModel.Builder classModel;
    private final List<TypedElementInfo> methods;
    private final JdbcStatementGenerator statements;

    private JdbcQueryMethodsGenerator(RepositoryInfo repositoryInfo,
                                      ClassModel.Builder classModel,
                                      CodegenContext codegenContext) {
        this.classModel = classModel;
        this.methods = repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(method -> method.kind() == ElementKind.METHOD)
                .filter(method -> method.findAnnotation(QUERY_ANNOTATION).isPresent())
                .toList();
        this.statements = new JdbcStatementGenerator(codegenContext, repositoryInfo);
    }

    static void generate(RepositoryInfo repositoryInfo,
                         ClassModel.Builder classModel,
                         CodegenContext codegenContext) {
        new JdbcQueryMethodsGenerator(repositoryInfo, classModel, codegenContext).generate();
    }

    private void generate() {
        methods.forEach(this::addGeneratedMethod);
    }

    private void addGeneratedMethod(TypedElementInfo methodInfo) {
        try {
            classModel.addMethod(builder -> generateMethod(builder, methodInfo));
        } catch (CodegenException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() != null && !e.getMessage().isEmpty()
                    ? e.getMessage()
                    : "Code generation of JDBC @Data.Query annotated method failed";
            throw new CodegenException(message, e, methodInfo.originatingElement());
        }
    }

    private void generateMethod(Method.Builder builder, TypedElementInfo methodInfo) {
        generateHeader(builder, methodInfo);
        validateParameters(methodInfo);

        String sql = methodInfo.annotation(QUERY_ANNOTATION)
                .value()
                .orElseThrow(() -> new CodegenException("@Data.Query annotation value is missing",
                                                        methodInfo.originatingElement()));

        List<PersistenceGenerator.QuerySettings> settings = settings(methodInfo, JdbcSqlParameters.parse(sql));
        TypeName returnType = methodInfo.typeName();
        if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            statement(builder, b -> statements.addDirectUpdateCount(b, sql, settings));
        } else if (isStream(returnType)) {
            returnStatement(builder,
                            b -> statements.addDirectQueryStream(b, sql, settings, genericReturnTypeArgument(methodInfo)));
        } else if (isListOrCollection(returnType)) {
            returnStatement(builder,
                            b -> statements.addDirectQueryList(b, sql, settings, genericReturnTypeArgument(methodInfo)));
        } else if (returnType.isOptional()) {
            returnStatement(builder,
                            b -> statements.addDirectQueryOptional(b, sql, settings, genericReturnTypeArgument(methodInfo)));
        } else if (isSliceOrPage(returnType)) {
            throw methodError(methodInfo, "JDBC @Data.Query methods do not support Page or Slice return types yet");
        } else {
            returnStatement(builder, b -> statements.addDirectQueryItem(b, sql, settings, returnType));
        }
    }

    private static void generateHeader(Method.Builder builder, TypedElementInfo methodInfo) {
        builder.name(methodInfo.elementName())
                .returnType(methodInfo.typeName())
                .addAnnotation(Annotation.create(Override.class));
        methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .forEach(parameterInfo -> builder.addParameter(Parameter.builder()
                                                                       .name(parameterInfo.elementName())
                                                                       .type(parameterInfo.typeName())
                                                                       .build()));
    }

    private static void validateParameters(TypedElementInfo methodInfo) {
        methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.typeName().equals(SORT) || parameter.typeName().equals(PAGE_REQUEST))
                .findFirst()
                .ifPresent(parameter -> {
                    throw methodError(methodInfo, "JDBC @Data.Query methods do not support "
                            + parameter.typeName().className() + " parameters yet");
                });
    }

    private static List<PersistenceGenerator.QuerySettings> settings(TypedElementInfo methodInfo,
                                                                     JdbcSqlParameters parameters) {
        List<TypedElementInfo> methodParameters = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .toList();
        return switch (parameters.mode()) {
        case NONE -> {
            if (!methodParameters.isEmpty()) {
                throw methodError(methodInfo, "JDBC SQL declares no parameters, but method has parameters: "
                        + methodInfo.elementName());
            }
            yield List.of();
        }
        case NAMED -> namedSettings(methodInfo, methodParameters, parameters.names());
        case POSITIONAL -> positionalSettings(methodInfo, methodParameters, parameters.count());
        case ORDINAL -> ordinalSettings(methodInfo, methodParameters, parameters.indexes());
        };
    }

    private static List<PersistenceGenerator.QuerySettings> namedSettings(TypedElementInfo methodInfo,
                                                                          List<TypedElementInfo> methodParameters,
                                                                          List<String> names) {
        Set<String> sqlNames = new HashSet<>(names);
        List<PersistenceGenerator.QuerySettings> settings = new ArrayList<>(methodParameters.size());
        for (TypedElementInfo methodParameter : methodParameters) {
            String parameterName = methodParameter.elementName();
            if (!sqlNames.contains(parameterName)) {
                throw methodError(methodInfo, "JDBC method parameter is not used by SQL markers: "
                        + parameterName + " in method " + methodInfo.elementName());
            }
            settings.add(() -> "io.helidon.data.jdbc.JdbcParameter.create(\""
                    + parameterName
                    + "\", "
                    + parameterName
                    + ")");
        }
        for (String sqlName : sqlNames) {
            if (methodParameters.stream().noneMatch(parameter -> parameter.elementName().equals(sqlName))) {
                throw methodError(methodInfo, "JDBC SQL parameter :"
                        + sqlName
                        + " has no matching method parameter in method "
                        + methodInfo.elementName());
            }
        }
        return List.copyOf(settings);
    }

    private static List<PersistenceGenerator.QuerySettings> positionalSettings(TypedElementInfo methodInfo,
                                                                               List<TypedElementInfo> methodParameters,
                                                                               int count) {
        if (methodParameters.size() != count) {
            throw methodError(methodInfo, "JDBC SQL declares "
                    + count
                    + " positional parameters, but method has "
                    + methodParameters.size()
                    + " parameters: "
                    + methodInfo.elementName());
        }
        return methodParameters.stream()
                .map(parameter -> (PersistenceGenerator.QuerySettings) () -> "io.helidon.data.jdbc.JdbcParameter.create("
                        + parameter.elementName()
                        + ")")
                .toList();
    }

    private static List<PersistenceGenerator.QuerySettings> ordinalSettings(TypedElementInfo methodInfo,
                                                                            List<TypedElementInfo> methodParameters,
                                                                            List<Integer> indexes) {
        int max = indexes.stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (max > methodParameters.size()) {
            throw methodError(methodInfo, "JDBC SQL ordinal parameter index "
                    + max
                    + " has no matching method parameter in method "
                    + methodInfo.elementName());
        }
        if (methodParameters.size() != new HashSet<>(indexes).size()) {
            throw methodError(methodInfo, "JDBC ordinal SQL parameters must use every method parameter exactly once: "
                    + methodInfo.elementName());
        }
        return methodParameters.stream()
                .map(parameter -> (PersistenceGenerator.QuerySettings) () -> "io.helidon.data.jdbc.JdbcParameter.create("
                        + parameter.elementName()
                        + ")")
                .toList();
    }

    private static TypeName genericReturnTypeArgument(TypedElementInfo methodInfo) {
        List<TypeName> genericArguments = methodInfo.typeName().typeArguments();
        if (genericArguments == null || genericArguments.isEmpty()) {
            throw methodError(methodInfo, "Missing generic argument of method "
                    + methodInfo.elementName()
                    + " with return type "
                    + methodInfo.typeName());
        }
        return genericArguments.getFirst();
    }

    private static boolean isListOrCollection(TypeName typeName) {
        return typeName.isList() || TypeNames.COLLECTION.equals(typeName);
    }

    private static boolean isStream(TypeName typeName) {
        return STREAM.equals(typeName);
    }

    private static boolean isSliceOrPage(TypeName typeName) {
        return SLICE.equals(typeName) || PAGE.equals(typeName);
    }
}
