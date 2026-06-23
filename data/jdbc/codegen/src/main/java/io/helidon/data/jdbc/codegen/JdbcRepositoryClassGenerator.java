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
import java.util.Optional;
import java.util.Set;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Constructor;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.Api;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.jdbc.namedparameters.NamedParameters;

import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.GENERATOR;
import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.methodError;
import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.repositoryError;

final class JdbcRepositoryClassGenerator {

    private static final TypeName COLLECTION = TypeNames.COLLECTION;
    private static final TypeName DATA_EXCEPTION = TypeName.create("io.helidon.data.DataException");
    private static final TypeName GENERATED_KEYS = TypeName.create("io.helidon.data.Data.GeneratedKeys");
    private static final TypeName GENERIC_REPOSITORY = TypeName.create("io.helidon.data.Data.GenericRepository");
    private static final TypeName JDBC_BINDER = TypeName.create("io.helidon.data.jdbc.JdbcBinder");
    private static final TypeName JDBC_OPERATIONS = TypeName.create("io.helidon.data.jdbc.JdbcOperations");
    private static final TypeName JDBC_STATEMENT_PLAN = TypeName.create("io.helidon.data.jdbc.JdbcStatementPlan");
    private static final TypeName PAGE = TypeName.create("io.helidon.data.Page");
    private static final TypeName PERSISTENCE_UNIT = TypeName.create("io.helidon.data.Data.PersistenceUnit");
    private static final TypeName QUERY = TypeName.create("io.helidon.data.Data.Query");
    private static final TypeName SERVICE_NAMED = TypeName.create("io.helidon.service.registry.Service.Named");
    private static final TypeName SERVICE_SINGLETON = TypeName.create("io.helidon.service.registry.Service.Singleton");
    private static final TypeName SLICE = TypeName.create("io.helidon.data.Slice");
    private static final TypeName STREAM = TypeName.create("java.util.stream.Stream");

    private final CodegenContext codegenContext;
    private final RoundContext roundContext;
    private final RepositoryInfo repositoryInfo;
    private final TypeName className;
    private final ClassModel.Builder classModel;
    private final JdbcMapperCodegen mapperCodegen;
    private final JdbcReducerCodegen reducerCodegen;
    private int statementPlanCounter;

    private JdbcRepositoryClassGenerator(CodegenContext codegenContext,
                                         RoundContext roundContext,
                                         RepositoryInfo repositoryInfo,
                                         TypeName className,
                                         ClassModel.Builder classModel) {
        this.codegenContext = codegenContext;
        this.roundContext = roundContext;
        this.repositoryInfo = repositoryInfo;
        this.className = className;
        this.classModel = classModel;
        this.mapperCodegen = new JdbcMapperCodegen(codegenContext, roundContext);
        this.reducerCodegen = new JdbcReducerCodegen(codegenContext, roundContext, className);
    }

    static void generate(CodegenContext codegenContext,
                         RoundContext roundContext,
                         RepositoryInfo repositoryInfo,
                         TypeName className,
                         ClassModel.Builder classModel) {
        new JdbcRepositoryClassGenerator(codegenContext,
                                         roundContext,
                                         repositoryInfo,
                                         className,
                                         classModel).generate();
    }

    private void generate() {
        TypeName repositoryInterface = repositoryInfo.interfaceInfo().typeName();
        validateRepositoryShape();

        classModel.type(className)
                .copyright(CodegenUtil.copyright(GENERATOR, repositoryInterface, className))
                .addAnnotation(Annotation.create(SuppressWarnings.class, Api.SUPPRESS_INTERNAL))
                .addAnnotation(Annotation.create(SERVICE_SINGLETON))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR, repositoryInterface, className, "1", ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(repositoryInterface);

        classModel.addField(builder -> builder.name("jdbc")
                .isFinal(true)
                .type(JDBC_OPERATIONS));

        generateConstructor();
        repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.hasAnnotation(QUERY))
                .forEach(this::generateQueryMethod);
    }

    private void validateRepositoryShape() {
        Set<TypeName> unsupported = new HashSet<>(repositoryInfo.interfaceNames());
        unsupported.remove(GENERIC_REPOSITORY);
        if (!unsupported.isEmpty()) {
            throw repositoryError(repositoryInfo,
                                  "JDBC repositories currently support Data.GenericRepository and @Data.Query "
                                          + "methods only. Unsupported repository interfaces: " + unsupported);
        }

        repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> !it.elementModifiers().contains(Modifier.DEFAULT))
                .filter(it -> !it.elementModifiers().contains(Modifier.STATIC))
                .filter(it -> !it.hasAnnotation(QUERY))
                .findFirst()
                .ifPresent(it -> {
                    throw methodError(it, "JDBC repository methods must be annotated with @Data.Query: "
                            + it.elementName());
                });
    }

    private void generateConstructor() {
        Constructor.Builder ctr = Constructor.builder()
                .accessModifier(AccessModifier.PACKAGE_PRIVATE);

        repositoryInfo.interfaceInfo()
                .findAnnotation(PERSISTENCE_UNIT)
                .ifPresentOrElse(annotation -> generateNamedJdbcConstructor(ctr, annotation),
                                 () -> {
                                     ctr.addParameter(JDBC_OPERATIONS, "jdbc");
                                     ctr.addContentLine("this.jdbc = jdbc;");
                                 });

        classModel.addConstructor(ctr);
    }

    private void generateNamedJdbcConstructor(Constructor.Builder ctr, Annotation annotation) {
        String name = annotation.value().orElse("@default");
        boolean required = annotation.booleanValue("required").orElse(true);
        if ("@default".equals(name)) {
            ctr.addParameter(JDBC_OPERATIONS, "jdbc");
            ctr.addContentLine("this.jdbc = jdbc;");
            return;
        }

        Annotation named = Annotation.builder()
                .typeName(SERVICE_NAMED)
                .property("value", name)
                .build();

        if (required) {
            ctr.addParameter(Parameter.builder()
                                     .addAnnotation(named)
                                     .name("jdbc")
                                     .type(JDBC_OPERATIONS)
                                     .build());
            ctr.addContentLine("this.jdbc = jdbc;");
        } else {
            ctr.addParameter(Parameter.builder()
                                     .addAnnotation(named)
                                     .name("namedJdbc")
                                     .type(TypeName.builder(TypeNames.OPTIONAL)
                                                   .addTypeArgument(JDBC_OPERATIONS)
                                                   .build())
                                     .build())
                    .addParameter(Parameter.builder()
                                          .name("jdbc")
                                          .type(TypeName.builder(TypeNames.SUPPLIER)
                                                        .addTypeArgument(JDBC_OPERATIONS)
                                                        .build())
                                          .build());
            ctr.addContentLine("this.jdbc = namedJdbc.orElseGet(jdbc);");
        }
    }

    private void generateQueryMethod(TypedElementInfo methodInfo) {
        classModel.addMethod(builder -> {
            generateMethodHeader(builder, methodInfo);
            RepositoryMethodAnalysis analysis = analyzeMethod(methodInfo);
            StatementBinding binding = analysis.binding().withPlanField(addStatementPlanField(methodInfo, analysis.binding()));
            if (analysis.generatedKeys()) {
                generateGeneratedKeyMethodBody(builder, methodInfo, binding);
            } else if (analysis.query()) {
                generateSelectMethodBody(builder, methodInfo, binding);
            } else {
                generateUpdateMethodBody(builder, methodInfo, binding);
            }
        });
    }

    private void generateMethodHeader(Method.Builder builder, TypedElementInfo methodInfo) {
        if (!methodInfo.typeParameters().isEmpty()) {
            throw methodError(methodInfo,
                              "JDBC repository methods with method type parameters are not yet supported: "
                                      + methodInfo.elementName());
        }
        builder.name(methodInfo.elementName())
                .returnType(methodInfo.typeName())
                .addAnnotation(Annotations.OVERRIDE);
        methodInfo.parameterArguments()
                .forEach(parameterInfo -> builder.addParameter(Parameter.builder()
                                                                       .name(parameterInfo.elementName())
                                                                       .type(parameterInfo.typeName())
                                                                       .build()));
    }

    private void generateSelectMethodBody(Method.Builder builder,
                                          TypedElementInfo methodInfo,
                                          StatementBinding binding) {
        TypeName returnType = methodInfo.typeName();
        if (returnType.equals(STREAM) || returnType.equals(PAGE) || returnType.equals(SLICE)) {
            throw unsupportedReturnType(methodInfo);
        }
        if (returnType.isList() || returnType.equals(COLLECTION)) {
            TypeName itemType = typeArgument(methodInfo);
            if (reducerCodegen.reducerRequired(itemType, methodInfo, binding.statement())) {
                builder.addContentLine("return this.jdbc.listReduced(");
                appendReducedOperationArguments(builder, binding, itemType, methodInfo);
            } else {
                builder.addContentLine("return this.jdbc.list(");
                appendOperationArguments(builder, binding, itemType, methodInfo);
            }
            builder.addContentLine(");");
        } else if (returnType.isOptional()) {
            TypeName itemType = typeArgument(methodInfo);
            if (reducerCodegen.reducerRequired(itemType, methodInfo, binding.statement())) {
                builder.addContentLine("return this.jdbc.optionalReduced(");
                appendReducedOperationArguments(builder, binding, itemType, methodInfo);
            } else {
                builder.addContentLine("return this.jdbc.optional(");
                appendOperationArguments(builder, binding, itemType, methodInfo);
            }
            builder.addContentLine(");");
        } else {
            if (reducerCodegen.reducerRequired(returnType, methodInfo, binding.statement())) {
                builder.addContentLine("return this.jdbc.oneReduced(");
                appendReducedOperationArguments(builder, binding, returnType, methodInfo);
            } else {
                builder.addContentLine("return this.jdbc.one(");
                appendOperationArguments(builder, binding, returnType, methodInfo);
            }
            builder.addContentLine(");");
        }
    }

    private void generateUpdateMethodBody(Method.Builder builder,
                                          TypedElementInfo methodInfo,
                                          StatementBinding binding) {
        TypeName returnType = methodInfo.typeName();
        if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            builder.addContentLine("this.jdbc.update(");
            appendUpdateArguments(builder, binding);
            builder.addContentLine(");");
        } else if (returnType.equals(TypeNames.PRIMITIVE_LONG) || returnType.equals(TypeNames.BOXED_LONG)) {
            builder.addContentLine("return this.jdbc.update(");
            appendUpdateArguments(builder, binding);
            builder.addContentLine(");");
        } else if (returnType.equals(TypeNames.PRIMITIVE_INT) || returnType.equals(TypeNames.BOXED_INT)) {
            builder.addContentLine("return (int) this.jdbc.update(");
            appendUpdateArguments(builder, binding);
            builder.addContentLine(");");
        } else if (returnType.equals(TypeNames.PRIMITIVE_BOOLEAN) || returnType.equals(TypeNames.BOXED_BOOLEAN)) {
            builder.addContentLine("return this.jdbc.update(");
            appendUpdateArguments(builder, binding);
            builder.addContentLine(") > 0L;");
        } else {
            throw unsupportedReturnType(methodInfo);
        }
    }

    private void generateGeneratedKeyMethodBody(Method.Builder builder,
                                                TypedElementInfo methodInfo,
                                                StatementBinding binding) {
        TypeName returnType = methodInfo.typeName();
        boolean optional = returnType.isOptional();
        TypeName keyType = optional ? typeArgument(methodInfo) : returnType;
        if (returnType.isList() || returnType.equals(COLLECTION) || returnType.equals(STREAM)
                || returnType.equals(PAGE) || returnType.equals(SLICE)
                || returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            throw unsupportedReturnType(methodInfo);
        }

        builder.addContentLine("return this.jdbc.generatedKey(");
        appendGeneratedKeyArguments(builder, binding, keyType, methodInfo);
        if (optional) {
            builder.addContentLine(");");
        } else {
            builder.addContent(").orElseThrow(() -> new ")
                    .addContent(DATA_EXCEPTION)
                    .addContentLine("(\"JDBC update returned no generated key.\"));");
        }
    }

    private void appendOperationArguments(Method.Builder builder,
                                          StatementBinding binding,
                                          TypeName itemType,
                                          TypedElementInfo methodInfo) {
        builder.increaseContentPadding();
        builder.addContentLine(binding.planField() + ",");
        appendBinder(builder, binding);
        builder.addContentLine(",");
        mapperCodegen.appendMapper(builder, itemType, methodInfo);
        builder.decreaseContentPadding();
    }

    private void appendReducedOperationArguments(Method.Builder builder,
                                                 StatementBinding binding,
                                                 TypeName itemType,
                                                 TypedElementInfo methodInfo) {
        builder.increaseContentPadding();
        builder.addContentLine(binding.planField() + ",");
        appendBinder(builder, binding);
        builder.addContentLine(",");
        reducerCodegen.appendReducer(builder, itemType, methodInfo, binding.statement());
        builder.decreaseContentPadding();
    }

    private void appendUpdateArguments(Method.Builder builder, StatementBinding binding) {
        builder.increaseContentPadding();
        builder.addContentLine(binding.planField() + ",");
        appendBinder(builder, binding);
        builder.decreaseContentPadding();
    }

    private void appendGeneratedKeyArguments(Method.Builder builder,
                                            StatementBinding binding,
                                            TypeName keyType,
                                            TypedElementInfo methodInfo) {
        builder.increaseContentPadding();
        builder.addContentLine(binding.planField() + ",");
        appendBinder(builder, binding);
        builder.addContentLine(",");
        mapperCodegen.appendMapper(builder, keyType, methodInfo);
        builder.decreaseContentPadding();
    }

    private String addStatementPlanField(TypedElementInfo methodInfo, StatementBinding binding) {
        String fieldName = statementPlanFieldName(methodInfo);
        classModel.addField(field -> field.accessModifier(AccessModifier.PRIVATE)
                .isStatic(true)
                .isFinal(true)
                .type(JDBC_STATEMENT_PLAN)
                .name(fieldName)
                .addContent(JDBC_STATEMENT_PLAN)
                .addContent(".")
                .addContent(statementPlanFactory(methodInfo, binding))
                .addContent("(")
                .addContent(literal(binding.statement()))
                .addContent(generatedKeyArguments(methodInfo))
                .addContent(")"));
        return fieldName;
    }

    private String statementPlanFactory(TypedElementInfo methodInfo, StatementBinding binding) {
        if (methodInfo.hasAnnotation(GENERATED_KEYS)) {
            return "generatedKeys";
        }
        if (isQuery(binding.statement())) {
            return "query";
        }
        return "update";
    }

    private String generatedKeyArguments(TypedElementInfo methodInfo) {
        if (!methodInfo.hasAnnotation(GENERATED_KEYS)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        generatedKeyColumnNames(methodInfo)
                .forEach(columnName -> builder.append(", ")
                        .append(literal(columnName)));
        return builder.toString();
    }

    private String statementPlanFieldName(TypedElementInfo methodInfo) {
        StringBuilder builder = new StringBuilder("STATEMENT_");
        String methodName = methodInfo.elementName();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                builder.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toUpperCase(c));
            } else {
                builder.append('_');
            }
        }
        builder.append('_')
                .append(++statementPlanCounter);
        return builder.toString();
    }

    private void appendBinder(Method.Builder builder, StatementBinding binding) {
        if (binding.bindings().isEmpty()) {
            builder.addContent(JDBC_BINDER)
                    .addContent(".none()");
            return;
        }
        builder.addContentLine("statement -> {");
        builder.increaseContentPadding();
        binding.bindings()
                .forEach(bindValue -> builder.addContentLine("statement.setObject("
                                                                     + bindValue.index()
                                                                     + ", "
                                                                     + bindValue.expression()
                                                                     + ");"));
        builder.decreaseContentPadding();
        builder.addContent("}");
    }

    private StatementBinding statementBinding(String sql, TypedElementInfo methodInfo) {
        List<String> markers = new ArrayList<>();
        String statement = NamedParameters.rewrite(sql, markers::add);
        List<TypedElementInfo> parameters = methodInfo.parameterArguments();
        if (markers.isEmpty() && !parameters.isEmpty()) {
            throw methodError(methodInfo,
                              "JDBC query has method parameters but no parameter markers: " + methodInfo.elementName());
        }

        List<BindValue> bindings = new ArrayList<>(markers.size());
        Set<String> usedParameters = new HashSet<>();
        int positionalIndex = 0;
        for (int i = 0; i < markers.size(); i++) {
            String marker = markers.get(i);
            String expression;
            if ("?".equals(marker)) {
                if (positionalIndex >= parameters.size()) {
                    throw methodError(methodInfo,
                                      "JDBC query has more positional markers than method parameters: "
                                              + methodInfo.elementName());
                }
                expression = parameters.get(positionalIndex++).elementName();
            } else {
                expression = namedBindingExpression(marker.substring(1), parameters, methodInfo);
            }
            usedParameters.add(boundParameterName(expression));
            bindings.add(new BindValue(i + 1, expression));
        }
        parameters.stream()
                .map(TypedElementInfo::elementName)
                .filter(parameter -> !usedParameters.contains(parameter))
                .findFirst()
                .ifPresent(parameter -> {
                    throw methodError(methodInfo,
                                      "JDBC query parameter is not used by SQL markers: "
                                              + parameter
                                              + " in method "
                                              + methodInfo.elementName());
                });
        return new StatementBinding(statement, List.copyOf(bindings), null);
    }

    private static String boundParameterName(String expression) {
        int methodCall = expression.indexOf('.');
        if (methodCall == -1) {
            return expression;
        }
        return expression.substring(0, methodCall);
    }

    private String namedBindingExpression(String name,
                                          List<TypedElementInfo> parameters,
                                          TypedElementInfo methodInfo) {
        Map<String, TypedElementInfo> byName = new HashMap<>();
        parameters.forEach(parameter -> byName.put(parameter.elementName(), parameter));
        TypedElementInfo direct = byName.get(name);
        if (direct != null) {
            return direct.elementName();
        }

        List<String> matches = parameters.stream()
                .map(parameter -> propertyExpression(parameter, name))
                .flatMap(Optional::stream)
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw methodError(methodInfo,
                              "No method parameter or readable parameter property matches JDBC named "
                                      + "parameter :" + name + " in method " + methodInfo.elementName());
        }
        throw methodError(methodInfo,
                          "Multiple method parameter properties match JDBC named parameter :"
                                  + name + " in method " + methodInfo.elementName());
    }

    private Optional<String> propertyExpression(TypedElementInfo parameter, String propertyName) {
        return typeInfo(parameter.typeName())
                .flatMap(typeInfo -> {
                    if (typeInfo.kind() == ElementKind.RECORD
                            && typeInfo.elementInfo()
                                    .stream()
                                    .anyMatch(it -> it.kind() == ElementKind.RECORD_COMPONENT
                                            && it.elementName().equals(propertyName))) {
                        return Optional.of(parameter.elementName() + "." + propertyName + "()");
                    }
                    return readableMethod(typeInfo, propertyName)
                            .map(method -> parameter.elementName() + "." + method.elementName() + "()");
                });
    }

    private static Optional<TypedElementInfo> readableMethod(TypeInfo typeInfo, String propertyName) {
        String getter = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        String booleanGetter = "is" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        return typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.parameterArguments().isEmpty())
                .filter(it -> it.elementName().equals(propertyName)
                        || it.elementName().equals(getter)
                        || it.elementName().equals(booleanGetter))
                .findFirst();
    }

    private Optional<TypeInfo> typeInfo(TypeName typeName) {
        return roundContext.typeInfo(typeName)
                .or(() -> codegenContext.typeInfo(typeName));
    }

    private TypeName typeArgument(TypedElementInfo methodInfo) {
        List<TypeName> typeArguments = methodInfo.typeName().typeArguments();
        if (typeArguments.isEmpty()) {
            throw methodError(methodInfo, "Missing generic return type argument for " + methodInfo.elementName());
        }
        return typeArguments.getFirst();
    }

    private RepositoryMethodAnalysis analyzeMethod(TypedElementInfo methodInfo) {
        String sql = methodInfo.annotation(QUERY)
                .value()
                .orElseThrow(() -> methodError(methodInfo, "@Data.Query annotation value is missing"));
        StatementBinding binding = statementBinding(sql, methodInfo);
        boolean generatedKeys = methodInfo.hasAnnotation(GENERATED_KEYS);
        boolean query = isQuery(binding.statement());
        if (generatedKeys && query) {
            throw methodError(methodInfo,
                              "@Data.GeneratedKeys can be used only with data-changing JDBC repository methods: "
                                      + methodInfo.elementName());
        }
        return new RepositoryMethodAnalysis(sql,
                                            binding,
                                            generatedKeys,
                                            query,
                                            methodInfo.typeName());
    }

    private List<String> generatedKeyColumnNames(TypedElementInfo methodInfo) {
        return methodInfo.findAnnotation(GENERATED_KEYS)
                .flatMap(Annotation::stringValues)
                .orElseGet(List::of);
    }

    private CodegenException unsupportedReturnType(TypedElementInfo methodInfo) {
        return methodError(methodInfo,
                           "Unsupported JDBC repository return type for method "
                                   + methodInfo.elementName()
                                   + ": "
                                   + methodInfo.typeName().resolvedName());
    }

    private static boolean isQuery(String statement) {
        String lower = statement.stripLeading().toLowerCase();
        return lower.startsWith("select")
                || lower.startsWith("with")
                || lower.startsWith("values");
    }

    private static String literal(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '\\' -> builder.append("\\\\");
            case '"' -> builder.append("\\\"");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private record StatementBinding(String statement, List<BindValue> bindings, String planField) {
        private StatementBinding withPlanField(String planField) {
            return new StatementBinding(statement, bindings, planField);
        }
    }

    private record BindValue(int index, String expression) {
    }

    private record RepositoryMethodAnalysis(String sql,
                                            StatementBinding binding,
                                            boolean generatedKeys,
                                            boolean query,
                                            TypeName returnType) {
    }
}
