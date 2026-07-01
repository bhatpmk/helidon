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
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".single(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public Optional<PokemonRow> findOptional(long id)"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"SELECT id, name FROM PokemonRow WHERE id = :id\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"id\", id)))"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".optional(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public List<PokemonRow> list()"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"SELECT id, name FROM PokemonRow\")"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".list(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated, containsString("public List<PokemonRow> listWithCte()"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"WITH rows AS (SELECT id, name "
                                          + "FROM PokemonRow) SELECT id, name FROM rows\")"
                                          + ".readColumns(\"id\", \"name\")"
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
                        return jdbcClient.execute("/* leading comment */ DELETE FROM PokemonRow WHERE id = :id").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("id", id))).readColumns(1).single(row -> row.value(1, Integer.class));
                    }
                //...
                    public long deleteByName(String name) {
                        return jdbcClient.execute("WITH stale AS (SELECT id FROM PokemonRow WHERE name = :name) DELETE FROM PokemonRow WHERE id IN (SELECT id FROM stale)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).readColumns(1).single(row -> row.value(1, Long.class));
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
                        return jdbcClient.execute("WITH rows AS (SELECT id, name FROM PokemonRow) UPDATE PokemonRow SET name = :name WHERE id IN (SELECT id FROM rows)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).readColumns(1).single(row -> row.value(1, Integer.class));
                    }
                //...
                """));
    }

    @Test
    void generatesJdbcRepositoryForStoredProcedureCalls() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.sql.Types;
                        import java.util.List;
                        import java.util.Map;
                        import java.util.Optional;
                        import java.util.stream.Stream;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Call("{? = call ABS(?)}")
                            @Data.Out(index = 1, name = "absolute", type = Types.INTEGER)
                            int absolute(int value);

                            @Data.Call("{call increment(?)}")
                            Integer increment(@Data.InOut(index = 1, name = "value", type = Types.INTEGER) int value);

                            @Data.Call("{call status(?)}")
                            @Data.Out(index = 1, name = "status", type = Types.INTEGER)
                            Optional<Integer> status();

                            @Data.Call("{call out_values(?)}")
                            @Data.Out(index = 1, name = "status", type = Types.INTEGER)
                            Map<String, Object> outValues();

                            @Data.Call("{call list_pokemon(?)}")
                            @Data.OutCursor(index = 1, name = "rows")
                            List<PokemonRow> listFromCursor();

                            @Data.Call("{call stream_pokemon(?)}")
                            @Data.OutCursor(index = 1, name = "rows")
                            Stream<PokemonRow> streamFromCursor();
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated, containsString("private final JdbcClient jdbcClient;"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{? = call ABS(?)}\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(value)))"
                                          + ".outParam(1, \"absolute\", 4)"
                                          + ".outParam(\"absolute\", Integer.class);"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{call increment(?)}\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(value)))"
                                          + ".outParam(1, \"value\", 4)"
                                          + ".outParam(\"value\", Integer.class);"));
        assertThat(generated,
                   containsString("return Optional.ofNullable(jdbcClient.execute(\"{call status(?)}\")"
                                          + ".outParam(1, \"status\", 4)"
                                          + ".outParam(\"status\", Integer.class));"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{call out_values(?)}\")"
                                          + ".outParam(1, \"status\", 4)"
                                          + ".outParams();"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{call list_pokemon(?)}\")"
                                          + ".outCursor(1, \"rows\", 2012)"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".outCursor(\"rows\", row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)));"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{call stream_pokemon(?)}\")"
                                          + ".outCursor(1, \"rows\", 2012)"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".outCursor(\"rows\", row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class))).stream();"));
        assertThat(generated, not(containsString("private final Executor executor;")));
    }

    private static List<Path> testClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }
}
