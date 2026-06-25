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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.TestCompiler;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

final class JdbcPersistenceGeneratorProviderTest {

    private JdbcPersistenceGeneratorProviderTest() {
        super();
    }

    @Test
    void testCreate() {
        PersistenceGenerator generator = new JdbcPersistenceGeneratorProvider().create();

        assertThat(generator, notNullValue());
        assertThat(generator, instanceOf(JdbcPersistenceGenerator.class));
    }

    @Test
    void testGenerateRepositoryImplementation() throws IOException {
        TestCompiler.Result result = TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .addOption("-Xlint:none")
                .addClasspathEntries(List.of(moduleClasses("common/common"),
                                             moduleClasses("common/types"),
                                             moduleClasses("data/data"),
                                             moduleClasses("data/jdbc/jdbc"),
                                             moduleClasses("service/registry")))
                .printDiagnostics(false)
                .addSource("example/Pokemon.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Map(value = "pokemon_id", target = "id")
                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonType.java", """
                        package example;

                        public record PokemonType(String name) {
                        }
                        """)
                .addSource("example/PokemonDetail.java", """
                        package example;

                        public record PokemonDetail(long id, String name, PokemonType type) {
                        }
                        """)
                .addSource("example/PokemonSummary.java", """
                        package example;

                        public class PokemonSummary {
                            private final long id;
                            private final String name;

                            public PokemonSummary(long id, String name) {
                                this.id = id;
                                this.name = name;
                            }
                        }
                        """)
                .addSource("example/PokemonBean.java", """
                        package example;

                        public class PokemonBean {
                            private long id;
                            private String name;

                            public void setId(long id) {
                                this.id = id;
                            }

                            public void setName(String name) {
                                this.name = name;
                            }
                        }
                        """)
                .addSource("example/PokemonBoxed.java", """
                        package example;

                        public record PokemonBoxed(Long id, Integer rank, Boolean active) {
                        }
                        """)
                .addSource("example/Range.java", """
                        package example;

                        public record Range<T>(T from, T to) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public record SearchRequest(String name, Range<Long> idRange) {
                        }
                        """)
                .addSource("example/SearchBean.java", """
                        package example;

                        public class SearchBean {
                            public String getType() {
                                return null;
                            }

                            public Range<Long> range() {
                                return null;
                            }
                        }
                        """)
                .addSource("example/PokemonDetailMapping.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Mapper(target = PokemonDetail.class)
                        @Data.Map(source = "pokemon_id", target = "id")
                        @Data.Map(source = "pokemon_name", target = "name")
                        @Data.Map(source = "type_name", target = "type.name")
                        interface PokemonDetailMapping {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;
                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id as pokemon_id, name from pokemon where name = :name")
                            List<Pokemon> findByName(String name);

                            @Data.Query("select id as pokemon_id, name from pokemon where id = ?")
                            Optional<Pokemon> findById(long id);

                            @Data.Query("select id as pokemon_id, name from pokemon where name = :name")
                            List<Pokemon> findByExplicitName(@Data.Param("name") String pokemonName);

                            @Data.Query("select id as pokemon_id, name from pokemon where id = ?")
                            Optional<Pokemon> findByExplicitId(@Data.Param(index = 1) long pokemonId);

                            @Data.Query("select count(*) from pokemon")
                            long countAll();

                            @Data.Query("select id, name from pokemon")
                            List<PokemonSummary> listSummaries();

                            @Data.Query("select id, name from pokemon where id = :id")
                            Optional<PokemonBean> findBean(long id);

                            @Data.Query("select id, rank, active from pokemon where id = :id")
                            Optional<PokemonBoxed> findBoxed(long id);

                            @Data.Query(\"\"\"
                                    select id as pokemon_id, name
                                    from pokemon
                                    where (:q.name is null or name = :q.name)
                                      and (:q.idRange.from is null or id >= :q.idRange.from)
                                      and (:q.idRange.to is null or id <= :q.idRange.to)
                                    \"\"\")
                            List<Pokemon> search(@Data.Bind("q") SearchRequest query);

                            @Data.Query(\"\"\"
                                    select id as pokemon_id, name
                                    from pokemon
                                    where (:bean.type is null or type = :bean.type)
                                      and (:bean.range.from is null or id >= :bean.range.from)
                                    \"\"\")
                            List<Pokemon> searchBean(@Data.Bind("bean") SearchBean bean);

                            @Data.Query(\"\"\"
                                    select p.id as pokemon_id, p.name as pokemon_name, t.name as type_name
                                    from pokemon p
                                    join type t on t.id = p.type_id
                                    \"\"\")
                            @Data.MapWith(PokemonDetailMapping.class)
                            List<PokemonDetail> listDetails();

                            @Data.Query("insert into pokemon(name) values(:name)")
                            @Data.GeneratedKeys("id")
                            long insert(String name);

                            @Data.Query("insert into pokemon(name) values(:name)")
                            @Data.GeneratedKeys
                            Optional<Long> insertDefaultKey(String name);

                            @Data.Query("update pokemon set name = :name where id = :id")
                            void rename(long id, String name);

                            @Data.Query("delete from pokemon where id = :id")
                            int delete(long id);

                            @Data.Query("update pokemon set name = :name where id = :id")
                            boolean updateIfPresent(long id, String name);
                        }
                        """)
                .addSource("example/Contact.java", """
                        package example;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Contact {
                            Long id;
                            String name;
                            List<Phone> phones = new ArrayList<>();
                        }
                        """)
                .addSource("example/Phone.java", """
                        package example;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Phone {
                            Long id;
                            String type;
                            String phone;
                            List<Tag> tags = new ArrayList<>();
                        }
                        """)
                .addSource("example/Tag.java", """
                        package example;

                        public class Tag {
                            Long id;
                            String name;
                        }
                        """)
                .addSource("example/ContactMapping.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Mapper(target = Contact.class)
                        @Data.Map(source = "contact_key", target = "id")
                        @Data.Map(source = "contact_name", target = "name")
                        @Data.Map(source = "phone_key", target = "phones.id")
                        @Data.Map(source = "phone_number", target = "phones.phone")
                        @Data.Map(source = "tag_key", target = "phones.tags.id")
                        @Data.Map(source = "tag_name", target = "phones.tags.name")
                        @Data.Key(source = "contact_key")
                        @Data.Key(source = "phone_key", target = "phones")
                        @Data.Key(source = "tag_key", target = "phones.tags")
                        interface ContactMapping {
                        }
                        """)
                .addSource("example/ContactRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface ContactRepository extends Data.GenericRepository<Contact, Long> {

                            @Data.Query(\"\"\"
                                    SELECT c.ID    AS "id",
                                           c.NAME  AS "name",
                                           p.ID    AS "phones.id",
                                           p.TYPE  AS "phones.type",
                                           p.PHONE AS "phones.phone",
                                           t.ID    AS "phones.tags.id",
                                           t.NAME  AS "phones.tags.name"
                                    FROM CONTACT c
                                    LEFT JOIN PHONE p ON p.CONTACT_ID = c.ID
                                    LEFT JOIN TAG t   ON t.PHONE_ID = p.ID
                                    ORDER BY c.ID, p.ID, t.ID
                                    \"\"\")
                            List<Contact> findContacts();

                            @Data.Query(\"\"\"
                                    SELECT c.ID    AS contact_key,
                                           c.NAME  AS contact_name,
                                           p.ID    AS phone_key,
                                           p.PHONE AS phone_number,
                                           t.ID    AS tag_key,
                                           t.NAME  AS tag_name
                                    FROM CONTACT c
                                    LEFT JOIN PHONE p ON p.CONTACT_ID = c.ID
                                    LEFT JOIN TAG t   ON t.PHONE_ID = p.ID
                                    ORDER BY c.ID, p.ID, t.ID
                                    \"\"\")
                            @Data.ReduceWith(ContactMapping.class)
                            List<Contact> findContactsWithMapping();
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join(System.lineSeparator(), result.diagnostics()), result.success(), is(true));

        Path source = result.sourceOutput()
                .resolve("example/PokemonRepository__Jdbc.java");
        assertThat(Files.exists(source), is(true));

        String generated = Files.readString(source);
        assertThat(generated, containsString("PokemonRepository__Jdbc(JdbcOperations jdbc)"));
        assertThat(generated, containsString("this.jdbc = jdbc;"));
        assertThat(generated, containsString("private static final JdbcStatementPlan STATEMENT_FIND_BY_NAME_1"));
        assertThat(generated, containsString("JdbcStatementPlan.query("));
        assertThat(generated, containsString("JdbcStatementPlan.generatedKeys("));
        assertThat(generated, containsString("JdbcStatementPlan.update("));
        assertThat(generated, containsString("return this.jdbc.list("));
        assertThat(generated, containsString("return this.jdbc.optional("));
        assertThat(generated, containsString("return this.jdbc.generatedKey("));
        assertThat(generated, containsString(").orElseThrow"));
        assertThat(generated, containsString("this.jdbc.update("));
        assertThat(generated, containsString("return (int) this.jdbc.update("));
        assertThat(generated, containsString(") > 0L;"));
        assertThat(generated, containsString("statement.setObject(1, name);"));
        assertThat(generated, containsString("statement.setObject(1, pokemonName);"));
        assertThat(generated, containsString("statement.setObject(1, pokemonId);"));
        assertThat(generated, containsString("statement.setObject(1, query == null ? null : query.name());"));
        assertThat(generated, containsString("statement.setObject(2, query == null ? null : query.name());"));
        assertThat(generated, containsString("statement.setObject(3, query == null ? null : "
                                                     + "(query.idRange() == null ? null : query.idRange().from()));"));
        assertThat(generated, containsString("statement.setObject(6, query == null ? null : "
                                                     + "(query.idRange() == null ? null : query.idRange().to()));"));
        assertThat(generated, containsString("statement.setObject(1, bean == null ? null : bean.getType());"));
        assertThat(generated, containsString("statement.setObject(3, bean == null ? null : "
                                                     + "(bean.range() == null ? null : bean.range().from()));"));
        assertThat(generated, containsString("row.getLong(1)"));
        assertThat(generated, containsString("row.getLong(\"pokemon_id\")"));
        assertThat(generated, containsString("new PokemonSummary("));
        assertThat(generated, containsString("PokemonBean mapped = new PokemonBean();"));
        assertThat(generated, containsString("mapped.setId(row.getLong(\"id\"));"));
        assertThat(generated, containsString("mapped.setName(row.getString(\"name\"));"));
        assertThat(generated, containsString("return mapped;"));
        assertThat(generated, containsString("row.getObject(\"id\", Long.class)"));
        assertThat(generated, containsString("row.getObject(\"rank\", Integer.class)"));
        assertThat(generated, containsString("row.getObject(\"active\", Boolean.class)"));
        assertThat(generated, containsString("new Pokemon("));
        assertThat(generated, containsString("PokemonDetailMapping__JdbcMapper.INSTANCE"));

        Path mapperSource = result.sourceOutput()
                .resolve("example/PokemonDetailMapping__JdbcMapper.java");
        assertThat(Files.exists(mapperSource), is(true));

        String mapperGenerated = Files.readString(mapperSource);
        assertThat(mapperGenerated, containsString("implements JdbcRowMapper<PokemonDetail>"));
        assertThat(mapperGenerated, containsString("static final PokemonDetailMapping__JdbcMapper INSTANCE"));
        assertThat(mapperGenerated, containsString("new PokemonDetail("));
        assertThat(mapperGenerated, containsString("row.getLong(\"pokemon_id\")"));
        assertThat(mapperGenerated, containsString("row.getString(\"pokemon_name\")"));
        assertThat(mapperGenerated, containsString("new PokemonType("));
        assertThat(mapperGenerated, containsString("row.getString(\"type_name\")"));

        Path contactSource = result.sourceOutput()
                .resolve("example/ContactRepository__Jdbc.java");
        assertThat(Files.exists(contactSource), is(true));

        String contactGenerated = Files.readString(contactSource);
        assertThat(contactGenerated, containsString("return this.jdbc.listReduced("));
        assertThat(contactGenerated, containsString("new ContactRepository__JdbcFindContactsReducer()"));

        Path reducerSource = result.sourceOutput()
                .resolve("example/ContactRepository__JdbcFindContactsReducer.java");
        assertThat(Files.exists(reducerSource), is(true));

        String reducerGenerated = Files.readString(reducerSource);
        assertThat(reducerGenerated, containsString("implements JdbcRowReducer<Contact>"));
        assertThat(reducerGenerated, containsString("row.getObject(\"id\")"));
        assertThat(reducerGenerated, containsString("row.getObject(\"phones.id\")"));
        assertThat(reducerGenerated, containsString("row.getObject(\"phones.tags.id\")"));
        assertThat(reducerGenerated, containsString("phonesByKey"));
        assertThat(reducerGenerated, containsString("tagsByKey"));

        Path mappedReducerSource = result.sourceOutput()
                .resolve("example/ContactRepository__JdbcFindContactsWithMappingReducer.java");
        assertThat(Files.exists(mappedReducerSource), is(true));

        String mappedReducerGenerated = Files.readString(mappedReducerSource);
        assertThat(mappedReducerGenerated, containsString("row.getObject(\"contact_key\")"));
        assertThat(mappedReducerGenerated, containsString("row.getObject(\"phone_key\")"));
        assertThat(mappedReducerGenerated, containsString("row.getObject(\"tag_key\")"));
    }

    @Test
    void testRejectUnresolvedNamedParameter() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :missing")
                            List<Pokemon> findByName(String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("No method parameter or readable parameter property matches JDBC named "
                                          + "parameter :missing"));
    }

    @Test
    void testExplicitJdbcProviderAndPersistenceUnitSelection() throws IOException {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        @Data.PersistenceUnit("pokemon")
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon")
                            List<Pokemon> list();
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join(System.lineSeparator(), result.diagnostics()), result.success(), is(true));

        String generated = Files.readString(result.sourceOutput()
                                                    .resolve("example/PokemonRepository__Jdbc.java"));
        assertThat(generated, containsString("@Service.Named(\"pokemon\")"));
    }

    @Test
    void testGenerateNestedRepositoryImplementationAsTopLevelType() throws IOException {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/Outer.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        public final class Outer {
                            private Outer() {
                            }

                            @Data.Repository
                            @Data.Provider("jdbc")
                            public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                                @Data.Query("select id, name from pokemon")
                                List<Pokemon> list();
                            }
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join(System.lineSeparator(), result.diagnostics()), result.success(), is(true));

        Path generated = result.sourceOutput()
                .resolve("example/Outer_PokemonRepository__Jdbc.java");
        assertThat(Files.exists(generated), is(true));
        assertThat(Files.readString(generated), containsString("implements Outer.PokemonRepository"));
    }

    @Test
    void testNonJdbcProviderDoesNotGenerateJdbcImplementation() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("other")
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon")
                            List<Pokemon> list();
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join(System.lineSeparator(), result.diagnostics()), result.success(), is(true));
        assertThat(Files.exists(result.sourceOutput().resolve("example/PokemonRepository__Jdbc.java")), is(false));
    }

    @Test
    void testRejectUnusedMethodParameter() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :name")
                            List<Pokemon> findByName(String name, int limit);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC query parameter is not used by SQL markers: limit in method findByName"));
    }

    @Test
    void testRejectExplicitBindingMissingAnnotation() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where id = :id and name = :name")
                            Optional<Pokemon> find(@Data.Param("id") long pokemonId, String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC explicit parameter binding requires @Data.Param or @Data.Bind "
                                          + "on method parameter: name"));
    }

    @Test
    void testRejectDuplicateExplicitNamedParameter() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where id = :id")
                            Optional<Pokemon> find(@Data.Param("id") long pokemonId,
                                                   @Data.Param("id") long otherPokemonId);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("Multiple method parameters declare @Data.Param(\"id\")"));
    }

    @Test
    void testRejectMixedJdbcParameterMarkers() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where id = :id and name = ?")
                            Optional<Pokemon> find(long id, String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC query must not mix named and positional parameter markers"));
    }

    @Test
    void testRejectExplicitPositionalNameBinding() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where id = ?")
                            Optional<Pokemon> find(@Data.Param("id") long pokemonId);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC positional query parameter binding must use @Data.Param(index = n)"));
    }

    @Test
    void testRejectBindWithPositionalParameter() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public record SearchRequest(String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = ?")
                            List<Pokemon> find(@Data.Bind("q") SearchRequest query);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Bind can be used only with named JDBC parameters"));
    }

    @Test
    void testRejectUnresolvedBindPath() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public record SearchRequest(String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :q.missing")
                            List<Pokemon> find(@Data.Bind("q") SearchRequest query);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Bind property path \"missing\" does not match a readable property"));
    }

    @Test
    void testRejectUnusedBindPrefix() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public record SearchRequest(String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :name")
                            List<Pokemon> find(@Data.Bind("q") SearchRequest query,
                                               @Data.Param("name") String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Bind(\"q\") is not used by SQL markers"));
    }

    @Test
    void testRejectParamBindNamespaceConflict() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public record SearchRequest(String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :q.name")
                            List<Pokemon> find(@Data.Bind("q") SearchRequest query,
                                               @Data.Param("q.name") String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC explicit parameter binding namespace conflict"));
    }

    @Test
    void testRejectAmbiguousBindProperty() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/SearchRequest.java", """
                        package example;

                        public class SearchRequest {
                            public String name() {
                                return null;
                            }

                            public String getName() {
                                return null;
                            }
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon where name = :q.name")
                            List<Pokemon> find(@Data.Bind("q") SearchRequest query);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Bind property path \"name\" is ambiguous"));
    }

    @Test
    void testRejectInvalidMappingTarget() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Map(value = "pokemon_id", target = "missing")
                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id as pokemon_id, name from pokemon")
                            Optional<Pokemon> find();
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("mapping target does not match example.Pokemon: missing"));
    }

    @Test
    void testRejectUnknownMapperSourceLabel() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Map(value = "pokemon_key", target = "id")
                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id, name from pokemon")
                            Optional<Pokemon> find();
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC mapper source label \"pokemon_key\" is not produced by SQL query"));
    }

    @Test
    void testRejectDuplicateMapTargets() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Map(value = "pokemon_id", target = "id")
                        @Data.Map(value = "pokemon_key", target = "id")
                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import java.util.Optional;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id as pokemon_id, name from pokemon")
                            Optional<Pokemon> find();
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Map declares multiple mappings for target: id"));
    }

    @Test
    void testRejectDuplicateReducerKeys() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Contact.java", """
                        package example;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Contact {
                            Long id;
                            List<Phone> phones = new ArrayList<>();
                        }
                        """)
                .addSource("example/Phone.java", """
                        package example;

                        public class Phone {
                            Long id;
                        }
                        """)
                .addSource("example/ContactRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface ContactRepository extends Data.GenericRepository<Contact, Long> {

                            @Data.Query(\"\"\"
                                    SELECT c.ID AS "id",
                                           p.ID AS "phones.id"
                                    FROM CONTACT c
                                    LEFT JOIN PHONE p ON p.CONTACT_ID = c.ID
                                    \"\"\")
                            @Data.Key(source = "id")
                            @Data.Key(source = "contact_id")
                            List<Contact> findContacts();
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.Key declares multiple reducer keys for target"));
    }

    @Test
    void testRejectUnknownReducerSourceLabel() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Contact.java", """
                        package example;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Contact {
                            Long id;
                            List<Phone> phones = new ArrayList<>();
                        }
                        """)
                .addSource("example/Phone.java", """
                        package example;

                        public class Phone {
                            Long id;
                        }
                        """)
                .addSource("example/ContactMapping.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Mapper(target = Contact.class)
                        @Data.Map(source = "missing_contact_key", target = "id")
                        @Data.Map(source = "phone_key", target = "phones.id")
                        @Data.Key(source = "contact_key")
                        @Data.Key(source = "phone_key", target = "phones")
                        interface ContactMapping {
                        }
                        """)
                .addSource("example/ContactRepository.java", """
                        package example;

                        import java.util.List;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface ContactRepository extends Data.GenericRepository<Contact, Long> {

                            @Data.Query(\"\"\"
                                    SELECT c.ID AS contact_key,
                                           p.ID AS phone_key
                                    FROM CONTACT c
                                    LEFT JOIN PHONE p ON p.CONTACT_ID = c.ID
                                    \"\"\")
                            @Data.ReduceWith(ContactMapping.class)
                            List<Contact> findContacts();
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("JDBC reducer source label \"missing_contact_key\" is not produced by SQL query"));
    }

    @Test
    void testRejectGeneratedKeysOnQuery() {
        TestCompiler.Result result = repositoryCompiler()
                .addSource("example/Pokemon.java", """
                        package example;

                        public record Pokemon(long id, String name) {
                        }
                        """)
                .addSource("example/PokemonRepository.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Repository
                        public interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {

                            @Data.Query("select id from pokemon where name = :name")
                            @Data.GeneratedKeys("id")
                            long findId(String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(result.success(), is(false));
        assertThat(String.join(System.lineSeparator(), result.diagnostics()),
                   containsString("@Data.GeneratedKeys can be used only with data-changing JDBC repository methods"));
    }

    private static Path moduleClasses(String module) {
        return rootDirectory()
                .resolve(module)
                .resolve("target/classes");
    }

    private static TestCompiler.Builder repositoryCompiler() {
        return TestCompiler.builder()
                .addProcessor(new AptProcessor())
                .currentRelease()
                .addOption("-Xlint:none")
                .addClasspathEntries(List.of(moduleClasses("common/common"),
                                             moduleClasses("common/types"),
                                             moduleClasses("data/data"),
                                             moduleClasses("data/jdbc/jdbc"),
                                             moduleClasses("service/registry")))
                .printDiagnostics(false);
    }

    private static Path rootDirectory() {
        Path dir = Path.of("")
                .toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("parent")) && Files.isDirectory(dir.resolve("data"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate Helidon repository root");
    }
}
