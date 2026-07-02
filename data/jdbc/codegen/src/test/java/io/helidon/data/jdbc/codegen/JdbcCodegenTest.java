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
    void generatesScopedStreamingCallbacks() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.util.function.Consumer;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id")
                            void withAll(Consumer<Iterable<PokemonRow>> action);

                            @Data.Query("SELECT id, name FROM PokemonRow WHERE id > :afterId ORDER BY id")
                            void withAfter(long afterId, Consumer<Iterable<PokemonRow>> action);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated,
                   containsString("jdbcClient.execute(\"SELECT id, name FROM PokemonRow ORDER BY id\")"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".withRows(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)), action);"));
        assertThat(generated,
                   containsString("jdbcClient.execute(\"SELECT id, name FROM PokemonRow WHERE id > :afterId "
                                          + "ORDER BY id\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"afterId\", "
                                          + "afterId)))"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".withRows(row -> new PokemonRow(row.value(\"id\", Long.class), "
                                          + "row.value(\"name\", String.class)), action);"));
        assertThat(generated, not(containsString("JdbcParameter.create(\"action\"")));
    }

    @Test
    void rejectsUnscopedStreamingCallbackType() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.util.function.Consumer;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {
                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id")
                            void withRows(Consumer<PokemonRow> action);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC streaming callback parameter must have type Consumer<Iterable<T>>: withRows"));
    }

    @Test
    void rejectsStreamingCallbackWithValueReturn() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.util.function.Consumer;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {
                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id")
                            long withRows(Consumer<Iterable<PokemonRow>> action);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC callback streaming methods must return void: withRows"));
    }

    @Test
    void rejectsDirectStreamReturn() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.util.stream.Stream;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {
                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id")
                            Stream<PokemonRow> rows();
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC direct Stream<T> returns do not provide reliable resource ownership"));
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
                        return jdbcClient.execute("/* leading comment */ DELETE FROM PokemonRow WHERE id = :id").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("id", id))).readColumns(1).resultScalar(Integer.class);
                    }
                //...
                    public long deleteByName(String name) {
                        return jdbcClient.execute("WITH stale AS (SELECT id FROM PokemonRow WHERE name = :name) DELETE FROM PokemonRow WHERE id IN (SELECT id FROM stale)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).readColumns(1).resultScalar(Long.class);
                    }
                //...
                    public void createAuditTable() {
                        jdbcClient.execute("CREATE TABLE PokemonAudit (id BIGINT)").discard();
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
                        return jdbcClient.execute("WITH rows AS (SELECT id, name FROM PokemonRow) UPDATE PokemonRow SET name = :name WHERE id IN (SELECT id FROM rows)").params(List.of(io.helidon.data.jdbc.JdbcParameter.create("name", name))).readColumns(1).resultScalar(Integer.class);
                    }
                //...
                """));
    }

    @Test
    void generatesExplicitGeneratedKeyTerminals() throws IOException {
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
                        interface PokemonRepository extends Data.GenericRepository<PokemonKey, Long> {

                            @Data.Query("INSERT INTO Pokemon (name) VALUES (:name)")
                            @Data.GeneratedKeys
                            long insert(String name);

                            @Data.Query("INSERT INTO Pokemon (name) VALUES (:name)")
                            @Data.GeneratedKeys(columns = "ID")
                            Optional<Long> insertOptional(String name);

                            @Data.Query("INSERT INTO Pokemon (name) VALUES (:name)")
                            @Data.GeneratedKeys(columns = {"ID", "VERSION"})
                            List<PokemonKey> insertKeys(String name);
                        }

                        record PokemonKey(long id, long version) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"INSERT INTO Pokemon (name) VALUES (:name)\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"name\", name)))"
                                          + ".readColumns(1)"
                                          + ".generatedKey(row -> row.value(1, Long.class));"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"INSERT INTO Pokemon (name) VALUES (:name)\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"name\", name)))"
                                          + ".generatedKeyColumns(\"ID\")"
                                          + ".readColumns(1)"
                                          + ".optionalGeneratedKey(row -> row.value(1, Long.class));"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"INSERT INTO Pokemon (name) VALUES (:name)\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"name\", name)))"
                                          + ".generatedKeyColumns(\"ID\", \"VERSION\")"
                                          + ".readColumns(\"id\", \"version\")"
                                          + ".generatedKeys(row -> new PokemonKey(row.value(\"id\", Long.class), "
                                          + "row.value(\"version\", Long.class)));"));
    }

    @Test
    void rejectsGeneratedKeysWithoutAValueReturn() {
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

                            @Data.Query("INSERT INTO Pokemon (name) VALUES (:name)")
                            @Data.GeneratedKeys
                            void insert(String name);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC @Data.GeneratedKeys method must return a generated key: insert"));
    }

    @Test
    void rejectsGeneratedKeysForCallableMethods() {
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

                            @Data.Call("{call create_pokemon(?)}")
                            @Data.GeneratedKeys
                            long insert(String name);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("@Data.GeneratedKeys requires @Data.Query on JDBC repository method: insert"));
    }

    @Test
    void rejectsDuplicateGeneratedKeyColumns() {
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

                            @Data.Query("INSERT INTO Pokemon (name) VALUES (:name)")
                            @Data.GeneratedKeys(columns = {"ID", "ID"})
                            long insert(String name);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("@Data.GeneratedKeys contains duplicate column name \"ID\" in method insert"));
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

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Call("{? = call ABS(?)}")
                            @Data.Out(index = 1, name = "absolute", type = Types.INTEGER)
                            int absolute(@Data.In(type = Types.INTEGER) int value);

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

                            @Data.Call("{call count_by_country(:country, :count)}")
                            @Data.Out(index = 2, name = "count", type = Types.INTEGER)
                            int countByCountry(@Data.In(index = 1, name = "country", type = Types.VARCHAR) String nation);

                            @Data.Call("{? = call locate(?, ?)}")
                            @Data.Out(index = 1, name = "result", type = Types.INTEGER)
                            int locate(@Data.In(index = 3, type = Types.VARCHAR, typeName = "VARCHAR") String name,
                                       @Data.In(index = 2, type = Types.INTEGER) int id);

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
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(value)"
                                          + ".withSqlType(4)))"
                                          + ".outParam(1, \"absolute\", 4)"
                                          + ".outParam(\"absolute\", Integer.class);"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{call increment(?)}\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(value)"
                                          + ".withSqlType(4)))"
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
                   containsString("return jdbcClient.execute(\"{call count_by_country(:country, :count)}\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"country\", nation)"
                                          + ".withSqlType(12)))"
                                          + ".outParam(2, \"count\", 4)"
                                          + ".outParam(\"count\", Integer.class);"));
        assertThat(generated,
                   containsString("return jdbcClient.execute(\"{? = call locate(?, ?)}\")"
                                          + ".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(id)"
                                          + ".withSqlType(4), io.helidon.data.jdbc.JdbcParameter.create(name)"
                                          + ".withSqlType(12).withTypeName(\"VARCHAR\")))"
                                          + ".outParam(1, \"result\", 4)"
                                          + ".outParam(\"result\", Integer.class);"));
        assertThat(generated, not(containsString("private final Executor executor;")));
    }

    @Test
    void rejectsDataInOnOrdinaryQuery() {
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

                            @Data.Query("SELECT id, name FROM PokemonRow WHERE id = :id")
                            PokemonRow find(@Data.In(index = 1) long id);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("@Data.In applies only to @Data.Call method parameters: find"));
    }

    @Test
    void rejectsDataInScaleWithoutSqlType() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import java.sql.Types;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Call("{? = call ABS(?)}")
                            @Data.Out(index = 1, name = "result", type = Types.INTEGER)
                            int absolute(@Data.In(index = 2, scale = 2) int value);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC @Data.In scale and typeName require an explicit type: value"));
    }

    @Test
    void rejectsDuplicateDataInIndexes() {
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

                            @Data.Call("{call compare(?, ?)}")
                            void compare(@Data.In(index = 1) int first,
                                         @Data.In(index = 1) int second);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC @Data.Call declares duplicate input parameter index 1"));
    }

    @Test
    void generatesSqlLevelPageAndOffsetSliceTerminals() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;
                        import io.helidon.data.Page;
                        import io.helidon.data.PageRequest;
                        import io.helidon.data.Slice;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query(
                                    value = "SELECT id, name FROM PokemonRow WHERE type = :type ORDER BY id "
                                            + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset",
                                    count = "SELECT COUNT(*) FROM PokemonRow WHERE type = :type")
                            Page<PokemonRow> pageByType(String type, PageRequest request);

                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id "
                                    + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset")
                            Slice<PokemonRow> slice(PageRequest request);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated, containsString("public Page<PokemonRow> pageByType(String type, PageRequest request)"));
        assertThat(generated,
                   containsString(".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"type\", type)))"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".page(request, \"SELECT COUNT(*) FROM PokemonRow WHERE type = :type\", "
                                          + "row -> new PokemonRow("));
        assertThat(generated, containsString("public Slice<PokemonRow> slice(PageRequest request)"));
        assertThat(generated,
                   containsString(".readColumns(\"id\", \"name\").slice(request, row -> new PokemonRow("));
    }

    @Test
    void generatesSqlLevelKeysetSliceTerminal() throws IOException {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;
                        import io.helidon.data.PageRequest;
                        import io.helidon.data.Slice;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {

                            @Data.Query("SELECT id, name FROM PokemonRow WHERE id > :afterId ORDER BY id "
                                    + "LIMIT :__helidon_page_size")
                            Slice<PokemonRow> after(long afterId, PageRequest request);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = Files.readString(result.sourceOutput().resolve("com/example/PokemonRepository__Jdbc.java"));
        assertThat(generated,
                   containsString(".params(List.of(io.helidon.data.jdbc.JdbcParameter.create(\"afterId\", afterId)))"
                                          + ".readColumns(\"id\", \"name\")"
                                          + ".slice(request, row -> new PokemonRow("));
    }

    @Test
    void rejectsPageWithoutCountSql() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;
                        import io.helidon.data.Page;
                        import io.helidon.data.PageRequest;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {
                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id "
                                    + "LIMIT :__helidon_page_size OFFSET :__helidon_page_offset")
                            Page<PokemonRow> page(PageRequest request);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(), containsString("JDBC Page methods require @Data.Query count SQL"));
    }

    @Test
    void rejectsSliceWithoutSqlLimitBinding() {
        var result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .printDiagnostics(false)
                .addClasspathEntries(testClasspath())
                .addSource("com/example/PokemonRepository.java", """
                        package com.example;

                        import io.helidon.data.Data;
                        import io.helidon.data.PageRequest;
                        import io.helidon.data.Slice;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository extends Data.GenericRepository<PokemonRow, Long> {
                            @Data.Query("SELECT id, name FROM PokemonRow ORDER BY id")
                            Slice<PokemonRow> slice(PageRequest request);
                        }

                        record PokemonRow(long id, String name) {
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(result.diagnostics().toString(),
                   containsString("JDBC Page and Slice SQL must use named parameters"));
    }

    private static List<Path> testClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }
}
