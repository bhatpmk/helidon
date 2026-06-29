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
import java.util.Objects;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.data.codegen.common.BasePersistenceGenerator;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;
import io.helidon.data.codegen.common.spi.RepositoryGenerator;

/**
 * JDBC persistence generator.
 */
class JdbcPersistenceGenerator extends BasePersistenceGenerator {

    static final TypeName GENERATOR = TypeName.create(JdbcPersistenceGenerator.class);
    private static final TypeName PROVIDER_ANNOTATION = TypeName.create("io.helidon.data.Data.Provider");

    JdbcPersistenceGenerator() {
        super();
    }

    @Override
    public void generate(CodegenContext codegenContext,
                         RoundContext roundContext,
                         TypeInfo interfaceInfo,
                         RepositoryGenerator repositoryGenerator) {
        Objects.requireNonNull(interfaceInfo, "Data repository interface info value is null");
        Objects.requireNonNull(codegenContext, "Codegen context value is null");
        Objects.requireNonNull(repositoryGenerator, "Data repository generator value is null");

        // Keep JDBC opt-in for now so adding this provider cannot change existing Jakarta repository generation.
        boolean selected = interfaceInfo.findAnnotation(PROVIDER_ANNOTATION)
                .flatMap(Annotation::value)
                .map(provider()::equals)
                .orElse(false);
        if (selected) {
            super.generate(codegenContext, roundContext, interfaceInfo, repositoryGenerator);
        }
    }

    @Override
    protected String provider() {
        return "jdbc";
    }

    @Override
    public PersistenceGenerator.QueryBuilder queryBuilder(RepositoryInfo repositoryInfo) {
        throw new CodegenException("JDBC repository generation does not use the common query builder");
    }

    @Override
    public PersistenceGenerator.StatementGenerator statementGenerator() {
        throw new CodegenException("JDBC repository generation does not use the common statement generator");
    }

    @Override
    protected TypeName repositoryClassName(TypeName baseName) {
        return TypeName.builder(baseName)
                .className(baseName.classNameWithEnclosingNames().replace('.', '_') + "__Jdbc")
                .enclosingNames(List.of())
                .build();
    }

    @Override
    protected void generateRepositoryClass(CodegenContext codegenContext,
                                           RoundContext roundContext,
                                           RepositoryGenerator repositoryGenerator,
                                           RepositoryInfo repositoryInfo,
                                           TypeName className,
                                           ClassModel.Builder classModel) {
        JdbcRepositoryClassGenerator.generate(codegenContext,
                                              roundContext,
                                              repositoryInfo,
                                              className,
                                              classModel);
    }
}
