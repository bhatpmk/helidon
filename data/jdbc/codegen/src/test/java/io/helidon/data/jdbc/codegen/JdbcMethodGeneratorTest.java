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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.TestCompiler;
import io.helidon.data.codegen.DataGeneratorProvider;
import io.helidon.data.codegen.common.RepositoryCodegenProvider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        import io.helidon.transaction.Tx;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository {
                            @Data.Query("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            List<Pokemon> find(long minimum);

                            @Data.Query("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            List<Pokemon> findRequested(JdbcQueryRequest request, long minimum);

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
                            @Data.GeneratedKeys("ID")
                            long insertRequested(JdbcQueryRequest request, String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys
                            long insertDefault(String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            GeneratedPokemon insertRecord(String name);

                            @Data.Update("insert into POKEMON(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            @Data.BeanMapping(GeneratedBean.class)
                            GeneratedBean insertBean(String name);

                            @Data.Query("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Data.RowMapper(PokemonMapper.class)
                            Pokemon mapped(long id);

                            @Data.Query("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Data.RowMapper(PokemonMapper.class)
                            Pokemon mappedRequested(JdbcQueryRequest request, long id);

                            @Data.Query("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            void visitRecords(JdbcQueryRequest.ForEach<Pokemon> request, long minimum);

                            @Data.Query("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Data.RowMapper(PokemonMapper.class)
                            void visitMapped(JdbcQueryRequest.ForEach<Pokemon> request, long id);

                            @Data.Query("select ID as id from POKEMON")
                            @Data.BeanMapping(GeneratedBean.class)
                            void visitBeans(JdbcQueryRequest.ForEach<GeneratedBean> request);
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
        assertTrue(source.contains(".bind(1, minimum).map(MAPPER_FIND).list()"), source);
        assertTrue(source.contains(".bind(1, minimum).map(MAPPER_FIND_REQUESTED).list(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).optional()"), source);
        assertTrue(source.contains("jdbcClient.create(SQL_COUNT).map(long.class).one()"), source);
        assertTrue(source.contains(".bind(1, name, JDBCType.VARCHAR).bind(2, id).execute()"), source);
        assertTrue(source.contains(".bind(1, name, JDBCType.VARCHAR)"
                                           + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one()"), source);
        assertTrue(source.contains(".bind(1, name, JDBCType.VARCHAR)"
                                           + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one(request)"),
                   source);
        assertTrue(source.contains(".generatedKeys(row -> row.required(1, Long.class)).one()"), source);
        assertTrue(source.contains("MAPPER_MAPPED = new PokemonMapper()"), source);
        assertTrue(source.contains(".map(MAPPER_MAPPED).one()"), source);
        assertTrue(source.contains(".map(MAPPER_MAPPED_REQUESTED).one(request)"), source);
        assertTrue(source.contains(".generatedKeys(MAPPER_INSERT_RECORD, \"ID\").one()"), source);
        assertTrue(source.contains(".generatedKeys(MAPPER_INSERT_BEAN, \"ID\").one()"), source);
        assertTrue(source.contains(".bind(1, minimum).map(MAPPER_VISIT_RECORDS).forEach(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(MAPPER_VISIT_MAPPED).forEach(request)"), source);
        assertTrue(source.contains(".map(MAPPER_VISIT_BEANS).forEach(request)"), source);
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
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        @Data.PersistenceUnit(value = "contacts", required = false)
                        interface TraversalRepository {
                            @Data.Query("select NAME from CONTACT where ID = :id")
                            String one(long id);

                            @Data.Query("select NAME from CONTACT where ID = :id")
                            String requestedOne(JdbcQueryRequest request, long id);

                            @Data.Query("select NAME from CONTACT where ID = :id")
                            Optional<String> optional(long id);

                            @Data.Query("select NAME from CONTACT where ID = :id")
                            Optional<String> requestedOptional(JdbcQueryRequest request, long id);

                            @Data.Query("select NAME from CONTACT where ID = :id or PARENT_ID = :id and TYPE = :type")
                            List<String> list(long id, @Data.JdbcType(JDBCType.CHAR) String type);

                            @Data.Query("select NAME from CONTACT where ID = :id or PARENT_ID = :id")
                            List<String> requestedList(JdbcQueryRequest request, long id);

                            @Data.Query("select NAME from CONTACT where ID >= :id")
                            void visit(JdbcQueryRequest.ForEach<String> request, long id);

                            @Data.Query("select NAME from CONTACT where ID >= :id")
                            boolean visitUntil(JdbcQueryRequest.ForEachWhile<String> request, long id);

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
        assertTrue(source.contains(".bind(1, id).map(String.class).one(request)"), source);
        assertTrue(source.contains(".map(String.class).optional()"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).optional(request)"), source);
        assertTrue(source.contains(".bind(1, id).bind(2, id).bind(3, type, JDBCType.CHAR)"
                                           + ".map(String.class).list()"), source);
        assertTrue(source.contains(".bind(1, id).bind(2, id).map(String.class).list(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).forEach(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).forEachWhile(request)"), source);
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
    void rejectsInvalidTraversalContracts() {
        assertCompilationFailure("TrailingCallbackRepository.java", """
                        package example;
                        import java.util.function.Consumer;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface TrailingCallbackRepository {
                            @Data.Query("select NAME from CONTACT where ID = :id")
                            void invalid(long id, Consumer<String> action);
                        }
                        """,
                                 "Traversal callbacks must be supplied through a leading JdbcQueryRequest");
        assertCompilationFailure("NonLeadingRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NonLeadingRequestRepository {
                            @Data.Query("select NAME from CONTACT where ID = :id")
                            void invalid(long id, JdbcQueryRequest.ForEach<String> request);
                        }
                        """,
                                 "only as the leading parameter");
        assertCompilationFailure("DuplicateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DuplicateRequestRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(JdbcQueryRequest.ForEach<String> first,
                                         JdbcQueryRequest.ForEach<String> second);
                        }
                        """,
                                 "permitted once and only as the leading parameter");
        assertCompilationFailure("ForEachReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ForEachReturnRepository {
                            @Data.Query("select NAME from CONTACT")
                            String invalid(JdbcQueryRequest.ForEach<String> request);
                        }
                        """,
                                 "JdbcQueryRequest.ForEach methods must return void");
        assertCompilationFailure("ForEachWhileReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ForEachWhileReturnRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(JdbcQueryRequest.ForEachWhile<String> request);
                        }
                        """,
                                 "JdbcQueryRequest.ForEachWhile methods must return primitive boolean");
        assertCompilationFailure("RawRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface RawRequestRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(JdbcQueryRequest.ForEach request);
                        }
                        """,
                                 "requires one concrete mapped row type");
        assertCompilationFailure("WildcardRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface WildcardRequestRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(JdbcQueryRequest.ForEach<?> request);
                        }
                        """,
                                 "wildcard row types are not supported");
        assertCompilationFailure("TypedRequestRepository.java", """
                        package example;
                        import java.sql.JDBCType;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface TypedRequestRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(@Data.JdbcType(JDBCType.VARCHAR)
                                         JdbcQueryRequest.ForEach<String> request);
                        }
                        """,
                                 "must not carry @Data.JdbcType");
        assertCompilationFailure("UpdateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface UpdateRequestRepository {
                            @Data.Update("delete from CONTACT")
                            void invalid(JdbcQueryRequest.ForEach<String> request);
                        }
                        """,
                                 "@Data.Update methods do not support JdbcQueryRequest");
        assertCompilationFailure("RegularUpdateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface RegularUpdateRequestRepository {
                            @Data.Update("delete from CONTACT")
                            long invalid(JdbcQueryRequest request);
                        }
                        """,
                                 "@Data.Update methods do not support JdbcQueryRequest");
        assertCompilationFailure("GeneratedKeyRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GeneratedKeyRequestRepository {
                            @Data.Update("insert into CONTACT(NAME) values ('name')")
                            @Data.GeneratedKeys("ID")
                            void invalid(JdbcQueryRequest.ForEach<Long> request);
                        }
                        """,
                                 "JDBC traversal requests are supported only on @Data.Query methods");
        assertCompilationFailure("MapperRequestMismatchRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MapperRequestMismatchRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.RowMapper(LongMapper.class)
                            void invalid(JdbcQueryRequest.ForEach<String> request);
                        }
                        final class LongMapper implements JdbcClient.RowMapper<Long> {
                            public LongMapper() { }
                            public Long map(JdbcClient.Row row) { return row.get(1, Long.class); }
                        }
                        """,
                                 "Mapper must implement JdbcClient.RowMapper<java.lang.String>");
        assertCompilationFailure("GraphRequestRepository.java", """
                        package example;
                        import java.util.ArrayList;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GraphRequestRepository {
                            @Data.Query("select ID as contactId, CHILD_ID as children.childId from CONTACT")
                            @Data.BeanMapping(value = Contact.class, identityProperty = "contactId")
                            @Data.BeanMapping(value = Child.class,
                                              propertyPath = "children",
                                              identityProperty = "childId")
                            void invalid(JdbcQueryRequest.ForEach<Contact> request);
                        }
                        class Contact {
                            private Long contactId;
                            private List<Child> children = new ArrayList<>();
                            public Contact() { }
                            public void setContactId(Long value) { contactId = value; }
                            public List<Child> getChildren() { return children; }
                            public void setChildren(List<Child> value) { children = value; }
                        }
                        class Child {
                            private Long childId;
                            public Child() { }
                            public void setChildId(Long value) { childId = value; }
                        }
                        """,
                                 "Identity-defined graph reduction cannot use a JDBC query traversal request");
        assertCompilationFailure("VoidRegularRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface VoidRegularRequestRepository {
                            @Data.Query("select NAME from CONTACT")
                            void invalid(JdbcQueryRequest request);
                        }
                        """,
                                 "regular JdbcQueryRequest method requires a non-void mapped result");
    }

    @Test
    void generatesIdentityDefinedJoinReducer() throws Exception {
        TestCompiler.Result result = compiler()
                .addSource("ContactRepository.java", """
                        package example;

                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.Optional;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcQueryRequest;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface ContactRepository {
                            @Data.Query(\"""
                                    select c.ID as "contactId", c.NAME as "name",
                                           p.ID as "phones.phoneId", p.NUMBER as "phones.number",
                                           t.ID as "phones.tags.tagId", t.NAME as "phones.tags.name"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    left join TAG t on t.PHONE_ID = p.ID
                                    order by c.ID, p.ID, t.ID
                                    \""")
                            @Data.BeanMapping(value = Contact.class, identityProperty = "contactId")
                            @Data.BeanMapping(value = Phone.class,
                                              propertyPath = "phones",
                                              identityProperty = "phoneId")
                            @Data.BeanMapping(value = Tag.class,
                                              propertyPath = "phones.tags",
                                              identityProperty = "tagId")
                            List<Contact> findContacts();

                            @Data.Query("select ID as contactId from CONTACT order by ID")
                            @Data.RowReducer(ContactIdsReducer.class)
                            List<Long> reduceContactIds();

                            @Data.Query("select ID as contactId from CONTACT order by ID")
                            @Data.RowReducer(ContactIdsReducer.class)
                            List<Long> reduceContactIdsRequested(JdbcQueryRequest request);

                            @Data.Query("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Data.BeanMapping(value = Contact.class, identityProperty = "contactId")
                            Contact findOne();

                            @Data.Query("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Data.BeanMapping(value = Contact.class, identityProperty = "contactId")
                            Contact findOneRequested(JdbcQueryRequest request);

                            @Data.Query("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Data.BeanMapping(value = Contact.class, identityProperty = "contactId")
                            Optional<Contact> findOptional();

                            @Data.Query("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Data.BeanMappings(@Data.BeanMapping(Contact.class))
                            List<Contact> listContacts();
                        }

                        class Contact {
                            private Long contactId;
                            private String name;
                            private List<Phone> phones;
                            public Contact() { }
                            public Long getContactId() { return contactId; }
                            public void setContactId(Long contactId) { this.contactId = contactId; }
                            public String getName() { return name; }
                            public void setName(String name) { this.name = name; }
                            public List<Phone> getPhones() { return phones; }
                            public void setPhones(List<Phone> phones) { this.phones = phones; }
                        }

                        class Phone {
                            private Long phoneId;
                            private String number;
                            private List<Tag> tags;
                            public Phone() { }
                            public Long getPhoneId() { return phoneId; }
                            public void setPhoneId(Long phoneId) { this.phoneId = phoneId; }
                            public String getNumber() { return number; }
                            public void setNumber(String number) { this.number = number; }
                            public List<Tag> getTags() { return tags; }
                            public void setTags(List<Tag> tags) { this.tags = tags; }
                        }

                        class Tag {
                            private Long tagId;
                            private String name;
                            public Tag() { }
                            public Long getTagId() { return tagId; }
                            public void setTagId(Long tagId) { this.tagId = tagId; }
                            public String getName() { return name; }
                            public void setName(String name) { this.name = name; }
                        }

                        final class ContactIdsReducer implements JdbcClient.RowReducer<List<Long>> {
                            private final List<Long> ids = new ArrayList<>();
                            @Override
                            public void accept(JdbcClient.Row row) {
                                ids.add(row.required("contactId", Long.class));
                            }
                            @Override
                            public List<Long> finish() {
                                return List.copyOf(ids);
                            }
                        }
                        """)
                .build()
                .compile();

        assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
        String source = Files.readString(result.sourceOutput().resolve("example/ContactRepository__Jdbc.java"));
        assertTrue(source.contains(".reduce(new Reducer_FindContacts())"), source);
        assertTrue(source.contains("LinkedHashMap<Long, Contact> roots"), source);
        assertTrue(source.contains("IdentityHashMap<Contact, LinkedHashMap<Long, Phone>> phonesByParent"), source);
        assertTrue(source.contains("row.required(\"contactId\", Long.class)"), source);
        assertTrue(source.contains("row.get(\"phones.phoneId\", Long.class)"), source);
        assertTrue(source.contains("Conflicting projected value for graph scope 'phones' property 'number'"), source);
        assertTrue(source.contains("Graph scope 'phones.tags' has an identity while ancestor scope 'phones' is absent"),
                   source);
        assertTrue(source.contains(".reduce(new ContactIdsReducer())"), source);
        assertTrue(source.contains(".reduce(new ContactIdsReducer(), request)"), source);
        assertTrue(source.contains(".reduce(new Reducer_FindOne())"), source);
        assertTrue(source.contains(".reduce(new Reducer_FindOneRequested(), request)"), source);
        assertTrue(source.contains(".reduce(new Reducer_FindOptional())"), source);
        assertTrue(source.contains("MAPPER_LIST_CONTACTS = row ->"), source);
        assertTrue(source.contains(".map(MAPPER_LIST_CONTACTS).list()"), source);
        assertGeneratedGraphBehavior(result);
    }

    @Test
    void rejectsImplicitGraphsAndInvalidReducerDeclarations() {
        assertCompilationFailure("ImplicitGraphRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ImplicitGraphRepository {
                            @Data.Query("select c.ID as contactId, p.ID as \\\"phones.phoneId\\\" from CONTACT c "
                                    + "left join PHONE p on p.CONTACT_ID = c.ID")
                            List<Contact> invalid();
                        }
                        class Contact { }
                        """,
                                 "Dotted SQL projection aliases require a complete identity-bearing");
        assertCompilationFailure("MissingIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingIdentityRepository {
                            @Data.Query("select c.ID as contactId, p.ID as \\\"children.childId\\\" from CONTACT c "
                                    + "left join CHILD p on p.CONTACT_ID = c.ID")
                            @Data.BeanMapping(value = Parent.class, identityProperty = "contactId")
                            @Data.BeanMapping(value = Child.class, propertyPath = "children")
                            List<Parent> invalid();
                        }
                        class Parent {
                            private Long contactId;
                            private List<Child> children;
                            public Parent() { }
                            public Long getContactId() { return contactId; }
                            public void setContactId(Long contactId) { this.contactId = contactId; }
                            public List<Child> getChildren() { return children; }
                            public void setChildren(List<Child> children) { this.children = children; }
                        }
                        class Child {
                            private Long childId;
                            public Child() { }
                            public Long getChildId() { return childId; }
                            public void setChildId(Long childId) { this.childId = childId; }
                        }
                        """,
                                 "requires a nonblank identityProperty");
        assertCompilationFailure("ReducerResultRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ReducerResultRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.RowReducer(WrongReducer.class)
                            List<String> invalid();
                        }
                        final class WrongReducer implements JdbcClient.RowReducer<String> {
                            public WrongReducer() { }
                            public void accept(JdbcClient.Row row) { }
                            public String finish() { return ""; }
                        }
                        """,
                                 "Reducer must implement JdbcClient.RowReducer<java.util.List<java.lang.String>>");
        assertCompilationFailure("ReducerConflictRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ReducerConflictRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.RowMapper(NameMapper.class)
                            @Data.RowReducer(NameReducer.class)
                            String invalid();
                        }
                        final class NameMapper implements JdbcClient.RowMapper<String> {
                            public NameMapper() { }
                            public String map(JdbcClient.Row row) { return row.get(1, String.class); }
                        }
                        final class NameReducer implements JdbcClient.RowReducer<String> {
                            public NameReducer() { }
                            public void accept(JdbcClient.Row row) { }
                            public String finish() { return ""; }
                        }
                        """,
                                 "@Data.RowReducer cannot be combined");
        assertCompilationFailure("GeneratedKeyReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GeneratedKeyReducerRepository {
                            @Data.Update("insert into CONTACT(NAME) values (:name)")
                            @Data.GeneratedKeys("ID")
                            @Data.RowReducer(KeyReducer.class)
                            Long invalid(String name);
                        }
                        final class KeyReducer implements JdbcClient.RowReducer<Long> {
                            public void accept(JdbcClient.Row row) { }
                            public Long finish() { return 1L; }
                        }
                        """,
                                 "@Data.RowReducer is legal only on @Data.Query methods");
        assertCompilationFailure("TraversalReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcQueryRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface TraversalReducerRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.RowReducer(NameReducer.class)
                            void invalid(JdbcQueryRequest.ForEach<String> request);
                        }
                        final class NameReducer implements JdbcClient.RowReducer<String> {
                            public void accept(JdbcClient.Row row) { }
                            public String finish() { return ""; }
                        }
                        """,
                                 "@Data.RowReducer cannot use a JDBC query traversal request");
        assertCompilationFailure("AbstractReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface AbstractReducerRepository {
                            @Data.Query("select NAME from CONTACT")
                            @Data.RowReducer(AbstractReducer.class)
                            String invalid();
                        }
                        abstract class AbstractReducer implements JdbcClient.RowReducer<String> {
                            public void accept(JdbcClient.Row row) { }
                        }
                        """,
                                 "Reducer must be a concrete class");
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

    private static void assertGeneratedGraphBehavior(TestCompiler.Result result) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {result.classOutput().toUri().toURL()},
                                                        JdbcMethodGeneratorTest.class.getClassLoader())) {
            Class<?> repositoryType = Class.forName("example.ContactRepository__Jdbc", true, loader);
            Class<?> jdbcClientType = Class.forName("io.helidon.data.jdbc.JdbcClient");
            Class<?> rowType = Class.forName("io.helidon.data.jdbc.JdbcClient$Row");
            var constructor = repositoryType.getDeclaredConstructor(jdbcClientType);
            constructor.setAccessible(true);
            var findContacts = repositoryType.getMethod("findContacts");
            findContacts.setAccessible(true);
            var reduceContactIds = repositoryType.getMethod("reduceContactIds");
            reduceContactIds.setAccessible(true);
            Object repository = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada", "phones.phoneId", 10L, "phones.number", "111",
                        "phones.tags.tagId", 100L, "phones.tags.name", "family"),
                    row("contactId", 1L, "name", "Ada", "phones.phoneId", 10L, "phones.number", "111",
                        "phones.tags.tagId", 101L, "phones.tags.name", "mobile"),
                    row("contactId", 1L, "name", "Ada", "phones.phoneId", 10L, "phones.number", "111",
                        "phones.tags.tagId", 101L, "phones.tags.name", "mobile"),
                    row("contactId", 1L, "name", "Ada", "phones.phoneId", 11L, "phones.number", "222",
                        "phones.tags.tagId", null, "phones.tags.name", null),
                    row("contactId", 2L, "name", "Grace", "phones.phoneId", null, "phones.number", null,
                        "phones.tags.tagId", null, "phones.tags.name", null))));

            List<?> contacts = (List<?>) findContacts.invoke(repository);
            assertEquals(2, contacts.size());
            Object first = contacts.getFirst();
            assertEquals(1L, property(first, "getContactId"));
            List<?> phones = (List<?>) property(first, "getPhones");
            assertEquals(2, phones.size());
            assertEquals(10L, property(phones.getFirst(), "getPhoneId"));
            List<?> tags = (List<?>) property(phones.getFirst(), "getTags");
            assertEquals(2, tags.size(), "A duplicate physical row must not duplicate a nested child");
            assertEquals(100L, property(tags.getFirst(), "getTagId"));
            assertTrue(((List<?>) property(contacts.get(1), "getPhones")).isEmpty(),
                       "A null outer-join child identity must not create a child");
            assertEquals(List.of(1L, 1L, 1L, 1L, 2L), reduceContactIds.invoke(repository));
            assertEquals(List.of(1L, 1L, 1L, 1L, 2L), reduceContactIds.invoke(repository),
                         "Generated code must construct a fresh application reducer for every invocation");

            Object conflicting = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada"),
                    row("contactId", 1L, "name", "Grace"))));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                                                              () -> findContacts.invoke(conflicting));
            Throwable cause = failure.getCause();
            assertEquals("io.helidon.data.DataException", cause.getClass().getName());
            assertTrue(cause.getMessage().contains("Conflicting projected value"));
            assertTrue(!cause.getMessage().contains("Ada") && !cause.getMessage().contains("Grace"),
                       "Conflict diagnostics must not include projected SQL values");
        }
    }

    private static Object property(Object target, String methodName) throws ReflectiveOperationException {
        var method = target.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Map<String, Object> row(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private static Object reducingClient(Class<?> jdbcClientType,
                                         Class<?> rowType,
                                         List<Map<String, Object>> rows) throws ClassNotFoundException {
        Class<?> statementType = Class.forName("io.helidon.data.jdbc.JdbcClient$Statement");
        Object statement = Proxy.newProxyInstance(statementType.getClassLoader(),
                                                  new Class<?>[] {statementType},
                                                  (proxy, method, arguments) -> switch (method.getName()) {
                                                      case "options", "bind", "bindNull" -> proxy;
                                                      case "reduce" -> reduce(arguments[0], rowType, rows);
                                                      case "toString" -> "ReducingStatement";
                                                      default -> throw new UnsupportedOperationException(method.getName());
                                                  });
        return Proxy.newProxyInstance(jdbcClientType.getClassLoader(),
                                      new Class<?>[] {jdbcClientType},
                                      (proxy, method, arguments) -> switch (method.getName()) {
                                          case "create" -> statement;
                                          case "toString" -> "ReducingJdbcClient";
                                          default -> throw new UnsupportedOperationException(method.getName());
                                      });
    }

    private static Object reduce(Object reducer,
                                 Class<?> rowType,
                                 List<Map<String, Object>> rows) throws Throwable {
        var accept = reducer.getClass().getMethod("accept", rowType);
        var finish = reducer.getClass().getMethod("finish");
        accept.setAccessible(true);
        finish.setAccessible(true);
        try {
            for (Map<String, Object> values : rows) {
                accept.invoke(reducer, row(rowType, values));
            }
            return finish.invoke(reducer);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object row(Class<?> rowType, Map<String, Object> values) {
        return Proxy.newProxyInstance(rowType.getClassLoader(),
                                      new Class<?>[] {rowType},
                                      (proxy, method, arguments) -> {
                                          if ("toString".equals(method.getName())) {
                                              return values.toString();
                                          }
                                          if (!(arguments[0] instanceof String label)) {
                                              throw new UnsupportedOperationException("Index-based row access");
                                          }
                                          Object value = values.get(label);
                                          if ("required".equals(method.getName()) && value == null) {
                                              throw new AssertionError("Required test value is absent: " + label);
                                          }
                                          return value;
                                      });
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
