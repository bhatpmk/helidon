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

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.BasePersistenceGenerator;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;
import io.helidon.data.codegen.common.spi.RepositoryGenerator;

/**
 * JDBC provider entry point for the common Helidon Data repository generator.
 * <p>
 * The common repository generator discovers repository interfaces and delegates
 * to this class when the selected provider is {@code jdbc}. JDBC repositories
 * are generated from explicit {@code @Data.Query} SQL, so this class bypasses
 * the common derived-query builders and delegates the implementation details to
 * {@link JdbcRepositoryClassGenerator}.
 */
class JdbcPersistenceGenerator extends BasePersistenceGenerator {

    static final TypeName GENERATOR = TypeName.create(JdbcPersistenceGenerator.class);

    static final String PROVIDER_NAME = "jdbc";

    JdbcPersistenceGenerator() {
        super();
    }

    @Override
    protected String provider() {
        return PROVIDER_NAME;
    }

    @Override
    public PersistenceGenerator.QueryBuilder queryBuilder(RepositoryInfo repositoryInfo) {
        // JDBC methods use user-provided SQL and do not participate in common query derivation.
        throw new CodegenException("JDBC repository generation does not use the common query builder");
    }

    @Override
    public PersistenceGenerator.StatementGenerator statementGenerator() {
        // JDBC statement plans are emitted directly by JdbcRepositoryClassGenerator.
        throw new CodegenException("JDBC repository generation does not use the common statement generator");
    }

    @Override
    protected void generateRepositoryClass(CodegenContext codegenContext,
                                           RoundContext roundContext,
                                           RepositoryGenerator repositoryGenerator,
                                           RepositoryInfo repositoryInfo,
                                           TypeName className,
                                           ClassModel.Builder classModel) {
        JdbcRepositoryClassGenerator.generate(codegenContext, roundContext, repositoryInfo, className, classModel);
    }

    @Override
    protected TypeName repositoryClassName(TypeName baseName) {
        return TypeName.builder(baseName)
                .className(baseName.classNameWithEnclosingNames().replace('.', '_') + "__Jdbc")
                .enclosingNames(List.of())
                .build();
    }

    static CodegenException repositoryError(RepositoryInfo repositoryInfo, String message) {
        return new CodegenException(message, repositoryInfo.interfaceInfo().originatingElement());
    }

    static CodegenException methodError(TypedElementInfo methodInfo, String message) {
        return new CodegenException(message, methodInfo.originatingElement());
    }
}
