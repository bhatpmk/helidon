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
import io.helidon.data.codegen.DataGeneratorProvider;
import io.helidon.data.codegen.common.RepositoryCodegenProvider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMethodGeneratorTest {

    @Test
    void generatesDirectPublicClientCalls() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("PokemonRepository.java", """
                        package example;

                        import java.util.List;
                        import java.util.Optional;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcExecutionOptions;
                        import io.helidon.transaction.Tx;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository {
                            @Data.Query("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            List<Pokemon> find(JdbcExecutionOptions options, long minimum);

                            @Data.Query("select NAME from POKEMON where ID = :id")
                            Optional<String> name(long id);

                            @Data.Query("select count(*) from POKEMON")
                            long count();

                            @Data.Update("update POKEMON set NAME = :name where ID = :id")
                            @Tx.Required
                            long rename(String name, long id);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            long insert(String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys
                            long insertDefault(String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            GeneratedPokemon insertRecord(String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            @Data.BeanMapper(GeneratedBean.class)
                            GeneratedBean insertBean(String name);

                            @Data.Query("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Data.RowMapper(PokemonMapper.class)
                            Pokemon mapped(long id);
                        }

                        record Pokemon(long id, String name) {
                        }

                        record GeneratedPokemon(long id) {
                        }

                        class GeneratedBean {
                            private Long id;
                            public GeneratedBean() { }
                            public void setId(Long id) { this.id = id; }
                        }

                        final class PokemonMapper implements JdbcClient.RowMapper<Pokemon> {
                            public PokemonMapper() { }
                            @Override
                            public Pokemon map(JdbcClient.Row row) {
                                return new Pokemon(row.required("id", Long.class), row.get("name", String.class));
                            }
                        }
                        """)
                .build()
                .compile();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        Path generated = result.sourceOutput().resolve("example/PokemonRepository__Jdbc.java");
        assertTrue(Files.exists(generated));
        String source = Files.readString(generated);
        assertTrue(source.contains("jdbcClient.create(SQL_FIND)"), source);
        assertTrue(source.contains("@Service.Named(\"@default\") @Data.ProviderType(\"jdbc\") JdbcClient jdbcClient"),
                   source);
        assertTrue(source.contains(".options(options).bind(1, minimum).map(MAPPER_FIND).list()"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).optional()"), source);
        assertTrue(source.contains("jdbcClient.create(SQL_COUNT).map(long.class).one()"), source);
        assertTrue(source.contains(".bind(1, name, JDBCType.VARCHAR).bind(2, id).execute()"), source);
        assertTrue(source.contains(".bind(1, name, JDBCType.VARCHAR)"
                                           + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one()"), source);
        assertTrue(source.contains(".generatedKeys(row -> row.required(1, Long.class)).one()"), source);
        assertTrue(source.contains("MAPPER_MAPPED = new PokemonMapper()"), source);
        assertTrue(source.contains(".map(MAPPER_MAPPED).one()"), source);
        assertTrue(source.contains(".generatedKeys(MAPPER_INSERT_RECORD, \"ID\").one()"), source);
        assertTrue(source.contains(".generatedKeys(MAPPER_INSERT_BEAN, \"ID\").one()"), source);
        assertTrue(source.contains("@Tx.Required"), source);
    }

    @Test
    void rejectsUnmatchedAndPositionalDeclarativeParameters() {
        TestCompiler.Result result = compiler()
                .printDiagnostics(false)
                .addSource("InvalidRepository.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface InvalidRepository {
                            @Data.Query("select NAME from POKEMON where ID = ?")
                            String find(long id);
                        }
                        """)
                .build()
                .compile();

        assertTrue(!result.success());
        assertTrue(result.diagnostics().stream()
                           .anyMatch(message -> message.contains("named ':name' markers only")),
                   () -> String.join("\n", result.diagnostics()));
    }

    @Test
    void requiresExplicitJdbcProviderSelection() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("DefaultRepository.java", """
                        package example;

                        import io.helidon.data.Data;

                        @Data.Repository
                        interface DefaultRepository {
                            @Data.Query("select NAME from CONTACT")
                            String find();
                        }
                        """)
                .build()
                .compile();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        assertTrue(!Files.exists(result.sourceOutput().resolve("example/DefaultRepository__Jdbc.java")));
    }

    @Test
    void generatesAllFlatTerminalsRepeatedBindsAndOptionalUnitFallback() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("TraversalRepository.java", """
                        package example;

                        import java.sql.JDBCType;
                        import java.util.List;
                        import java.util.Optional;
                        import java.util.function.Consumer;
                        import java.util.function.Predicate;

                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        @Data.PersistenceUnit(value = "contacts", required = false)
                        interface TraversalRepository {
                            @Data.Query("select NAME from CONTACT where ID = :id")
                            String one(long id);

                            @Data.Query("select NAME from CONTACT where ID = :id")
                            Optional<String> optional(long id);

                            @Data.Query("select NAME from CONTACT where ID = :id or PARENT_ID = :id and TYPE = :type")
                            List<String> list(long id, @Data.JdbcType(JDBCType.CHAR) String type);

                            @Data.Query("select NAME from CONTACT")
                            void withRows(Consumer<Iterable<String>> action);

                            @Data.Query("select NAME from CONTACT")
                            void forEach(Consumer<String> action);

                            @Data.Query("select NAME from CONTACT")
                            boolean forEachWhile(Predicate<String> action);

                            @Data.Update("delete from CONTACT where ID = :id")
                            void delete(long id);
                        }
                        """)
                .build()
                .compile();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        String source = Files.readString(result.sourceOutput().resolve("example/TraversalRepository__Jdbc.java"));
        assertTrue(source.contains("Optional<JdbcClient> namedJdbcClient"), source);
        assertTrue(source.contains("@Service.Named(\"contacts\") @Data.ProviderType(\"jdbc\")"), source);
        assertTrue(source.contains("Supplier<JdbcClient> jdbcClient"), source);
        assertTrue(source.contains("@Service.Named(\"@default\") @Data.ProviderType(\"jdbc\")"), source);
        assertTrue(source.contains(".map(String.class).one()"), source);
        assertTrue(source.contains(".map(String.class).optional()"), source);
        assertTrue(source.contains(".bind(1, id).bind(2, id).bind(3, type, JDBCType.CHAR)"
                                           + ".map(String.class).list()"), source);
        assertTrue(source.contains(".map(String.class).withRows(action)"), source);
        assertTrue(source.contains(".map(String.class).forEach(action)"), source);
        assertTrue(source.contains(".map(String.class).forEachWhile(action)"), source);
        assertTrue(source.contains(".bind(1, id).execute();"), source);
    }

    @Test
    void rejectsAmbiguousStatementsUnsupportedCollectionsAndAbstractMappers() {
        assertCompilationFailure("MixedRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MixedRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.Update("delete from CONTACT")
                            String invalid();
                        }
                        """,
                                 "cannot combine @Data.Query and @Data.Update");
        assertCompilationFailure("SetRepository.java", """
                        package example;
                        import java.util.Set;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface SetRepository {
                            @Data.Query("select NAME from CONTACT")
                            Set<String> invalid();
                        }
                        """,
                                 "Unsupported JDBC repository return type");
        assertCompilationFailure("MapperRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MapperRepository {
                            @Data.Query("select NAME as name from CONTACT")
                            @Data.RowMapper(AbstractMapper.class)
                            String invalid();
                        }
                        abstract class AbstractMapper implements JdbcClient.RowMapper<String> {
                            public AbstractMapper() { }
                        }
                        """,
                                 "Mapper must be a concrete class");
        assertCompilationFailure("BlankKeyRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface BlankKeyRepository {
                            @Data.Update("insert into CONTACT(NAME) values (:name)")
                            @Data.GeneratedKeys(" ")
                            long invalid(String name);
                        }
                        """,
                                 "column names must not be blank");
    }

    @Test
    void generatesIdentityDefinedJoinReducer() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("ContactRepository.java", """
                        package example;

                        import java.util.List;
                        import io.helidon.data.Data;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface ContactRepository {
                            @Data.Query(\"""
                                    select c.ID as "id", c.NAME as "name",
                                           p.ID as "phones.id", p.NUMBER as "phones.number",
                                           t.ID as "phones.tags.id", t.NAME as "phones.tags.name"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    left join TAG t on t.PHONE_ID = p.ID
                                    order by c.ID, p.ID, t.ID
                                    \""")
                            @Data.BeanMapper(Contact.class)
                            @Data.BeanMapper(value = Phone.class, prefix = "phones")
                            @Data.BeanMapper(value = Tag.class, prefix = "phones.tags")
                            List<Contact> findContacts();

                            @Data.Query(\"""
                                    select c.ID as "id", c.NAME as "name",
                                           p.ID as "phones.id", p.NUMBER as "phones.number",
                                           t.ID as "phones.tags.id", t.NAME as "phones.tags.name"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    left join TAG t on t.PHONE_ID = p.ID
                                    order by c.ID, p.ID, t.ID
                                    \""")
                            List<Contact> findContactsInferred();

                            @Data.Query("select ID as id, NAME as name from CONTACT order by ID")
                            @Data.BeanMappers(@Data.BeanMapper(Contact.class))
                            List<Contact> listContacts();
                        }

                        class Contact {
                            private Long id;
                            private String name;
                            private List<Phone> phones;
                            public Contact() { }
                            public void setId(Long id) { this.id = id; }
                            public void setName(String name) { this.name = name; }
                            public List<Phone> getPhones() { return phones; }
                            public void setPhones(List<Phone> phones) { this.phones = phones; }
                        }

                        class Phone {
                            private Long id;
                            private String number;
                            private List<Tag> tags;
                            public Phone() { }
                            public void setId(Long id) { this.id = id; }
                            public void setNumber(String number) { this.number = number; }
                            public List<Tag> getTags() { return tags; }
                            public void setTags(List<Tag> tags) { this.tags = tags; }
                        }

                        class Tag {
                            private Long id;
                            private String name;
                            public Tag() { }
                            public void setId(Long id) { this.id = id; }
                            public void setName(String name) { this.name = name; }
                        }
                        """)
                .build()
                .compile();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        String source = Files.readString(result.sourceOutput().resolve("example/ContactRepository__Jdbc.java"));
        assertTrue(source.contains(".reduce(new Reducer_FindContacts())"), source);
        assertTrue(source.contains("LinkedHashMap<Long, Contact> roots"), source);
        assertTrue(source.contains("IdentityHashMap<Contact, LinkedHashMap<Long, Phone>> phonesByParent"), source);
        assertTrue(source.contains("if (phonesTagsId != null)"), source);
        assertTrue(source.contains(".reduce(new Reducer_FindContactsInferred())"), source);
        assertTrue(source.contains("MAPPER_LIST_CONTACTS = row ->"), source);
        assertTrue(source.contains(".map(MAPPER_LIST_CONTACTS).list()"), source);
    }

    private static TestCompiler.Builder compiler() {
        return TestCompiler.builder()
                .currentRelease()
                .printDiagnostics(false)
                .addProcessor(AptProcessor::new)
                .addClasspath(List.of(load("io.helidon.data.Data"),
                                      load("io.helidon.data.jdbc.JdbcClient"),
                                      load("io.helidon.service.registry.Service"),
                                      load("io.helidon.transaction.Tx"),
                                      load("io.helidon.common.Generated"),
                                      load("io.helidon.common.types.TypeName"),
                                      DataGeneratorProvider.class,
                                      RepositoryCodegenProvider.class,
                                      JdbcPersistenceGeneratorProvider.class));
    }

    private static void assertCompilationFailure(String fileName, String source, String expectedDiagnostic) {
        TestCompiler.Result result = compiler()
                .printDiagnostics(false)
                .addSource(fileName, source)
                .build()
                .compile();
        assertTrue(!result.success());
        assertTrue(result.diagnostics().stream().anyMatch(message -> message.contains(expectedDiagnostic)),
                   () -> String.join("\n", result.diagnostics()));
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing test classpath entry " + className, e);
        }
    }
}
