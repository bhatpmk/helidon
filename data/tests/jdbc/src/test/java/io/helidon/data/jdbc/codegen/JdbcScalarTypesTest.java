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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.helidon.builder.api.RuntimeType;
import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.TestCompiler;
import io.helidon.common.Generated;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.data.Data;
import io.helidon.data.codegen.DataGeneratorProvider;
import io.helidon.data.codegen.common.RepositoryCodegenProvider;
import io.helidon.data.jdbc.JdbcClient;
import io.helidon.service.registry.Service;
import io.helidon.transaction.Tx;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcScalarTypesTest {

    private static final Map<TypeName, String> REFERENCE_TYPES = Map.ofEntries(
            Map.entry(TypeName.create(UUID.class), "OTHER"),
            Map.entry(TypeName.create(Boolean.class), "BOOLEAN"),
            Map.entry(TypeName.create(Byte.class), "TINYINT"),
            Map.entry(TypeName.create(Short.class), "SMALLINT"),
            Map.entry(TypeName.create(Integer.class), "INTEGER"),
            Map.entry(TypeName.create(Long.class), "BIGINT"),
            Map.entry(TypeName.create(Float.class), "REAL"),
            Map.entry(TypeName.create(Double.class), "DOUBLE"),
            Map.entry(TypeName.create(BigDecimal.class), "DECIMAL"),
            Map.entry(TypeName.create(String.class), "VARCHAR"),
            Map.entry(TypeName.create(byte[].class), "VARBINARY"),
            Map.entry(TypeName.create(LocalDate.class), "DATE"),
            Map.entry(TypeName.create(LocalTime.class), "TIME"),
            Map.entry(TypeName.create(LocalDateTime.class), "TIMESTAMP"),
            Map.entry(TypeName.create("java.sql.Date"), "DATE"),
            Map.entry(TypeName.create("java.sql.Time"), "TIME"),
            Map.entry(TypeName.create("java.sql.Timestamp"), "TIMESTAMP"));

    @Test
    void assignsTheCanonicalNullTypeToEverySupportedReferenceType() {
        REFERENCE_TYPES.forEach((javaType, jdbcTypeConstant) -> {
            assertThat(javaType.resolvedName(), JdbcScalarTypes.isScalar(javaType), is(true));
            assertThat(javaType.resolvedName(),
                       JdbcScalarTypes.nullJdbcTypeConstant(javaType),
                       is(jdbcTypeConstant));
        });
    }

    @Test
    void normalizesPrimitiveTypesToTheirWrapperMappings() {
        Map.of(boolean.class, Boolean.class,
               byte.class, Byte.class,
               short.class, Short.class,
               int.class, Integer.class,
               long.class, Long.class,
               float.class, Float.class,
               double.class, Double.class)
                .forEach((primitiveType, wrapperType) -> {
                    TypeName primitive = TypeName.create(primitiveType);
                    assertThat(primitiveType.getTypeName(), JdbcScalarTypes.isScalar(primitive), is(true));
                    assertThat(primitiveType.getTypeName(),
                               JdbcScalarTypes.nullJdbcTypeConstant(primitive),
                               is(REFERENCE_TYPES.get(TypeName.create(wrapperType))));
                });
    }

    @Test
    void rejectsTypesOutsideTheScalarContract() {
        assertThat(JdbcScalarTypes.isScalar(TypeName.create(String[].class)), is(false));
        for (TypeName unsupported : List.of(TypeName.create(BigInteger.class),
                                            TypeName.create(OffsetTime.class),
                                            TypeName.create(OffsetDateTime.class))) {
            assertThat(unsupported.resolvedName(), JdbcScalarTypes.isScalar(unsupported), is(false));
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> JdbcScalarTypes.nullJdbcTypeConstant(unsupported));
            assertThat(failure.getMessage(),
                       is("The Helidon Data JDBC provider does not support type '"
                                  + unsupported.resolvedName() + "' as a portable scalar."));
        }
    }

    @Test
    void returnsScalarOnlyForExactSupportedOptionalTypes() {
        TypeName optionalString = TypeName.builder(TypeNames.OPTIONAL)
                .addTypeArgument(TypeNames.STRING)
                .build();
        TypeName optionalList = TypeName.builder(TypeNames.OPTIONAL)
                .addTypeArgument(TypeName.create(List.class))
                .build();
        TypeName optionalWildcard = TypeName.builder(TypeNames.OPTIONAL)
                .addTypeArgument(TypeName.create("? extends java.lang.String"))
                .build();

        assertThat(JdbcScalarTypes.optionalScalarType(optionalString), is(Optional.of(TypeNames.STRING)));
        assertThat(JdbcScalarTypes.optionalScalarType(TypeNames.OPTIONAL), is(Optional.empty()));
        assertThat(JdbcScalarTypes.optionalScalarType(optionalWildcard), is(Optional.empty()));
        assertThat(JdbcScalarTypes.optionalScalarType(optionalList), is(Optional.empty()));
        assertThat(JdbcScalarTypes.optionalScalarType(TypeNames.STRING), is(Optional.empty()));
    }

    @Test
    void compilesUuidParametersResultsRecordsAndKeys() throws IOException {
        String repositorySource = """
                package example;

                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;
                import io.helidon.data.Data;
                import io.helidon.data.jdbc.Jdbc;

                @Data.Repository
                @Data.Provider("jdbc")
                interface UuidRepository {
                    record Projection(UUID id, Optional<UUID> related) {}
                    @Jdbc.Statement("INSERT INTO UUID_VALUE (ID) VALUES (?)")
                    void insert(UUID id);
                    @Jdbc.Statement("UPDATE UUID_VALUE SET ID = :id WHERE ID = :id")
                    @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                    long update(UUID id);
                    @Jdbc.Statement("SELECT ID FROM UUID_VALUE")
                    UUID one();
                    @Jdbc.Statement("SELECT ID FROM UUID_VALUE")
                    Optional<UUID> optional();
                    @Jdbc.Statement("SELECT ID FROM UUID_VALUE ORDER BY ID")
                    List<UUID> list();
                    @Jdbc.Statement("SELECT ID, RELATED FROM UUID_VALUE")
                    Projection projection();
                    @Jdbc.Statement("INSERT INTO UUID_VALUE DEFAULT VALUES")
                    @Jdbc.GeneratedKeys("ID")
                    UUID key();
                    @Jdbc.Statement("INSERT INTO UUID_VALUE DEFAULT VALUES")
                    @Jdbc.GeneratedKeys
                    Optional<UUID> optionalKey();
                    @Jdbc.Statement("INSERT INTO UUID_VALUE DEFAULT VALUES")
                    @Jdbc.GeneratedKeys("ID")
                    List<UUID> keys();
                }
                """;
        TestCompiler.Result result = TestCompiler.builder()
                .currentRelease()
                .printDiagnostics(false)
                .addProcessor(AptProcessor::new)
                .addClasspath(List.of(RuntimeType.class, Data.class, JdbcClient.class, Service.class, Tx.class,
                                      Generated.class, TypeName.class, DataGeneratorProvider.class,
                                      RepositoryCodegenProvider.class, JdbcRepositoryGeneratorProvider.class))
                .addSource("UuidRepository.java", repositorySource)
                .build().compile();
        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/UuidRepository__Jdbc.java"));
        assertThat(source, containsString("JDBCType.OTHER"));
        assertThat(source, containsString("UUID.class"));
        assertThat(Files.exists(result.classOutput().resolve("example/UuidRepository__Jdbc.class")), is(true));
    }
}
