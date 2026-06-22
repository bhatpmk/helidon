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

import java.util.List;
import java.util.Locale;

import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Constructor;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.RepositoryInfo;

import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.GENERATOR;

/**
 * Generates the JDBC implementation class for a repository interface.
 */
final class JdbcRepositoryClassGenerator {

    private static final TypeName DATA_QUERY = TypeName.create("io.helidon.data.Data.Query");
    private static final TypeName JDBC_EXECUTOR = TypeName.create("io.helidon.data.jdbc.JdbcRepositoryExecutor");
    private static final TypeName JDBC_PARAMETERS = TypeName.create("io.helidon.data.jdbc.JdbcParameters");
    private static final Annotation INJECTION_SINGLETON = Annotation.create(TypeName.create(
            "io.helidon.service.registry.Service.Singleton"));
    private static final Annotation PROVIDER_TYPE = Annotation.create(TypeName.create("io.helidon.data.Data.ProviderType"),
                                                                      "jdbc");

    private JdbcRepositoryClassGenerator() {
    }

    static void generate(RepositoryInfo repositoryInfo,
                         TypeName className,
                         ClassModel.Builder classModel) {
        TypeName repositoryInterface = repositoryInfo.interfaceInfo().typeName();

        classModel.type(className)
                .copyright(CodegenUtil.copyright(GENERATOR,
                                                 repositoryInterface,
                                                 className))
                .addAnnotation(INJECTION_SINGLETON)
                .addAnnotation(PROVIDER_TYPE)
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR,
                                                               repositoryInterface,
                                                               className,
                                                               "1",
                                                               ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(repositoryInterface);

        generateFields(classModel);
        generateConstructor(classModel);
        generateRepositoryMethods(repositoryInfo, classModel);
        generateCloseMethod(classModel);
    }

    private static void generateFields(ClassModel.Builder classModel) {
        classModel.addField(builder -> builder.name("executor")
                .isFinal(true)
                .type(JDBC_EXECUTOR));
    }

    private static void generateConstructor(ClassModel.Builder classModel) {
        Constructor.Builder constructor = Constructor.builder()
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addParameter(Parameter.builder()
                                      .name("executor")
                                      .type(JDBC_EXECUTOR)
                                      .build())
                .addContent("this.executor = executor;");
        classModel.addConstructor(constructor);
    }

    private static void generateRepositoryMethods(RepositoryInfo repositoryInfo, ClassModel.Builder classModel) {
        repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(info -> info.kind() == ElementKind.METHOD)
                .forEach(methodInfo -> classModel.addMethod(builder -> generateMethod(builder, methodInfo)));
    }

    private static void generateMethod(Method.Builder builder, TypedElementInfo methodInfo) {
        Annotation query = methodInfo.findAnnotation(DATA_QUERY)
                .orElseThrow(() -> new CodegenException("JDBC repositories require @Data.Query on method "
                                                                 + methodInfo.elementName(),
                                                         methodInfo.originatingElement()));
        String sql = query.value()
                .orElseThrow(() -> new CodegenException("@Data.Query annotation value is missing",
                                                        methodInfo.originatingElement()));

        builder.name(methodInfo.elementName())
                .returnType(methodInfo.typeName())
                .addAnnotation(Annotations.OVERRIDE);
        methodInfo.parameterArguments()
                .forEach(param -> builder.addParameter(Parameter.builder()
                                                              .name(param.elementName())
                                                              .type(param.typeName())
                                                              .build()));

        if (isDml(sql)) {
            generateDml(builder, methodInfo, sql);
        } else {
            generateQuery(builder, methodInfo, sql);
        }
    }

    private static void generateQuery(Method.Builder builder, TypedElementInfo methodInfo, String sql) {
        TypeName returnType = methodInfo.typeName();
        if (returnType.isList() || TypeNames.COLLECTION.equals(returnType.genericTypeName())) {
            builder.addContent("return executor.queryList(");
            addSqlAndResultType(builder, sql, genericArgument(methodInfo), methodInfo.parameterArguments());
            builder.addContentLine(");");
        } else if (returnType.isOptional()) {
            builder.addContent("return executor.queryOptional(");
            addSqlAndResultType(builder, sql, genericArgument(methodInfo), methodInfo.parameterArguments());
            builder.addContentLine(");");
        } else {
            builder.addContent("return executor.queryOne(");
            addSqlAndResultType(builder, sql, returnType, methodInfo.parameterArguments());
            builder.addContentLine(");");
        }
    }

    private static void generateDml(Method.Builder builder, TypedElementInfo methodInfo, String sql) {
        TypeName returnType = methodInfo.typeName();
        if (TypeNames.PRIMITIVE_VOID.equals(returnType) || TypeNames.BOXED_VOID.equals(returnType)) {
            builder.addContent("executor.update(");
            addSqlAndParameters(builder, sql, methodInfo.parameterArguments());
            builder.addContentLine(");");
        } else if (TypeNames.PRIMITIVE_BOOLEAN.equals(returnType) || TypeNames.BOXED_BOOLEAN.equals(returnType)) {
            builder.addContent("return executor.update(");
            addSqlAndParameters(builder, sql, methodInfo.parameterArguments());
            builder.addContentLine(") > 0;");
        } else if (TypeNames.PRIMITIVE_INT.equals(returnType) || TypeNames.BOXED_INT.equals(returnType)) {
            builder.addContent("return (int) executor.update(");
            addSqlAndParameters(builder, sql, methodInfo.parameterArguments());
            builder.addContentLine(");");
        } else if (TypeNames.PRIMITIVE_LONG.equals(returnType) || TypeNames.BOXED_LONG.equals(returnType)) {
            builder.addContent("return executor.update(");
            addSqlAndParameters(builder, sql, methodInfo.parameterArguments());
            builder.addContentLine(");");
        } else {
            throw new CodegenException("JDBC DML method "
                                               + methodInfo.elementName()
                                               + " must return void, boolean, int, or long.",
                                       methodInfo.originatingElement());
        }
    }

    private static void addSqlAndResultType(Method.Builder builder,
                                            String sql,
                                            TypeName resultType,
                                            List<TypedElementInfo> params) {
        builder.addContent(javaString(sql))
                .addContent(", ")
                .addContent(resultType.genericTypeName())
                .addContent(".class, ");
        addParameters(builder, params);
    }

    private static void addSqlAndParameters(Method.Builder builder, String sql, List<TypedElementInfo> params) {
        builder.addContent(javaString(sql))
                .addContent(", ");
        addParameters(builder, params);
    }

    private static void addParameters(Method.Builder builder, List<TypedElementInfo> params) {
        if (params.isEmpty()) {
            builder.addContent(JDBC_PARAMETERS)
                    .addContent(".empty()");
            return;
        }
        builder.addContent(JDBC_PARAMETERS)
                .addContent(".of(");
        for (int i = 0; i < params.size(); i++) {
            TypedElementInfo param = params.get(i);
            if (i > 0) {
                builder.addContent(", ");
            }
            builder.addContent(javaString(param.elementName()))
                    .addContent(", ")
                    .addContent(param.elementName());
        }
        builder.addContent(")");
    }

    private static TypeName genericArgument(TypedElementInfo methodInfo) {
        List<TypeName> typeArguments = methodInfo.typeName().typeArguments();
        if (typeArguments.isEmpty()) {
            throw new CodegenException("Missing generic return type argument for method " + methodInfo.elementName(),
                                       methodInfo.originatingElement());
        }
        return typeArguments.getFirst();
    }

    private static boolean isDml(String sql) {
        String trimmed = sql.stripLeading().toUpperCase(Locale.ROOT);
        return trimmed.startsWith("INSERT")
                || trimmed.startsWith("UPDATE")
                || trimmed.startsWith("DELETE")
                || trimmed.startsWith("MERGE");
    }

    private static String javaString(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
            case '\\' -> builder.append("\\\\");
            case '"' -> builder.append("\\\"");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> builder.append(ch);
            }
        }
        return builder.append('"').toString();
    }

    private static void generateCloseMethod(ClassModel.Builder classModel) {
        classModel.addMethod(close -> close
                .name("close")
                .addAnnotation(Annotation.create(TypeName.create("io.helidon.service.registry.Service.PreDestroy")))
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addContentLine("this.executor.close();"));
    }
}
