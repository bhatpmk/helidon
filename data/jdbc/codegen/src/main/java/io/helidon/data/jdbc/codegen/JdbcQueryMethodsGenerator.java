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
import java.util.LinkedHashSet;
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
import static io.helidon.data.jdbc.codegen.JdbcTypes.CALL_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.IN_OUT_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.OUT_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.OUT_CURSOR_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.OUT_CURSOR_LIST_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.OUT_LIST_ANNOTATION;
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
                .filter(method -> method.findAnnotation(QUERY_ANNOTATION).isPresent()
                        || method.findAnnotation(CALL_ANNOTATION).isPresent())
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
                    : "Code generation of JDBC repository method failed";
            throw new CodegenException(message, e, methodInfo.originatingElement());
        }
    }

    private void generateMethod(Method.Builder builder, TypedElementInfo methodInfo) {
        generateHeader(builder, methodInfo);
        validateParameters(methodInfo);
        boolean hasQuery = methodInfo.findAnnotation(QUERY_ANNOTATION).isPresent();
        boolean hasCall = methodInfo.findAnnotation(CALL_ANNOTATION).isPresent();
        if (hasQuery && hasCall) {
            throw methodError(methodInfo, "JDBC repository method cannot use both @Data.Query and @Data.Call: "
                    + methodInfo.elementName());
        }
        if (hasCall) {
            generateCallMethod(builder, methodInfo);
            return;
        }

        generateQueryMethod(builder, methodInfo);
    }

    private void generateQueryMethod(Method.Builder builder, TypedElementInfo methodInfo) {
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

    private void generateCallMethod(Method.Builder builder, TypedElementInfo methodInfo) {
        String sql = methodInfo.annotation(CALL_ANNOTATION)
                .value()
                .orElseThrow(() -> new CodegenException("@Data.Call annotation value is missing",
                                                        methodInfo.originatingElement()));

        List<JdbcCallParameter> outParameters = outParameters(methodInfo);
        List<PersistenceGenerator.QuerySettings> settings = callSettings(methodInfo,
                                                                         JdbcSqlParameters.parse(sql),
                                                                         outParameters);
        TypeName returnType = methodInfo.typeName();
        if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            statement(builder, b -> statements.addDirectCallVoid(b, sql, settings, outParameters));
        } else if (returnType.isMap()) {
            if (outParameters.stream().anyMatch(JdbcCallParameter::cursor)) {
                throw methodError(methodInfo, "JDBC @Data.Call methods returning Map cannot declare @Data.OutCursor: "
                        + methodInfo.elementName());
            }
            returnStatement(builder, b -> statements.addDirectCallOutParams(b, sql, settings, outParameters));
        } else if (isStream(returnType)) {
            JdbcCallParameter cursor = singleCursor(methodInfo, outParameters);
            returnStatement(builder,
                            b -> statements.addDirectCallOutCursorStream(b,
                                                                         sql,
                                                                         settings,
                                                                         outParameters,
                                                                         cursor.name(),
                                                                         genericReturnTypeArgument(methodInfo)));
        } else if (isListOrCollection(returnType)) {
            JdbcCallParameter cursor = singleCursor(methodInfo, outParameters);
            returnStatement(builder,
                            b -> statements.addDirectCallOutCursor(b,
                                                                   sql,
                                                                   settings,
                                                                   outParameters,
                                                                   cursor.name(),
                                                                   genericReturnTypeArgument(methodInfo)));
        } else if (returnType.isOptional()) {
            JdbcCallParameter scalar = singleScalar(methodInfo, outParameters);
            returnStatement(builder,
                            b -> statements.addDirectCallOptionalOutParam(b,
                                                                         sql,
                                                                         settings,
                                                                         outParameters,
                                                                         scalar.name(),
                                                                         genericReturnTypeArgument(methodInfo)));
        } else if (isSliceOrPage(returnType)) {
            throw methodError(methodInfo, "JDBC @Data.Call methods do not support Page or Slice return types yet");
        } else if (JdbcTypes.isScalar(returnType)) {
            JdbcCallParameter scalar = singleScalar(methodInfo, outParameters);
            returnStatement(builder,
                            b -> statements.addDirectCallOutParam(b,
                                                                  sql,
                                                                  settings,
                                                                  outParameters,
                                                                  scalar.name(),
                                                                  returnType));
        } else {
            throw methodError(methodInfo, "JDBC @Data.Call methods can return void, Map, a scalar OUT value, "
                    + "Optional scalar OUT value, List cursor rows, or Stream cursor rows: " + methodInfo.elementName());
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
                    throw methodError(methodInfo, "JDBC repository methods do not support "
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

    private static List<JdbcCallParameter> outParameters(TypedElementInfo methodInfo) {
        List<JdbcCallParameter> parameters = new ArrayList<>();
        for (io.helidon.common.types.Annotation annotation : annotations(methodInfo, OUT_ANNOTATION, OUT_LIST_ANNOTATION)) {
            int index = requiredInt(methodInfo, annotation, "index", "@Data.Out");
            parameters.add(new JdbcCallParameter(index,
                                                 outputName(annotation, String.valueOf(index)),
                                                 requiredInt(methodInfo, annotation, "type", "@Data.Out"),
                                                 false,
                                                 false));
        }
        methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .forEach(parameter -> parameter.findAnnotation(IN_OUT_ANNOTATION)
                        .ifPresent(annotation -> {
                            int index = requiredInt(methodInfo, annotation, "index", "@Data.InOut");
                            parameters.add(new JdbcCallParameter(index,
                                                                 outputName(annotation, parameter.elementName()),
                                                                 requiredInt(methodInfo, annotation, "type", "@Data.InOut"),
                                                                 false,
                                                                 true));
                        }));
        for (io.helidon.common.types.Annotation annotation : annotations(methodInfo,
                                                                        OUT_CURSOR_ANNOTATION,
                                                                        OUT_CURSOR_LIST_ANNOTATION)) {
            int index = requiredInt(methodInfo, annotation, "index", "@Data.OutCursor");
            parameters.add(new JdbcCallParameter(index,
                                                 outputName(annotation, String.valueOf(index)),
                                                 annotation.intValue("type").orElse(2012),
                                                 true,
                                                 false));
        }
        validateOutParameters(methodInfo, parameters);
        return parameters.stream()
                .sorted(java.util.Comparator.comparingInt(JdbcCallParameter::index))
                .toList();
    }

    private static List<io.helidon.common.types.Annotation> annotations(TypedElementInfo methodInfo,
                                                                        TypeName annotationType,
                                                                        TypeName containerType) {
        List<io.helidon.common.types.Annotation> result = new ArrayList<>();
        for (io.helidon.common.types.Annotation annotation : methodInfo.annotations()) {
            if (annotation.typeName().equals(annotationType)) {
                result.add(annotation);
            } else if (annotation.typeName().equals(containerType)) {
                Object value = annotation.objectValue("value")
                        .orElseThrow(() -> methodError(methodInfo,
                                                       "Missing repeatable annotation values for "
                                                               + annotationType.className()));
                if (value instanceof List<?> values) {
                    for (Object item : values) {
                        if (item instanceof io.helidon.common.types.Annotation nested) {
                            result.add(nested);
                        }
                    }
                } else if (value instanceof io.helidon.common.types.Annotation nested) {
                    result.add(nested);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void validateOutParameters(TypedElementInfo methodInfo, List<JdbcCallParameter> parameters) {
        Set<Integer> indexes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JdbcCallParameter parameter : parameters) {
            if (parameter.index() < 1) {
                throw methodError(methodInfo, "JDBC OUT parameter indexes start with 1: " + methodInfo.elementName());
            }
            if (!indexes.add(parameter.index())) {
                throw methodError(methodInfo, "JDBC @Data.Call declares duplicate OUT parameter index "
                        + parameter.index()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (!names.add(parameter.name())) {
                throw methodError(methodInfo, "JDBC @Data.Call declares duplicate OUT parameter name \""
                        + parameter.name()
                        + "\" in method "
                        + methodInfo.elementName());
            }
        }
    }

    private static int requiredInt(TypedElementInfo methodInfo,
                                   io.helidon.common.types.Annotation annotation,
                                   String property,
                                   String annotationName) {
        return annotation.intValue(property)
                .orElseThrow(() -> methodError(methodInfo,
                                               "Missing " + property + " property on " + annotationName
                                                       + " in method " + methodInfo.elementName()));
    }

    private static String outputName(io.helidon.common.types.Annotation annotation, String defaultName) {
        return annotation.stringValue("name")
                .filter(name -> !name.isBlank())
                .orElse(defaultName);
    }

    private static List<PersistenceGenerator.QuerySettings> callSettings(TypedElementInfo methodInfo,
                                                                         JdbcSqlParameters parameters,
                                                                         List<JdbcCallParameter> outParameters) {
        List<TypedElementInfo> methodParameters = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .toList();
        validateOutIndexes(methodInfo, parameters, outParameters);
        return switch (parameters.mode()) {
        case NONE -> {
            if (!methodParameters.isEmpty()) {
                throw methodError(methodInfo, "JDBC call declares no parameters, but method has parameters: "
                        + methodInfo.elementName());
            }
            yield List.of();
        }
        case NAMED -> namedCallSettings(methodInfo, methodParameters, parameters.names(), outParameters);
        case POSITIONAL -> positionalCallSettings(methodInfo, methodParameters, parameters.count(), outParameters);
        case ORDINAL -> ordinalCallSettings(methodInfo, methodParameters, parameters.indexes(), outParameters);
        };
    }

    private static void validateOutIndexes(TypedElementInfo methodInfo,
                                           JdbcSqlParameters parameters,
                                           List<JdbcCallParameter> outParameters) {
        if (parameters.mode() == JdbcSqlParameters.Mode.NONE && !outParameters.isEmpty()) {
            throw methodError(methodInfo, "JDBC @Data.Call OUT parameters require matching call placeholders: "
                    + methodInfo.elementName());
        }
        int markerCount = parameters.count();
        outParameters.stream()
                .filter(outParameter -> outParameter.index() > markerCount)
                .findFirst()
                .ifPresent(outParameter -> {
                    throw methodError(methodInfo, "JDBC OUT parameter index "
                            + outParameter.index()
                            + " is greater than the call placeholder count "
                            + markerCount
                            + " in method "
                            + methodInfo.elementName());
                });
    }

    private static List<PersistenceGenerator.QuerySettings> namedCallSettings(TypedElementInfo methodInfo,
                                                                              List<TypedElementInfo> methodParameters,
                                                                              List<String> names,
                                                                              List<JdbcCallParameter> outParameters) {
        Set<String> sqlNames = new HashSet<>(names);
        Set<String> methodNames = methodParameterNames(methodParameters);
        Set<Integer> pureOutIndexes = pureOutIndexes(outParameters);
        List<PersistenceGenerator.QuerySettings> settings = namedParameterSettings(methodParameters);
        for (TypedElementInfo methodParameter : methodParameters) {
            if (!sqlNames.contains(methodParameter.elementName())) {
                throw methodError(methodInfo, "JDBC method parameter is not used by call markers: "
                        + methodParameter.elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
        }
        for (int i = 0; i < names.size(); i++) {
            int jdbcIndex = i + 1;
            String name = names.get(i);
            if (pureOutIndexes.contains(jdbcIndex) && methodNames.contains(name)) {
                throw methodError(methodInfo, "JDBC pure OUT parameter at index "
                        + jdbcIndex
                        + " must not use method parameter marker :"
                        + name
                        + " in method "
                        + methodInfo.elementName());
            }
            if (!methodNames.contains(name) && !pureOutIndexes.contains(jdbcIndex)) {
                throw methodError(methodInfo, "JDBC call parameter :"
                        + name
                        + " has no matching method parameter or pure OUT annotation in method "
                        + methodInfo.elementName());
            }
        }
        return settings;
    }

    private static List<PersistenceGenerator.QuerySettings> positionalCallSettings(TypedElementInfo methodInfo,
                                                                                   List<TypedElementInfo> methodParameters,
                                                                                   int count,
                                                                                   List<JdbcCallParameter> outParameters) {
        int pureOutCount = pureOutIndexes(outParameters).size();
        int requiredInputs = count - pureOutCount;
        if (methodParameters.size() != requiredInputs) {
            throw methodError(methodInfo, "JDBC call declares "
                    + count
                    + " positional parameters with "
                    + pureOutCount
                    + " pure OUT parameters, but method has "
                    + methodParameters.size()
                    + " input parameters: "
                    + methodInfo.elementName());
        }
        return positionalParameterSettings(methodParameters);
    }

    private static List<PersistenceGenerator.QuerySettings> ordinalCallSettings(TypedElementInfo methodInfo,
                                                                                List<TypedElementInfo> methodParameters,
                                                                                List<Integer> indexes,
                                                                                List<JdbcCallParameter> outParameters) {
        Set<Integer> pureOutIndexes = pureOutIndexes(outParameters);
        Set<Integer> used = new LinkedHashSet<>();
        for (int i = 0; i < indexes.size(); i++) {
            int jdbcIndex = i + 1;
            int sourceIndex = indexes.get(i);
            if (pureOutIndexes.contains(jdbcIndex)) {
                if (sourceIndex <= methodParameters.size()) {
                    throw methodError(methodInfo, "JDBC pure OUT ordinal parameter at index "
                            + jdbcIndex
                            + " must reference an ordinal greater than the method parameter count in method "
                            + methodInfo.elementName());
                }
                continue;
            }
            if (sourceIndex > methodParameters.size()) {
                throw methodError(methodInfo, "JDBC call ordinal parameter ?"
                        + sourceIndex
                        + " has no matching method parameter in method "
                        + methodInfo.elementName());
            }
            used.add(sourceIndex);
        }
        for (int i = 1; i <= methodParameters.size(); i++) {
            if (!used.contains(i)) {
                throw methodError(methodInfo, "JDBC call ordinal parameters must use method parameter ?"
                        + i
                        + " in method "
                        + methodInfo.elementName());
            }
        }
        return positionalParameterSettings(methodParameters);
    }

    private static Set<Integer> pureOutIndexes(List<JdbcCallParameter> outParameters) {
        return outParameters.stream()
                .filter(outParameter -> !outParameter.inOut())
                .map(JdbcCallParameter::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> methodParameterNames(List<TypedElementInfo> methodParameters) {
        return methodParameters.stream()
                .map(TypedElementInfo::elementName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<PersistenceGenerator.QuerySettings> namedParameterSettings(List<TypedElementInfo> methodParameters) {
        return methodParameters.stream()
                .map(parameter -> (PersistenceGenerator.QuerySettings) () -> "io.helidon.data.jdbc.JdbcParameter.create(\""
                        + parameter.elementName()
                        + "\", "
                        + parameter.elementName()
                        + ")")
                .toList();
    }

    private static List<PersistenceGenerator.QuerySettings> positionalParameterSettings(List<TypedElementInfo> methodParameters) {
        return methodParameters.stream()
                .map(parameter -> (PersistenceGenerator.QuerySettings) () -> "io.helidon.data.jdbc.JdbcParameter.create("
                        + parameter.elementName()
                        + ")")
                .toList();
    }

    private static JdbcCallParameter singleScalar(TypedElementInfo methodInfo, List<JdbcCallParameter> outParameters) {
        List<JdbcCallParameter> scalar = outParameters.stream()
                .filter(outParameter -> !outParameter.cursor())
                .toList();
        if (scalar.size() != 1) {
            throw methodError(methodInfo, "JDBC @Data.Call scalar return methods must declare exactly one scalar "
                    + "OUT or INOUT parameter: " + methodInfo.elementName());
        }
        return scalar.getFirst();
    }

    private static JdbcCallParameter singleCursor(TypedElementInfo methodInfo, List<JdbcCallParameter> outParameters) {
        List<JdbcCallParameter> cursors = outParameters.stream()
                .filter(JdbcCallParameter::cursor)
                .toList();
        if (cursors.size() != 1) {
            throw methodError(methodInfo, "JDBC @Data.Call cursor return methods must declare exactly one "
                    + "@Data.OutCursor parameter: " + methodInfo.elementName());
        }
        return cursors.getFirst();
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
