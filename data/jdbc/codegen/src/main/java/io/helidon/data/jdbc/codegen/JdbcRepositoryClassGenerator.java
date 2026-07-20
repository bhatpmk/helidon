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
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.Constructor;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeName;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.RepositoryGenerator;

/**
 * Builds the provider-neutral shell around JDBC-generated repository methods.
 */
final class JdbcRepositoryClassGenerator {
    private JdbcRepositoryClassGenerator() {
    }

    static void generate(CodegenContext codegenContext,
                         RepositoryGenerator repositoryGenerator,
                         RepositoryInfo repositoryInfo,
                         TypeName className,
                         ClassModel.Builder classModel,
                         JdbcPersistenceGenerator generator) {
        TypeName repositoryType = repositoryInfo.interfaceInfo().typeName();
        classModel.type(className)
                .copyright(CodegenUtil.copyright(JdbcPersistenceGenerator.GENERATOR,
                                                 repositoryType,
                                                 className))
                .addAnnotation(Annotation.create(JdbcCodegenTypes.SERVICE_SINGLETON))
                .addAnnotation(CodegenUtil.generatedAnnotation(JdbcPersistenceGenerator.GENERATOR,
                                                               repositoryType,
                                                               className,
                                                               "1",
                                                               ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(repositoryType)
                .addField(field -> field.name("jdbcClient")
                        .isFinal(true)
                        .type(JdbcCodegenTypes.JDBC_CLIENT));

        generator.generateRepositoryMethods(repositoryInfo, classModel, codegenContext, repositoryGenerator);
    }

    /**
     * Generates the repository constructor after method planning has identified its mapper dependencies.
     *
     * @param classModel generated repository class
     * @param repositoryInfo repository metadata
     * @param mapperDependencies statically typed mapper-service dependencies
     */
    static void generateConstructor(ClassModel.Builder classModel,
                                    RepositoryInfo repositoryInfo,
                                    Iterable<JdbcMethodGenerator.MapperDependency> mapperDependencies) {
        Constructor.Builder constructor = Constructor.builder()
                .accessModifier(AccessModifier.PACKAGE_PRIVATE);
        Annotation provider = Annotation.builder()
                .typeName(JdbcCodegenTypes.DATA_PROVIDER_TYPE)
                .property("value", "jdbc")
                .build();
        Annotation defaultNamed = Annotation.builder()
                .typeName(JdbcCodegenTypes.SERVICE_NAMED)
                .property("value", JdbcCodegenTypes.DEFAULT_NAME)
                .build();

        Annotation persistenceUnit = repositoryInfo.interfaceInfo()
                .findAnnotation(JdbcCodegenTypes.DATA_PERSISTENCE_UNIT)
                .orElse(null);
        String name = persistenceUnit == null
                ? JdbcCodegenTypes.DEFAULT_NAME
                : persistenceUnit.stringValue().orElse(JdbcCodegenTypes.DEFAULT_NAME);
        boolean required = persistenceUnit == null || persistenceUnit.booleanValue("required").orElse(true);

        if (!JdbcCodegenTypes.DEFAULT_NAME.equals(name)) {
            Annotation named = Annotation.builder()
                    .typeName(JdbcCodegenTypes.SERVICE_NAMED)
                    .property("value", name)
                    .build();
            if (required) {
                constructor.addParameter(Parameter.builder()
                                                 .name("jdbcClient")
                                                 .type(JdbcCodegenTypes.JDBC_CLIENT)
                                                 .addAnnotation(named)
                                                 .addAnnotation(provider)
                                                 .build())
                        .addContentLine("this.jdbcClient = jdbcClient;");
            } else {
                TypeName optionalClient = TypeName.builder(JdbcCodegenTypes.OPTIONAL)
                        .addTypeArgument(JdbcCodegenTypes.JDBC_CLIENT)
                        .build();
                TypeName clientSupplier = TypeName.builder(JdbcCodegenTypes.SUPPLIER)
                        .addTypeArgument(JdbcCodegenTypes.JDBC_CLIENT)
                        .build();
                constructor.addParameter(Parameter.builder()
                                                 .name("namedJdbcClient")
                                                 .type(optionalClient)
                                                 .addAnnotation(named)
                                                 .addAnnotation(provider)
                                                 .build())
                        .addParameter(Parameter.builder()
                                              .name("jdbcClient")
                                              .type(clientSupplier)
                                              .addAnnotation(defaultNamed)
                                              .addAnnotation(provider)
                                              .build())
                        .addContentLine("this.jdbcClient = namedJdbcClient.orElseGet(jdbcClient);");
            }
        } else {
            constructor.addParameter(Parameter.builder()
                                             .name("jdbcClient")
                                             .type(JdbcCodegenTypes.JDBC_CLIENT)
                                             .addAnnotation(defaultNamed)
                                             .addAnnotation(provider)
                                             .build())
                    .addContentLine("this.jdbcClient = jdbcClient;");
        }
        for (JdbcMethodGenerator.MapperDependency dependency : mapperDependencies) {
            constructor.addParameter(Parameter.builder()
                                             .name(dependency.parameterName())
                                             .type(dependency.parameterType())
                                             .build())
                    .addContent("this.")
                    .addContent(dependency.fieldName())
                    .addContent(" = ")
                    .addContent(dependency.parameterName());
            if (dependency.optional()) {
                constructor.addContent(".orElse(")
                        .addContent(dependency.fallbackFieldName())
                        .addContent(")");
            }
            constructor.addContentLine(";");
        }
        classModel.addConstructor(constructor);
    }
}
