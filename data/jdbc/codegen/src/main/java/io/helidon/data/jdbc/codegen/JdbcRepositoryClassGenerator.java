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
import java.util.LinkedHashMap;
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

/**
 * Generates the concrete implementation class for one JDBC repository interface.
 * <p>
 * The generated class is a package-private Service Registry singleton. It keeps
 * repository code thin: each repository method owns a static JDBC statement plan,
 * creates a generated binder for its method arguments, and delegates execution to
 * {@code JdbcOperations}. Mapper and reducer generation is delegated to
 * {@link JdbcMapperCodegen} and {@link JdbcReducerCodegen}.
 */
final class JdbcRepositoryClassGenerator {

    private static final TypeName COLLECTION = TypeNames.COLLECTION;
    private static final TypeName BIND = TypeName.create("io.helidon.data.Data.Bind");
    private static final TypeName DATA_EXCEPTION = TypeName.create("io.helidon.data.DataException");
    private static final TypeName GENERATED_KEYS = TypeName.create("io.helidon.data.Data.GeneratedKeys");
    private static final TypeName GENERIC_REPOSITORY = TypeName.create("io.helidon.data.Data.GenericRepository");
    private static final TypeName JDBC_BINDER = TypeName.create("io.helidon.data.jdbc.JdbcBinder");
    private static final TypeName JDBC_OPERATIONS = TypeName.create("io.helidon.data.jdbc.JdbcOperations");
    private static final TypeName JDBC_STATEMENT_PLAN = TypeName.create("io.helidon.data.jdbc.JdbcStatementPlan");
    private static final TypeName PAGE = TypeName.create("io.helidon.data.Page");
    private static final TypeName PARAM = TypeName.create("io.helidon.data.Data.Param");
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

        // Repository implementations are deliberately package-private and exposed through Service Registry.
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
        // Keep the first JDBC provider scope explicit: supported base repository plus @Data.Query methods.
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
            // Optional named persistence unit falls back to the default JdbcOperations service.
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
            // Statement planning and SQL rewriting happen once in generated static fields.
            StatementBinding binding = analysis.binding()
                    .withPlanField(addStatementPlanField(methodInfo, analysis.binding()));
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
                // Relationship result graphs are accumulated through a generated reducer.
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

        // Generated-key methods call the same operation and then adapt Optional or required cardinality.
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
        mapperCodegen.appendMapper(builder, itemType, methodInfo, binding.statement());
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
        mapperCodegen.appendMapper(builder, keyType, methodInfo, binding.statement());
        builder.decreaseContentPadding();
    }

    private String addStatementPlanField(TypedElementInfo methodInfo, StatementBinding binding) {
        String fieldName = statementPlanFieldName(methodInfo);
        // The immutable plan captures SQL and operation kind; binders remain per invocation.
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
        // Named parameters are rewritten at build time. Generated code only binds JDBC positions.
        List<String> markers = new ArrayList<>();
        String statement = NamedParameters.rewrite(sql, markers::add);
        List<TypedElementInfo> parameters = methodInfo.parameterArguments();
        if (markers.isEmpty() && !parameters.isEmpty()) {
            throw methodError(methodInfo,
                              "JDBC query has method parameters but no parameter markers: " + methodInfo.elementName());
        }

        boolean hasNamedMarkers = markers.stream().anyMatch(marker -> !"?".equals(marker));
        boolean hasPositionalMarkers = markers.stream().anyMatch(marker -> "?".equals(marker));
        if (hasNamedMarkers && hasPositionalMarkers) {
            throw methodError(methodInfo,
                              "JDBC query must not mix named and positional parameter markers: "
                                      + methodInfo.elementName());
        }
        boolean explicit = parameters.stream()
                .anyMatch(parameter -> parameter.hasAnnotation(PARAM) || parameter.hasAnnotation(BIND));
        if (explicit) {
            return explicitStatementBinding(statement, markers, parameters, methodInfo, hasNamedMarkers);
        }
        return conventionStatementBinding(statement, markers, parameters, methodInfo);
    }

    private StatementBinding conventionStatementBinding(String statement,
                                                        List<String> markers,
                                                        List<TypedElementInfo> parameters,
                                                        TypedElementInfo methodInfo) {
        // Convention binding is retained for simple methods; explicit @Data.Param/@Data.Bind takes precedence.
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

    private StatementBinding explicitStatementBinding(String statement,
                                                      List<String> markers,
                                                      List<TypedElementInfo> parameters,
                                                      TypedElementInfo methodInfo,
                                                      boolean namedMarkers) {
        return namedMarkers
                ? explicitNamedStatementBinding(statement, markers, parameters, methodInfo)
                : explicitPositionalStatementBinding(statement, markers, parameters, methodInfo);
    }

    private StatementBinding explicitNamedStatementBinding(String statement,
                                                           List<String> markers,
                                                           List<TypedElementInfo> parameters,
                                                           TypedElementInfo methodInfo) {
        // Explicit binding requires every SQL name to resolve to one argument or one prefixed property.
        Map<String, String> byName = new LinkedHashMap<>();
        Map<String, BindPrefixBinding> byPrefix = new LinkedHashMap<>();
        for (TypedElementInfo parameter : parameters) {
            ParamBinding binding = explicitParamBinding(parameter, methodInfo);
            BindPrefixBinding bindPrefix = explicitBindBinding(parameter, methodInfo);
            if (binding != null && bindPrefix != null) {
                throw methodError(methodInfo,
                                  "JDBC explicit parameter binding must use either @Data.Param or @Data.Bind, "
                                          + "not both, on method parameter "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            if (binding == null && bindPrefix == null) {
                throw methodError(methodInfo,
                                  "JDBC explicit parameter binding requires @Data.Param or @Data.Bind "
                                          + "on method parameter: "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            if (bindPrefix != null) {
                validateNoParamNamespaceConflict(bindPrefix, byName, methodInfo);
                BindPrefixBinding previous = byPrefix.put(bindPrefix.prefix(), bindPrefix);
                if (previous != null) {
                    throw methodError(methodInfo,
                                      "Multiple method parameters declare @Data.Bind(\""
                                              + bindPrefix.prefix()
                                              + "\") in method "
                                              + methodInfo.elementName());
                }
                continue;
            }
            if (binding.positional()) {
                throw methodError(methodInfo,
                                  "JDBC named query parameter binding must use @Data.Param(\"name\"), not index, "
                                          + "on method parameter "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            validateNoBindNamespaceConflict(binding, byPrefix, methodInfo);
            String previous = byName.put(binding.name(), binding.expression());
            if (previous != null) {
                throw methodError(methodInfo,
                                  "Multiple method parameters declare @Data.Param(\""
                                          + binding.name()
                                          + "\") in method "
                                          + methodInfo.elementName());
            }
        }

        List<BindValue> bindings = new ArrayList<>(markers.size());
        Set<String> used = new HashSet<>();
        Set<String> usedPrefixes = new HashSet<>();
        for (int i = 0; i < markers.size(); i++) {
            String name = markers.get(i).substring(1);
            String expression = byName.get(name);
            if (expression != null) {
                used.add(name);
                bindings.add(new BindValue(i + 1, expression));
                continue;
            }
            BindPrefixBinding prefixBinding = bindPrefixBinding(name, byPrefix);
            if (prefixBinding != null) {
                usedPrefixes.add(prefixBinding.prefix());
                bindings.add(new BindValue(i + 1, bindExpression(name, prefixBinding, methodInfo)));
                continue;
            }
            if (byPrefix.containsKey(name)) {
                throw methodError(methodInfo,
                                  "@Data.Bind(\""
                                          + name
                                          + "\") binds property paths such as :"
                                          + name
                                          + ".name. Use @Data.Param(\""
                                          + name
                                          + "\") to bind the whole argument in method "
                                          + methodInfo.elementName());
            }
            throw methodError(methodInfo,
                              "No @Data.Param or @Data.Bind binding matches JDBC named parameter :"
                                      + name
                                      + " in method "
                                      + methodInfo.elementName());
        }
        byName.keySet()
                .stream()
                .filter(name -> !used.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw methodError(methodInfo,
                                      "@Data.Param(\""
                                              + name
                                              + "\") is not used by SQL markers in method "
                                              + methodInfo.elementName());
                });
        byPrefix.keySet()
                .stream()
                .filter(prefix -> !usedPrefixes.contains(prefix))
                .findFirst()
                .ifPresent(prefix -> {
                    throw methodError(methodInfo,
                                      "@Data.Bind(\""
                                              + prefix
                                              + "\") is not used by SQL markers in method "
                                              + methodInfo.elementName());
                });
        return new StatementBinding(statement, List.copyOf(bindings), null);
    }

    private void validateNoParamNamespaceConflict(BindPrefixBinding binding,
                                                  Map<String, String> byName,
                                                  TypedElementInfo methodInfo) {
        byName.keySet()
                .stream()
                .filter(name -> name.equals(binding.prefix()) || name.startsWith(binding.prefix() + "."))
                .findFirst()
                .ifPresent(name -> {
                    throw methodError(methodInfo,
                                      "JDBC explicit parameter binding namespace conflict between @Data.Bind(\""
                                              + binding.prefix()
                                              + "\") and @Data.Param(\""
                                              + name
                                              + "\") in method "
                                              + methodInfo.elementName());
                });
    }

    private void validateNoBindNamespaceConflict(ParamBinding binding,
                                                 Map<String, BindPrefixBinding> byPrefix,
                                                 TypedElementInfo methodInfo) {
        byPrefix.keySet()
                .stream()
                .filter(prefix -> binding.name().equals(prefix) || binding.name().startsWith(prefix + "."))
                .findFirst()
                .ifPresent(prefix -> {
                    throw methodError(methodInfo,
                                      "JDBC explicit parameter binding namespace conflict between @Data.Param(\""
                                              + binding.name()
                                              + "\") and @Data.Bind(\""
                                              + prefix
                                              + "\") in method "
                                              + methodInfo.elementName());
                });
    }

    private BindPrefixBinding bindPrefixBinding(String name, Map<String, BindPrefixBinding> byPrefix) {
        return byPrefix.entrySet()
                .stream()
                .filter(entry -> name.startsWith(entry.getKey() + "."))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String bindExpression(String name, BindPrefixBinding binding, TypedElementInfo methodInfo) {
        String propertyPath = name.substring(binding.prefix().length() + 1);
        if (propertyPath.isBlank()) {
            throw methodError(methodInfo,
                              "@Data.Bind(\""
                                      + binding.prefix()
                                      + "\") requires a property path in method "
                                      + methodInfo.elementName());
        }

        // Nested @Data.Bind paths are emitted as null-safe accessor chains.
        List<Accessor> accessors = new ArrayList<>();
        TypeInfo current = typeInfo(binding.parameter().typeName())
                .orElseThrow(() -> methodError(methodInfo,
                                               "@Data.Bind target type cannot be resolved: "
                                                       + binding.parameter().typeName().fqName()
                                                       + " in method "
                                                       + methodInfo.elementName()));
        String[] parts = propertyPath.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String property = parts[i];
            if (!isIdentifier(property)) {
                throw methodError(methodInfo,
                                  "Invalid @Data.Bind property path segment \""
                                          + property
                                          + "\" in marker :"
                                          + name
                                          + " in method "
                                          + methodInfo.elementName());
            }
            Accessor accessor = accessor(current, property, methodInfo);
            accessors.add(accessor);
            if (i + 1 < parts.length) {
                current = typeInfo(accessor.type())
                        .orElseThrow(() -> methodError(methodInfo,
                                                       "@Data.Bind property path cannot continue through "
                                                               + accessor.type().fqName()
                                                               + " at marker :"
                                                               + name
                                                               + " in method "
                                                               + methodInfo.elementName()));
            }
        }
        return nullSafeExpression(binding.parameter().elementName(), accessors, 0);
    }

    private Accessor accessor(TypeInfo typeInfo, String propertyName, TypedElementInfo methodInfo) {
        if (typeInfo.kind() == ElementKind.RECORD) {
            Optional<TypedElementInfo> component = typeInfo.elementInfo()
                    .stream()
                    .filter(it -> it.kind() == ElementKind.RECORD_COMPONENT)
                    .filter(it -> it.elementName().equals(propertyName))
                    .findFirst();
            if (component.isPresent()) {
                return new Accessor(propertyName, component.get().typeName());
            }
        }

        List<TypedElementInfo> methods = typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.parameterArguments().isEmpty())
                .filter(it -> !it.elementModifiers().contains(Modifier.STATIC))
                .filter(it -> !it.typeName().equals(TypeNames.PRIMITIVE_VOID)
                        && !it.typeName().equals(TypeNames.BOXED_VOID))
                .filter(it -> accessible(typeInfo, it.accessModifier()))
                .filter(it -> accessorNameMatches(it, propertyName))
                .toList();
        if (methods.size() == 1) {
            TypedElementInfo method = methods.getFirst();
            return new Accessor(method.elementName(), method.typeName());
        }
        if (methods.size() > 1) {
            throw methodError(methodInfo,
                              "@Data.Bind property path \""
                                      + propertyName
                                      + "\" is ambiguous on "
                                      + typeInfo.typeName().fqName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        throw methodError(methodInfo,
                          "@Data.Bind property path \""
                                  + propertyName
                                  + "\" does not match a readable property on "
                                  + typeInfo.typeName().fqName()
                                  + " in method "
                                  + methodInfo.elementName());
    }

    private boolean accessorNameMatches(TypedElementInfo method, String propertyName) {
        String name = method.elementName();
        if (name.equals(propertyName)) {
            return true;
        }
        String suffix = upperFirst(propertyName);
        if (name.equals("get" + suffix)) {
            return true;
        }
        return name.equals("is" + suffix)
                && (method.typeName().equals(TypeNames.PRIMITIVE_BOOLEAN)
                || method.typeName().equals(TypeNames.BOXED_BOOLEAN));
    }

    private boolean accessible(TypeInfo owner, AccessModifier accessModifier) {
        if (accessModifier == AccessModifier.PUBLIC) {
            return true;
        }
        if (accessModifier == AccessModifier.PRIVATE) {
            return false;
        }
        return owner.typeName().packageName().equals(className.packageName());
    }

    private String nullSafeExpression(String receiver, List<Accessor> accessors, int index) {
        if (index >= accessors.size()) {
            return receiver;
        }
        String next = receiver + "." + accessors.get(index).methodName() + "()";
        String resolved = nullSafeExpression(next, accessors, index + 1);
        if (index + 1 < accessors.size()) {
            resolved = "(" + resolved + ")";
        }
        return receiver + " == null ? null : " + resolved;
    }

    private StatementBinding explicitPositionalStatementBinding(String statement,
                                                                List<String> markers,
                                                                List<TypedElementInfo> parameters,
                                                                TypedElementInfo methodInfo) {
        Map<Integer, String> byIndex = new LinkedHashMap<>();
        for (TypedElementInfo parameter : parameters) {
            if (parameter.hasAnnotation(BIND)) {
                throw methodError(methodInfo,
                                  "@Data.Bind can be used only with named JDBC parameters on method parameter "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            ParamBinding binding = explicitParamBinding(parameter, methodInfo);
            if (binding == null) {
                throw methodError(methodInfo,
                                  "JDBC explicit parameter binding requires @Data.Param on method parameter: "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            if (binding.named()) {
                throw methodError(methodInfo,
                                  "JDBC positional query parameter binding must use @Data.Param(index = n), not name, "
                                          + "on method parameter "
                                          + parameter.elementName()
                                          + " in method "
                                          + methodInfo.elementName());
            }
            String previous = byIndex.put(binding.index(), binding.expression());
            if (previous != null) {
                throw methodError(methodInfo,
                                  "Multiple method parameters declare @Data.Param(index = "
                                          + binding.index()
                                          + ") in method "
                                          + methodInfo.elementName());
            }
        }

        List<BindValue> bindings = new ArrayList<>(markers.size());
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < markers.size(); i++) {
            int index = i + 1;
            String expression = byIndex.get(index);
            if (expression == null) {
                throw methodError(methodInfo,
                                  "No @Data.Param(index = "
                                          + index
                                          + ") binding matches JDBC positional parameter in method "
                                          + methodInfo.elementName());
            }
            used.add(index);
            bindings.add(new BindValue(index, expression));
        }
        byIndex.keySet()
                .stream()
                .filter(index -> !used.contains(index))
                .findFirst()
                .ifPresent(index -> {
                    throw methodError(methodInfo,
                                      "@Data.Param(index = "
                                              + index
                                              + ") is not used by SQL markers in method "
                                              + methodInfo.elementName());
                });
        return new StatementBinding(statement, List.copyOf(bindings), null);
    }

    private ParamBinding explicitParamBinding(TypedElementInfo parameter, TypedElementInfo methodInfo) {
        Optional<Annotation> annotation = parameter.findAnnotation(PARAM);
        if (annotation.isEmpty()) {
            return null;
        }
        String name = annotation.get().stringValue().orElse("");
        int index = annotation.get().intValue("index").orElse(-1);
        boolean named = !name.isBlank();
        boolean positional = index != -1;
        if (named && positional) {
            throw methodError(methodInfo,
                              "@Data.Param must declare either value or index, not both, on method parameter "
                                      + parameter.elementName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        if (!named && !positional) {
            throw methodError(methodInfo,
                              "@Data.Param must declare value or index on method parameter "
                                      + parameter.elementName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        if (positional && index < 1) {
            throw methodError(methodInfo,
                              "@Data.Param index must be greater than zero on method parameter "
                                      + parameter.elementName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        return new ParamBinding(name, index, parameter.elementName());
    }

    private BindPrefixBinding explicitBindBinding(TypedElementInfo parameter, TypedElementInfo methodInfo) {
        Optional<Annotation> annotation = parameter.findAnnotation(BIND);
        if (annotation.isEmpty()) {
            return null;
        }
        String prefix = annotation.get().stringValue().orElse("");
        if (prefix.isBlank()) {
            throw methodError(methodInfo,
                              "@Data.Bind prefix is missing on method parameter "
                                      + parameter.elementName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        if (!isIdentifier(prefix)) {
            throw methodError(methodInfo,
                              "@Data.Bind prefix must be a simple identifier on method parameter "
                                      + parameter.elementName()
                                      + " in method "
                                      + methodInfo.elementName());
        }
        return new BindPrefixBinding(prefix, parameter);
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
        Optional<TypeInfo> result = roundContext.typeInfo(typeName)
                .or(() -> codegenContext.typeInfo(typeName));
        if (result.isPresent()) {
            return result;
        }
        TypeName genericTypeName = typeName.genericTypeName();
        if (genericTypeName.equals(typeName)) {
            return Optional.empty();
        }
        return roundContext.typeInfo(genericTypeName)
                .or(() -> codegenContext.typeInfo(genericTypeName));
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
        // Temporary conservative classification until @Data.Query operation kind is finalized.
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

    private static boolean isIdentifier(String value) {
        if (value.isBlank() || !isIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String upperFirst(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * SQL statement plus generated binder expressions for one repository method.
     */
    private record StatementBinding(String statement, List<BindValue> bindings, String planField) {
        private StatementBinding withPlanField(String planField) {
            return new StatementBinding(statement, bindings, planField);
        }
    }

    /**
     * One generated PreparedStatement binding expression.
     */
    private record BindValue(int index, String expression) {
    }

    /**
     * Explicit scalar parameter binding declared by {@code @Data.Param}.
     */
    private record ParamBinding(String name, int index, String expression) {
        private boolean named() {
            return !name.isBlank();
        }

        private boolean positional() {
            return index != -1;
        }
    }

    /**
     * Explicit prefixed object binding declared by {@code @Data.Bind}.
     */
    private record BindPrefixBinding(String prefix, TypedElementInfo parameter) {
    }

    /**
     * One readable accessor used to generate a null-safe {@code @Data.Bind} expression.
     */
    private record Accessor(String methodName, TypeName type) {
    }

    /**
     * Operation metadata derived from one {@code @Data.Query} method.
     */
    private record RepositoryMethodAnalysis(String sql,
                                            StatementBinding binding,
                                            boolean generatedKeys,
                                            boolean query,
                                            TypeName returnType) {
    }
}
