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

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Constructor;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.RepositoryInfo;

import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.GENERATOR;
import static io.helidon.data.jdbc.codegen.JdbcTypes.CALL_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.CLIENT;
import static io.helidon.data.jdbc.codegen.JdbcTypes.GENERIC_REPOSITORY;
import static io.helidon.data.jdbc.codegen.JdbcTypes.INJECTION_SINGLETON;
import static io.helidon.data.jdbc.codegen.JdbcTypes.PU_NAME_ANNOTATION;
import static io.helidon.data.jdbc.codegen.JdbcTypes.QUERY_ANNOTATION;

final class JdbcRepositoryClassGenerator {

    private static final String JDBC_CLIENT = "jdbcClient";

    private JdbcRepositoryClassGenerator() {
    }

    static void generate(CodegenContext codegenContext,
                         RoundContext roundContext,
                         RepositoryInfo repositoryInfo,
                         TypeName className,
                         ClassModel.Builder classModel) {
        TypeName repositoryInterface = repositoryInfo.interfaceInfo().typeName();
        validateRepositoryShape(repositoryInfo);

        classModel.type(className)
                .copyright(CodegenUtil.copyright(GENERATOR, repositoryInterface, className))
                .addAnnotation(INJECTION_SINGLETON)
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR, repositoryInterface, className, "1", ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(repositoryInterface);

        generateFields(classModel);
        generateConstructor(classModel, repositoryInfo);

        JdbcQueryMethodsGenerator.generate(repositoryInfo, classModel, codegenContext);

    }

    private static void validateRepositoryShape(RepositoryInfo repositoryInfo) {
        repositoryInfo.interfaceNames()
                .stream()
                .filter(interfaceName -> !interfaceName.equals(GENERIC_REPOSITORY))
                .findFirst()
                .ifPresent(interfaceName -> {
                    throw new CodegenException("JDBC repositories currently support Data.GenericRepository and "
                                                       + "explicit @Data.Query methods only. Unsupported repository "
                                                       + "interface: " + interfaceName,
                                               repositoryInfo.interfaceInfo().originatingElement());
                });

        repositoryInfo.interfaceInfo()
                .elementInfo()
                .stream()
                .filter(method -> method.kind() == ElementKind.METHOD)
                .filter(method -> !method.elementModifiers().contains(Modifier.DEFAULT))
                .filter(method -> !method.elementModifiers().contains(Modifier.STATIC))
                .filter(method -> !method.hasAnnotation(QUERY_ANNOTATION))
                .filter(method -> !method.hasAnnotation(CALL_ANNOTATION))
                .findFirst()
                .ifPresent(method -> {
                    throw methodError(method, "JDBC repository methods must be annotated with @Data.Query or @Data.Call: "
                            + method.elementName());
                });
    }

    static CodegenException methodError(TypedElementInfo method, String message) {
        return new CodegenException(message, method.originatingElement());
    }

    private static void generateFields(ClassModel.Builder classModel) {
        classModel.addField(builder -> builder.name(JDBC_CLIENT)
                .isFinal(true)
                .type(CLIENT));
    }

    private static void generateConstructor(ClassModel.Builder classModel, RepositoryInfo repositoryInfo) {
        var ctr = Constructor.builder()
                .accessModifier(AccessModifier.PACKAGE_PRIVATE);

        boolean hasNamed = false;
        String name = null;
        boolean nameRequired = false;

        if (repositoryInfo.interfaceInfo().hasAnnotation(PU_NAME_ANNOTATION)) {
            Annotation persistenceUnit = repositoryInfo.interfaceInfo().annotation(PU_NAME_ANNOTATION);
            name = persistenceUnit.value().orElse("@default");
            nameRequired = persistenceUnit.booleanValue("required").orElse(false);
            hasNamed = !name.equals("@default");
        }

        if (hasNamed) {
            Annotation named = Annotation.builder()
                    .typeName(TypeName.create("io.helidon.service.registry.Service.Named"))
                    .property("value", name)
                    .build();
            if (nameRequired) {
                ctr.addParameter(Parameter.builder()
                                         .addAnnotation(named)
                                         .name(JDBC_CLIENT)
                                         .type(CLIENT)
                                         .build())
                        .addContentLine("this.jdbcClient = jdbcClient;");
            } else {
                ctr.addParameter(Parameter.builder()
                                         .addAnnotation(named)
                                         .name("namedJdbcClient")
                                         .type(TypeName.builder(TypeNames.OPTIONAL)
                                                       .addTypeArgument(CLIENT)
                                                       .build())
                                         .build())
                        .addParameter(Parameter.builder()
                                              .name("jdbcClientSupplier")
                                              .type(TypeName.builder(TypeNames.SUPPLIER)
                                                            .addTypeArgument(CLIENT)
                                                            .build())
                                              .build())
                        .addContentLine("this.jdbcClient = namedJdbcClient.orElseGet(jdbcClientSupplier);");
            }
        } else {
            ctr.addParameter(Parameter.builder()
                                     .name(JDBC_CLIENT)
                                     .type(CLIENT)
                                     .build())
                    .addContentLine("this.jdbcClient = jdbcClient;");
        }

        classModel.addConstructor(ctr);
    }
}
