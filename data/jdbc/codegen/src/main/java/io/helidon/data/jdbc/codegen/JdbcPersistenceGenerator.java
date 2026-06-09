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
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;
import io.helidon.data.codegen.common.spi.RepositoryGenerator;

/**
 * Persistence generator for explicit SQL JDBC repositories.
 */
final class JdbcPersistenceGenerator implements PersistenceGenerator {

    static final TypeName GENERATOR = TypeName.create(JdbcPersistenceGenerator.class);

    private static final String PROVIDER = "jdbc";
    private static final TypeName DATA_PROVIDER = TypeName.create("io.helidon.data.Data.Provider");

    @Override
    public void generate(CodegenContext codegenContext,
                         RoundContext roundContext,
                         TypeInfo repository,
                         RepositoryGenerator repositoryGenerator) {
        boolean shouldGenerate = repository.findAnnotation(DATA_PROVIDER)
                .flatMap(Annotation::value)
                .orElse(PROVIDER)
                .equals(PROVIDER);

        if (!shouldGenerate) {
            return;
        }

        RepositoryInfo repositoryInfo = repositoryGenerator.createRepositoryInfo(repository, codegenContext);
        TypeName className = TypeName.builder(repository.typeName())
                .className(repository.typeName().className() + "__Jdbc")
                .build();
        ClassModel.Builder classModel = ClassModel.builder();

        JdbcRepositoryClassGenerator.generate(repositoryInfo, className, classModel);

        roundContext.addGeneratedType(className,
                                      classModel,
                                      repositoryInfo.interfaceInfo().typeName(),
                                      repositoryInfo.interfaceInfo().originatingElementValue());
    }

    @Override
    public QueryBuilder queryBuilder(RepositoryInfo repositoryInfo) {
        // JDBC QueryBuilder support belongs with query-by-method-name generation.
        throw new UnsupportedOperationException("JDBC repositories currently generate explicit @Data.Query SQL directly.");
    }

    @Override
    public StatementGenerator statementGenerator() {
        // Probably required when JDBC module starts translating query model into generated JDBC execution statements.
        throw new UnsupportedOperationException("JDBC repositories currently generate explicit @Data.Query SQL directly.");
    }

}
