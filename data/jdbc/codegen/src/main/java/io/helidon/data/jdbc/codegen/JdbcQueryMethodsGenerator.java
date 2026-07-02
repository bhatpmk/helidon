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
import java.util.function.Consumer;
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
import static io.helidon.data.jdbc.codegen.JdbcTypes.GENERATED_KEYS_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.IN_ANNOTATION;
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
    private static final TypeName CONSUMER = TypeName.create(Consumer.class);
    private static final TypeName ITERABLE = TypeName.create(Iterable.class);
    private static final String PAGE_OFFSET = "__helidon_page_offset";
    private static final String PAGE_SIZE = "__helidon_page_size";

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
        boolean hasGeneratedKeys = methodInfo.findAnnotation(GENERATED_KEYS_ANNOTATION).isPresent();
        boolean hasIn = methodInfo.parameterArguments()
                .stream()
                .anyMatch(parameter -> parameter.findAnnotation(IN_ANNOTATION).isPresent());
        if (hasQuery && hasCall) {
            throw methodError(methodInfo, "JDBC repository method cannot use both @Data.Query and @Data.Call: "
                    + methodInfo.elementName());
        }
        if (hasGeneratedKeys && !hasQuery) {
            throw methodError(methodInfo, "@Data.GeneratedKeys requires @Data.Query on JDBC repository method: "
                    + methodInfo.elementName());
        }
        if (hasIn && !hasCall) {
            throw methodError(methodInfo, "@Data.In applies only to @Data.Call method parameters: "
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

        TypeName returnType = methodInfo.typeName();
        StreamingCallback streamingCallback = streamingCallback(methodInfo);
        if (streamingCallback != null) {
            if (methodInfo.findAnnotation(GENERATED_KEYS_ANNOTATION).isPresent()) {
                throw methodError(methodInfo, "JDBC streaming methods cannot request generated keys: "
                        + methodInfo.elementName());
            }
            List<PersistenceGenerator.QuerySettings> settings = settings(methodInfo,
                                                                          streamingCallback.applicationParameters,
                                                                          JdbcSqlParameters.parse(sql));
            statement(builder,
                      b -> statements.addDirectWithRows(b,
                                                        sql,
                                                        settings,
                                                        streamingCallback.parameter.elementName(),
                                                        streamingCallback.rowType));
            return;
        }
        if (isSliceOrPage(returnType)) {
            if (methodInfo.findAnnotation(GENERATED_KEYS_ANNOTATION).isPresent()) {
                throw methodError(methodInfo, "JDBC @Data.GeneratedKeys methods cannot return Page or Slice: "
                        + methodInfo.elementName());
            }
            generatePaginationMethod(builder, methodInfo, sql, returnType);
            return;
        }

        List<PersistenceGenerator.QuerySettings> settings = settings(methodInfo, JdbcSqlParameters.parse(sql));
        if (methodInfo.findAnnotation(GENERATED_KEYS_ANNOTATION).isPresent()) {
            generateGeneratedKeysMethod(builder, methodInfo, sql, settings, returnType);
            return;
        }
        if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            statement(builder, b -> statements.addDirectDiscard(b, sql, settings));
        } else if (isStream(returnType)) {
            throw methodError(methodInfo,
                              "JDBC direct Stream<T> returns do not provide reliable resource ownership; use a "
                                      + "void method with a Consumer<Iterable<T>> parameter: "
                                      + methodInfo.elementName());
        } else if (isShapeAwareScalar(returnType)) {
            returnStatement(builder,
                            b -> statements.addDirectQueryScalar(b, sql, settings, JdbcTypes.wrapper(returnType)));
        } else if (isListOrCollection(returnType)) {
            returnStatement(builder,
                            b -> statements.addDirectQueryList(b, sql, settings, genericReturnTypeArgument(methodInfo)));
        } else if (returnType.isOptional()) {
            returnStatement(builder,
                            b -> statements.addDirectQueryOptional(b, sql, settings, genericReturnTypeArgument(methodInfo)));
        } else {
            returnStatement(builder, b -> statements.addDirectQueryItem(b, sql, settings, returnType));
        }
    }

    private void generatePaginationMethod(Method.Builder builder,
                                          TypedElementInfo methodInfo,
                                          String sql,
                                          TypeName returnType) {
        List<TypedElementInfo> pageRequests = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .filter(parameter -> parameter.typeName().equals(PAGE_REQUEST))
                .toList();
        if (pageRequests.size() != 1) {
            throw methodError(methodInfo, "JDBC Page and Slice methods require exactly one PageRequest parameter: "
                    + methodInfo.elementName());
        }

        List<TypedElementInfo> applicationParameters = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .filter(parameter -> !parameter.typeName().equals(PAGE_REQUEST))
                .toList();
        JdbcSqlParameters pageSql = JdbcSqlParameters.parse(sql);
        boolean page = PAGE.equals(returnType);
        List<String> applicationNames = validatePageSql(methodInfo, pageSql, applicationParameters, page);
        List<PersistenceGenerator.QuerySettings> settings = namedSettings(methodInfo,
                                                                          applicationParameters,
                                                                          applicationNames);
        String countSql = methodInfo.annotation(QUERY_ANNOTATION)
                .stringValue("count")
                .orElse("");
        TypeName rowType = genericReturnTypeArgument(methodInfo);
        String requestName = pageRequests.getFirst().elementName();
        if (page) {
            validateCountSql(methodInfo, countSql, applicationNames);
            returnStatement(builder,
                            b -> statements.addDirectPage(b,
                                                          sql,
                                                          countSql,
                                                          settings,
                                                          requestName,
                                                          rowType));
        } else {
            if (!countSql.isBlank()) {
                throw methodError(methodInfo, "JDBC Slice methods must not declare @Data.Query count SQL: "
                        + methodInfo.elementName());
            }
            returnStatement(builder,
                            b -> statements.addDirectSlice(b, sql, settings, requestName, rowType));
        }
    }

    private static List<String> validatePageSql(TypedElementInfo methodInfo,
                                                JdbcSqlParameters parameters,
                                                List<TypedElementInfo> applicationParameters,
                                                boolean page) {
        if (parameters.mode() != JdbcSqlParameters.Mode.NAMED) {
            throw methodError(methodInfo, "JDBC Page and Slice SQL must use named parameters: "
                    + methodInfo.elementName());
        }
        requireMarker(methodInfo, parameters.names(), PAGE_SIZE);
        long offsets = parameters.names().stream().filter(PAGE_OFFSET::equals).count();
        if (page && offsets != 1) {
            throw methodError(methodInfo, "JDBC Page SQL must contain exactly one :" + PAGE_OFFSET
                    + " parameter: " + methodInfo.elementName());
        }
        if (!page && offsets > 1) {
            throw methodError(methodInfo, "JDBC Slice SQL may contain at most one :" + PAGE_OFFSET
                    + " parameter: " + methodInfo.elementName());
        }

        Set<String> names = new LinkedHashSet<>(parameters.names());
        names.remove(PAGE_OFFSET);
        names.remove(PAGE_SIZE);
        Set<String> methodNames = applicationParameters.stream()
                .map(TypedElementInfo::elementName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!names.equals(methodNames)) {
            throw methodError(methodInfo, "JDBC page SQL parameters must match non-PageRequest method parameters; "
                    + "expected " + methodNames + " but SQL uses " + names + " in method " + methodInfo.elementName());
        }
        return List.copyOf(names);
    }

    private static void validateCountSql(TypedElementInfo methodInfo,
                                         String countSql,
                                         List<String> applicationNames) {
        if (countSql.isBlank()) {
            throw methodError(methodInfo, "JDBC Page methods require @Data.Query count SQL: "
                    + methodInfo.elementName());
        }
        JdbcSqlParameters parameters = JdbcSqlParameters.parse(countSql);
        if (!applicationNames.isEmpty() && parameters.mode() != JdbcSqlParameters.Mode.NAMED) {
            throw methodError(methodInfo, "JDBC Page count SQL must use named parameters: "
                    + methodInfo.elementName());
        }
        if (applicationNames.isEmpty() && parameters.mode() != JdbcSqlParameters.Mode.NONE) {
            throw methodError(methodInfo, "JDBC Page count SQL declares parameters but the method has none: "
                    + methodInfo.elementName());
        }
        Set<String> countNames = new LinkedHashSet<>(parameters.names());
        if (countNames.contains(PAGE_OFFSET) || countNames.contains(PAGE_SIZE)) {
            throw methodError(methodInfo, "JDBC Page count SQL must not contain pagination parameters: "
                    + methodInfo.elementName());
        }
        if (!countNames.equals(new LinkedHashSet<>(applicationNames))) {
            throw methodError(methodInfo, "JDBC Page SQL and count SQL must use the same application parameters: "
                    + methodInfo.elementName());
        }
    }

    private static void requireMarker(TypedElementInfo methodInfo, List<String> names, String marker) {
        long count = names.stream().filter(marker::equals).count();
        if (count != 1) {
            throw methodError(methodInfo, "JDBC pagination SQL must contain exactly one :" + marker
                    + " parameter: " + methodInfo.elementName());
        }
    }

    private void generateGeneratedKeysMethod(Method.Builder builder,
                                             TypedElementInfo methodInfo,
                                             String sql,
                                             List<PersistenceGenerator.QuerySettings> settings,
                                             TypeName returnType) {
        List<String> columns = generatedKeyColumns(methodInfo);
        if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            throw methodError(methodInfo, "JDBC @Data.GeneratedKeys method must return a generated key: "
                    + methodInfo.elementName());
        }
        if (isStream(returnType)) {
            throw methodError(methodInfo, "JDBC @Data.GeneratedKeys methods cannot return Stream: "
                    + methodInfo.elementName());
        }
        if (isSliceOrPage(returnType)) {
            throw methodError(methodInfo, "JDBC @Data.GeneratedKeys methods cannot return Page or Slice: "
                    + methodInfo.elementName());
        }
        if (returnType.isMap()) {
            throw methodError(methodInfo, "JDBC @Data.GeneratedKeys methods cannot return Map: "
                    + methodInfo.elementName());
        }
        if (isListOrCollection(returnType)) {
            returnStatement(builder,
                            b -> statements.addDirectGeneratedKeys(b,
                                                                    sql,
                                                                    settings,
                                                                    columns,
                                                                    genericReturnTypeArgument(methodInfo)));
        } else if (returnType.isOptional()) {
            returnStatement(builder,
                            b -> statements.addDirectOptionalGeneratedKey(b,
                                                                          sql,
                                                                          settings,
                                                                          columns,
                                                                          genericReturnTypeArgument(methodInfo)));
        } else {
            returnStatement(builder,
                            b -> statements.addDirectGeneratedKey(b, sql, settings, columns, returnType));
        }
    }

    private static List<String> generatedKeyColumns(TypedElementInfo methodInfo) {
        List<String> columns = methodInfo.annotation(GENERATED_KEYS_ANNOTATION)
                .stringValues("columns")
                .orElse(List.of());
        Set<String> unique = new LinkedHashSet<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw methodError(methodInfo, "@Data.GeneratedKeys column names must not be blank: "
                        + methodInfo.elementName());
            }
            if (!unique.add(column)) {
                throw methodError(methodInfo, "@Data.GeneratedKeys contains duplicate column name \""
                        + column
                        + "\" in method "
                        + methodInfo.elementName());
            }
        }
        return List.copyOf(unique);
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
            throw methodError(methodInfo,
                              "JDBC @Data.Call does not support direct Stream<T> returns: "
                                      + methodInfo.elementName());
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
                    + "Optional scalar OUT value, or List cursor rows: " + methodInfo.elementName());
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
                .filter(parameter -> parameter.typeName().equals(SORT))
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
        return settings(methodInfo, methodParameters, parameters);
    }

    private static List<PersistenceGenerator.QuerySettings> settings(TypedElementInfo methodInfo,
                                                                     List<TypedElementInfo> methodParameters,
                                                                     JdbcSqlParameters parameters) {
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

    private static StreamingCallback streamingCallback(TypedElementInfo methodInfo) {
        List<TypedElementInfo> methodParameters = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .toList();
        List<TypedElementInfo> callbacks = methodParameters.stream()
                .filter(parameter -> parameter.typeName().genericTypeName().equals(CONSUMER))
                .toList();
        if (callbacks.isEmpty()) {
            return null;
        }
        if (callbacks.size() != 1) {
            throw methodError(methodInfo, "JDBC streaming methods require exactly one Consumer<Iterable<T>> parameter: "
                    + methodInfo.elementName());
        }
        if (!methodInfo.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw methodError(methodInfo, "JDBC callback streaming methods must return void: "
                    + methodInfo.elementName());
        }

        TypedElementInfo callback = callbacks.getFirst();
        List<TypeName> callbackTypes = callback.typeName().typeArguments();
        if (callbackTypes.size() != 1) {
            throw invalidStreamingCallback(methodInfo);
        }
        TypeName iterable = callbackTypes.getFirst();
        if (iterable.wildcard()
                || !iterable.genericTypeName().equals(ITERABLE)
                || iterable.typeArguments().size() != 1) {
            throw invalidStreamingCallback(methodInfo);
        }
        TypeName rowType = iterable.typeArguments().getFirst();
        if (rowType.wildcard()) {
            throw invalidStreamingCallback(methodInfo);
        }
        List<TypedElementInfo> applicationParameters = methodParameters.stream()
                .filter(parameter -> parameter != callback)
                .toList();
        return new StreamingCallback(callback, applicationParameters, rowType);
    }

    private static CodegenException invalidStreamingCallback(TypedElementInfo methodInfo) {
        return methodError(methodInfo, "JDBC streaming callback parameter must have type Consumer<Iterable<T>>: "
                + methodInfo.elementName());
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
        List<CallInput> inputs = methodInfo.parameterArguments()
                .stream()
                .filter(parameter -> parameter.kind() == ElementKind.PARAMETER)
                .map(parameter -> callInput(methodInfo, parameter))
                .toList();
        validateOutIndexes(methodInfo, parameters, outParameters);
        return switch (parameters.mode()) {
        case NONE -> {
            if (!inputs.isEmpty()) {
                throw methodError(methodInfo, "JDBC call declares no parameters, but method has parameters: "
                        + methodInfo.elementName());
            }
            yield List.of();
        }
        case NAMED -> namedCallSettings(methodInfo, inputs, parameters.names(), outParameters);
        case POSITIONAL -> positionalCallSettings(methodInfo, inputs, parameters.count(), outParameters);
        case ORDINAL -> ordinalCallSettings(methodInfo, inputs, parameters.indexes(), outParameters);
        };
    }

    private static CallInput callInput(TypedElementInfo methodInfo, TypedElementInfo parameter) {
        io.helidon.common.types.Annotation in = parameter.findAnnotation(IN_ANNOTATION).orElse(null);
        io.helidon.common.types.Annotation inOut = parameter.findAnnotation(IN_OUT_ANNOTATION).orElse(null);
        if (in != null && inOut != null) {
            throw methodError(methodInfo, "JDBC method parameter cannot use both @Data.In and @Data.InOut: "
                    + parameter.elementName()
                    + " in method "
                    + methodInfo.elementName());
        }

        int index = -1;
        String bindingName = parameter.elementName();
        boolean explicitName = false;
        Integer sqlType = null;
        int scale = -1;
        String typeName = "";
        if (in != null) {
            index = in.intValue("index").orElse(-1);
            String configuredName = in.stringValue("name").orElse("");
            if (!configuredName.isEmpty() && configuredName.isBlank()) {
                throw methodError(methodInfo, "JDBC @Data.In name must not be blank: "
                        + parameter.elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (!configuredName.isBlank()) {
                bindingName = configuredName;
                explicitName = true;
            }
            int configuredType = in.intValue("type").orElse(Integer.MIN_VALUE);
            if (configuredType != Integer.MIN_VALUE) {
                sqlType = configuredType;
            }
            scale = in.intValue("scale").orElse(-1);
            typeName = in.stringValue("typeName").orElse("");
            if (!typeName.isEmpty() && typeName.isBlank()) {
                throw methodError(methodInfo, "JDBC @Data.In typeName must not be blank: "
                        + parameter.elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
        } else if (inOut != null) {
            index = requiredInt(methodInfo, inOut, "index", "@Data.InOut");
            sqlType = requiredInt(methodInfo, inOut, "type", "@Data.InOut");
        }

        if (index == 0 || index < -1) {
            throw methodError(methodInfo, "JDBC @Data.In parameter index must be positive or -1: "
                    + parameter.elementName()
                    + " in method "
                    + methodInfo.elementName());
        }
        if (scale < -1) {
            throw methodError(methodInfo, "JDBC @Data.In scale must be non-negative or -1: "
                    + parameter.elementName()
                    + " in method "
                    + methodInfo.elementName());
        }
        if ((scale >= 0 || !typeName.isBlank()) && sqlType == null) {
            throw methodError(methodInfo, "JDBC @Data.In scale and typeName require an explicit type: "
                    + parameter.elementName()
                    + " in method "
                    + methodInfo.elementName());
        }

        return new CallInput(parameter, index, bindingName, explicitName, sqlType, scale, typeName);
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
                                                                              List<CallInput> inputs,
                                                                              List<String> names,
                                                                              List<JdbcCallParameter> outParameters) {
        Set<String> sqlNames = new HashSet<>(names);
        Set<String> inputNames = inputNames(methodInfo, inputs);
        Set<Integer> pureOutIndexes = pureOutIndexes(outParameters);
        for (CallInput input : inputs) {
            if (!sqlNames.contains(input.bindingName())) {
                throw methodError(methodInfo, "JDBC method parameter is not used by call markers: "
                        + input.parameter().elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (input.index() > names.size()) {
                throw methodError(methodInfo, "JDBC @Data.In parameter index "
                        + input.index()
                        + " is greater than the call placeholder count "
                        + names.size()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (input.index() > 0 && !names.get(input.index() - 1).equals(input.bindingName())) {
                throw methodError(methodInfo, "JDBC @Data.In parameter "
                        + input.parameter().elementName()
                        + " declares index "
                        + input.index()
                        + " but that call marker is :"
                        + names.get(input.index() - 1)
                        + " in method "
                        + methodInfo.elementName());
            }
        }
        for (int i = 0; i < names.size(); i++) {
            int jdbcIndex = i + 1;
            String name = names.get(i);
            if (pureOutIndexes.contains(jdbcIndex) && inputNames.contains(name)) {
                throw methodError(methodInfo, "JDBC pure OUT parameter at index "
                        + jdbcIndex
                        + " must not use method parameter marker :"
                        + name
                        + " in method "
                        + methodInfo.elementName());
            }
            if (!inputNames.contains(name) && !pureOutIndexes.contains(jdbcIndex)) {
                throw methodError(methodInfo, "JDBC call parameter :"
                        + name
                        + " has no matching method parameter or pure OUT annotation in method "
                        + methodInfo.elementName());
            }
        }
        return inputs.stream()
                .map(input -> parameterSetting(input, true))
                .toList();
    }

    private static List<PersistenceGenerator.QuerySettings> positionalCallSettings(TypedElementInfo methodInfo,
                                                                                   List<CallInput> inputs,
                                                                                   int count,
                                                                                   List<JdbcCallParameter> outParameters) {
        Set<Integer> pureOutIndexes = pureOutIndexes(outParameters);
        List<Integer> availableIndexes = new ArrayList<>(count - pureOutIndexes.size());
        for (int index = 1; index <= count; index++) {
            if (!pureOutIndexes.contains(index)) {
                availableIndexes.add(index);
            }
        }
        if (inputs.size() != availableIndexes.size()) {
            throw methodError(methodInfo, "JDBC call declares "
                    + count
                    + " positional parameters with "
                    + pureOutIndexes.size()
                    + " pure OUT parameters, but method has "
                    + inputs.size()
                    + " input parameters: "
                    + methodInfo.elementName());
        }

        Set<Integer> assigned = new HashSet<>();
        List<CallInput> resolved = new ArrayList<>(inputs.size());
        for (CallInput input : inputs) {
            if (input.explicitName()) {
                throw methodError(methodInfo, "JDBC @Data.In name requires named call markers: "
                        + input.parameter().elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (input.index() < 0) {
                continue;
            }
            if (!availableIndexes.contains(input.index())) {
                throw methodError(methodInfo, "JDBC @Data.In parameter index "
                        + input.index()
                        + " is not an input position in method "
                        + methodInfo.elementName());
            }
            if (!assigned.add(input.index())) {
                throw methodError(methodInfo, "JDBC @Data.Call declares duplicate input parameter index "
                        + input.index()
                        + " in method "
                        + methodInfo.elementName());
            }
            resolved.add(input);
        }

        java.util.Iterator<Integer> inferredIndexes = availableIndexes.stream()
                .filter(index -> !assigned.contains(index))
                .iterator();
        for (CallInput input : inputs) {
            if (input.index() < 0) {
                resolved.add(input.withIndex(inferredIndexes.next()));
            }
        }
        return resolved.stream()
                .sorted(java.util.Comparator.comparingInt(CallInput::index))
                .map(input -> parameterSetting(input, false))
                .toList();
    }

    private static List<PersistenceGenerator.QuerySettings> ordinalCallSettings(TypedElementInfo methodInfo,
                                                                                List<CallInput> inputs,
                                                                                List<Integer> indexes,
                                                                                List<JdbcCallParameter> outParameters) {
        Set<Integer> pureOutIndexes = pureOutIndexes(outParameters);
        Set<Integer> used = new LinkedHashSet<>();
        for (int parameterIndex = 0; parameterIndex < inputs.size(); parameterIndex++) {
            CallInput input = inputs.get(parameterIndex);
            if (input.explicitName()) {
                throw methodError(methodInfo, "JDBC @Data.In name requires named call markers: "
                        + input.parameter().elementName()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (input.index() > indexes.size()) {
                throw methodError(methodInfo, "JDBC @Data.In parameter index "
                        + input.index()
                        + " is greater than the call placeholder count "
                        + indexes.size()
                        + " in method "
                        + methodInfo.elementName());
            }
            if (input.index() > 0 && indexes.get(input.index() - 1) != parameterIndex + 1) {
                throw methodError(methodInfo, "JDBC @Data.In parameter "
                        + input.parameter().elementName()
                        + " does not match ordinal marker at JDBC index "
                        + input.index()
                        + " in method "
                        + methodInfo.elementName());
            }
        }
        for (int i = 0; i < indexes.size(); i++) {
            int jdbcIndex = i + 1;
            int sourceIndex = indexes.get(i);
            if (pureOutIndexes.contains(jdbcIndex)) {
                if (sourceIndex <= inputs.size()) {
                    throw methodError(methodInfo, "JDBC pure OUT ordinal parameter at index "
                            + jdbcIndex
                            + " must reference an ordinal greater than the method parameter count in method "
                            + methodInfo.elementName());
                }
                continue;
            }
            if (sourceIndex > inputs.size()) {
                throw methodError(methodInfo, "JDBC call ordinal parameter ?"
                        + sourceIndex
                        + " has no matching method parameter in method "
                        + methodInfo.elementName());
            }
            used.add(sourceIndex);
        }
        for (int i = 1; i <= inputs.size(); i++) {
            if (!used.contains(i)) {
                throw methodError(methodInfo, "JDBC call ordinal parameters must use method parameter ?"
                        + i
                        + " in method "
                        + methodInfo.elementName());
            }
        }
        return inputs.stream()
                .map(input -> parameterSetting(input, false))
                .toList();
    }

    private static Set<Integer> pureOutIndexes(List<JdbcCallParameter> outParameters) {
        return outParameters.stream()
                .filter(outParameter -> !outParameter.inOut())
                .map(JdbcCallParameter::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> inputNames(TypedElementInfo methodInfo, List<CallInput> inputs) {
        Set<String> names = new HashSet<>();
        for (CallInput input : inputs) {
            if (!names.add(input.bindingName())) {
                throw methodError(methodInfo, "JDBC @Data.Call declares duplicate input parameter name \""
                        + input.bindingName()
                        + "\" in method "
                        + methodInfo.elementName());
            }
        }
        return names;
    }

    private static PersistenceGenerator.QuerySettings parameterSetting(CallInput input, boolean named) {
        StringBuilder code = new StringBuilder("io.helidon.data.jdbc.JdbcParameter.create(");
        if (named) {
            code.append('"')
                    .append(escapeJava(input.bindingName()))
                    .append("\", ");
        }
        code.append(input.parameter().elementName()).append(')');
        if (input.sqlType() != null) {
            code.append(".withSqlType(").append(input.sqlType()).append(')');
        }
        if (input.scale() >= 0) {
            code.append(".withScale(").append(input.scale()).append(')');
        }
        if (!input.typeName().isBlank()) {
            code.append(".withTypeName(\"")
                    .append(escapeJava(input.typeName()))
                    .append("\")");
        }
        String generatedCode = code.toString();
        return () -> generatedCode;
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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

    private static boolean isShapeAwareScalar(TypeName typeName) {
        TypeName type = JdbcTypes.wrapper(typeName);
        return type.equals(TypeNames.BOXED_BOOLEAN)
                || type.equals(TypeNames.BOXED_BYTE)
                || type.equals(TypeNames.BOXED_SHORT)
                || type.equals(TypeNames.BOXED_INT)
                || type.equals(TypeNames.BOXED_LONG)
                || type.equals(TypeNames.BOXED_FLOAT)
                || type.equals(TypeNames.BOXED_DOUBLE)
                || type.equals(JdbcTypes.NUMBER)
                || type.equals(JdbcTypes.BIG_INTEGER)
                || type.equals(JdbcTypes.BIG_DECIMAL);
    }

    private static boolean isSliceOrPage(TypeName typeName) {
        return SLICE.equals(typeName) || PAGE.equals(typeName);
    }

    private static final class CallInput {
        private final TypedElementInfo parameter;
        private final int index;
        private final String bindingName;
        private final boolean explicitName;
        private final Integer sqlType;
        private final int scale;
        private final String typeName;

        private CallInput(TypedElementInfo parameter,
                          int index,
                          String bindingName,
                          boolean explicitName,
                          Integer sqlType,
                          int scale,
                          String typeName) {
            this.parameter = parameter;
            this.index = index;
            this.bindingName = bindingName;
            this.explicitName = explicitName;
            this.sqlType = sqlType;
            this.scale = scale;
            this.typeName = typeName;
        }

        private TypedElementInfo parameter() {
            return parameter;
        }

        private int index() {
            return index;
        }

        private String bindingName() {
            return bindingName;
        }

        private boolean explicitName() {
            return explicitName;
        }

        private Integer sqlType() {
            return sqlType;
        }

        private int scale() {
            return scale;
        }

        private String typeName() {
            return typeName;
        }

        private CallInput withIndex(int index) {
            return new CallInput(parameter, index, bindingName, explicitName, sqlType, scale, typeName);
        }
    }

    private static final class StreamingCallback {
        private final TypedElementInfo parameter;
        private final List<TypedElementInfo> applicationParameters;
        private final TypeName rowType;

        private StreamingCallback(TypedElementInfo parameter,
                                  List<TypedElementInfo> applicationParameters,
                                  TypeName rowType) {
            this.parameter = parameter;
            this.applicationParameters = applicationParameters;
            this.rowType = rowType;
        }
    }
}
