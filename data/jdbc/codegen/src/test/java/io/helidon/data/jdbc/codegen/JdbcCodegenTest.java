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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.CodegenMatchers;
import io.helidon.codegen.testing.TestCompiler;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class JdbcCodegenTest {

    @Test
    void generatesJdbcRepositoryForSqlQuery() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.util.List;
                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query("SELECT id, name FROM PokemonRow WHERE id = :id")
                            PokemonRow find(long id);

                            @Data.Query("SELECT id, name FROM PokemonRow WHERE id = :id")
                            Optional<PokemonRow> findOptional(long id);

                            @Data.Query("SELECT id, name FROM PokemonRow")
                            List<PokemonRow> list();

                            @Data.Query("WITH rows AS (SELECT id, name FROM PokemonRow) SELECT id, name FROM rows")
                            List<PokemonRow> listWithCte();
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated, containsString("class PokemonRepository__Jdbc implements PokemonRepository"));
        assertThat(generated, containsString("private final JdbcClient jdbcClient;"));
        assertThat(generated, not(containsString("private final Executor executor;")));
        assertThat(generated, containsString("public PokemonRow find(long id)"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"SELECT id, name FROM PokemonRow WHERE id = :id\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"id\", id)))"
                                          + ".single(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public Optional<PokemonRow> findOptional(long id)"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"SELECT id, name FROM PokemonRow WHERE id = :id\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"id\", id)))"
                                          + ".optional(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public List<PokemonRow> list()"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"SELECT id, name FROM PokemonRow\")"
                                          + ".list(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public List<PokemonRow> listWithCte()"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"WITH rows AS (SELECT id, name "
                                          + "FROM PokemonRow) SELECT id, name FROM rows\")"
                                          + ".list(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, not(containsString("private static final class Executor")));
    }

    @Test
    void generatesScalarMappingForNonSelectDataQuery() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query("/* leading comment */ DELETE FROM PokemonRow WHERE id = :id")
                            int delete(long id);

                            @Data.Query("WITH stale AS (SELECT id FROM PokemonRow WHERE name = :name) DELETE FROM PokemonRow WHERE id IN (SELECT id FROM stale)")
                            long deleteByName(String name);

                            @Data.Query("CREATE TABLE PokemonAudit (id BIGINT)")
                            void createAuditTable();
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated, CodegenMatchers.matches("""
                //...
                    public int delete(long id) {
                        return jdbcClient.execute("/* leading comment */ DELETE FROM PokemonRow WHERE id = :id").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("id", id))).single(row -> row.value(1, Integer.class));
                    }
                //...
                    public long deleteByName(String name) {
                        return jdbcClient.execute("WITH stale AS (SELECT id FROM PokemonRow WHERE name = :name) DELETE FROM PokemonRow WHERE id IN (SELECT id FROM stale)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).single(row -> row.value(1, Long.class));
                    }
                //...
                    public void createAuditTable() {
                        jdbcClient.execute("CREATE TABLE PokemonAudit (id BIGINT)").updateCount();
                    }
                //...
                """));
    }

    @Test
    void acceptsSqlWithoutClassifyingStatementKind() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query("WITH rows AS (SELECT id, name FROM PokemonRow) UPDATE PokemonRow SET name = :name WHERE id IN (SELECT id FROM rows)")
                            int updateWithCte(String name);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated, CodegenMatchers.matches("""
                //...
                    public int updateWithCte(String name) {
                        return jdbcClient.execute("WITH rows AS (SELECT id, name FROM PokemonRow) UPDATE PokemonRow SET name = :name WHERE id IN (SELECT id FROM rows)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).single(row -> row.value(1, Integer.class));
                    }
                //...
                """));
    }

    private static List<Path> testClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }
}
