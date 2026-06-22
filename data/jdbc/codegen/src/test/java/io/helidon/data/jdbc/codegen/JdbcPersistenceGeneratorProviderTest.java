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
        assertThat(generated, containsString("return this.jdbc.list("));
        assertThat(generated, containsString("return this.jdbc.optional("));
        assertThat(generated, containsString("return this.jdbc.generatedKey("));
        assertThat(generated, containsString(").orElseThrow"));
        assertThat(generated, containsString("statement.setObject(1, name);"));
        assertThat(generated, containsString("row.getLong(\"pokemon_id\")"));
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
    }

    private static Path moduleClasses(String module) {
        return rootDirectory()
                .resolve(module)
                .resolve("target/classes");
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
