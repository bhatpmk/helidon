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
import java.io.Writer;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

import io.helidon.builder.api.RuntimeType;
import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.TestCompiler;
import io.helidon.common.Generated;
import io.helidon.common.types.TypeName;
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

class JdbcCallableGenerationTest {
    @Test
    void rejectsMissingExplicitRegistrationType() {
        TestCompiler.Result result = compile("""
                @Jdbc.Statement("{call routine(:value)}")
                @Jdbc.OutParameter("value")
                void execute();
                """);
        assertThat(result.success(), is(false));
        assertThat(String.join("\n", result.diagnostics()), containsString("type"));
    }

    @Test
    void rejectsRepeatedOutputAndInvalidScale() {
        TestCompiler.Result repeated = compile("""
                @Jdbc.Statement("{call routine(:value, :value)}")
                @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
                int execute();
                """);
        assertThat(repeated.success(), is(false));
        assertThat(String.join("\n", repeated.diagnostics()), containsString("exactly once"));
        TestCompiler.Result scale = compile("""
                @Jdbc.Statement("{call routine(:value)}")
                @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER, scale = 2)
                int execute();
                """);
        assertThat(scale.success(), is(false));
        assertThat(String.join("\n", scale.diagnostics()), containsString("scale"));
    }

    @Test
    void rejectsQueryOutputsAndSnapshotCursors() {
        TestCompiler.Result query = compile("""
                @Jdbc.Statement("{call routine(:value)}")
                @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
                @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
                int execute();
                """);
        assertThat(query.success(), is(false));
        TestCompiler.Result cursor = compile("""
                @Jdbc.Statement("{call routine(:value)}")
                @Jdbc.OutParameter(value = "value", type = JDBCType.REF_CURSOR)
                io.helidon.data.jdbc.JdbcClient.OutParameters execute();
                """);
        assertThat(cursor.success(), is(false));
        assertThat(String.join("\n", cursor.diagnostics()), containsString("snapshot"));
    }

    @Test
    void rejectsNestedCursorRowsAndScopedResultsEvenWithAMapper() {
        for (String type : List.of("java.util.List<java.util.List<String>>",
                                   "java.util.Optional<java.util.Optional<String>>",
                                   "io.helidon.data.jdbc.JdbcClient.OutParameters", "java.sql.Blob")) {
            TestCompiler.Result result = compile("""
                    @Jdbc.Statement("{call routine(:rows)}")
                    @Jdbc.OutParameter(value = "rows", type = JDBCType.REF_CURSOR)
                    @Jdbc.RowMapper
                    """ + type + " execute();");
            assertThat(String.join("\n", result.diagnostics()), result.success(), is(false));
        }
    }

    /**
     * CLASS retained outputs and generic input types survive separate parent compilation.
     */
    @Test
    void generatesFromSeparatelyCompiledParent() {
        TestCompiler.Result parent = TestCompiler.builder().currentRelease().printDiagnostics(false)
                .addClasspath(List.of(RuntimeType.class, JdbcClient.class))
                .addSource("Parent.java", """
                        package example;
                        import io.helidon.data.jdbc.Jdbc;
                        import java.sql.JDBCType;
                        public interface Parent<T> {
                            @Jdbc.Statement("{? = call routine(?)}")
                            @Jdbc.OutParameter(type = JDBCType.INTEGER)
                            <E extends Exception> T execute(T value) throws E;

                            @Jdbc.Statement("{call routine(:value, :rows)}")
                            Outputs<T> aggregate();
                        }
                        """)
                .addSource("Outputs.java", """
                        package example;
                        import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
                        import java.sql.JDBCType;
                        import io.helidon.data.jdbc.Jdbc;
                        public record Outputs<T>(@Jdbc.OutParameter(type = JDBCType.INTEGER) int value,
                                                 @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
                                                 @Jdbc.RowMapper(IntegerMapper.class) List<T> rows) {
                        }
                        """)
                .addSource("IntegerMapper.java", """
                        package example;
                        import io.helidon.data.jdbc.JdbcClient;
                        public final class IntegerMapper implements JdbcClient.RowMapper<Integer> {
                            public Integer map(JdbcClient.Row row) { return row.get(1, Integer.class); }
                        }
                        """).build().compile();
        assertThat(String.join("\n", parent.diagnostics()), parent.success(), is(true));
        TestCompiler.Result child = compiler().addClasspathEntry(parent.classOutput())
                .addSource("Child.java", """
                        package example;
                        import io.helidon.data.Data;
                        @Data.Repository @Data.Provider("jdbc")
                        public interface Child extends Parent<Integer> {
                        }
                        """).build().compile();
        assertThat(String.join("\n", child.diagnostics()), child.success(), is(true));
    }

    /**
     * Equivalent repeated declarations retain their meaning across annotation containers, defaults, and hierarchy depths.
     */
    @Test
    void normalizesEquivalentOutputsFromUnrelatedParents() {
        TestCompiler.Result result = compiler().addSource("Child.java", """
                package example;
                import io.helidon.data.Data;
                import io.helidon.data.jdbc.Jdbc;
                import io.helidon.data.jdbc.JdbcClient;
                import java.sql.JDBCType;
                interface Root {
                    @Jdbc.Statement("{call routine(:value, :other)}")
                    @Jdbc.OutParameterDeclarations({@Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER),
                                                    @Jdbc.OutParameter(value = "other", type = JDBCType.VARCHAR)})
                    JdbcClient.OutParameters execute();
                }
                interface Middle extends Root { }
                interface Other {
                    @Jdbc.Statement("{call routine(:value, :other)}")
                    @Jdbc.OutParameter(value = "other", type = JDBCType.VARCHAR, scale = -1, typeName = "")
                    @Jdbc.OutParameter(value = "value", type = JDBCType.INTEGER)
                    JdbcClient.OutParameters execute();
                }
                @Data.Repository @Data.Provider("jdbc")
                public interface Child extends Middle, Other { }
                """).build().compile();
        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
    }

    /**
     * Result records and mapper services emitted together by another processor remain available to JDBC generation.
     */
    @Test
    void compilesResultAndMapperGeneratedInTheSameRound() {
        TestCompiler.Result result = compiler().processors(List.of(new GeneratedTypesProcessor(), new AptProcessor()))
                .addSource("Trigger.java", "package example; public class Trigger { }").build().compile();
        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        assertThat(Files.exists(result.classOutput().resolve("example/GeneratedRepository__Jdbc.class")), is(true));
    }

    /**
     * Component mapper names remain distinct from ordinary repository mapper names in either declaration order.
     */
    @Test
    void reservesComponentMapperNamesAcrossRepositoryMethods() {
        String aggregate = """
                @Jdbc.Statement("{call routine(:rows)}")
                Outputs aggregate();
                """;
        String query = """
                @Jdbc.Statement("SELECT value FROM TEST")
                Other aggregate_OUTPUT_0();
                """;
        String records = """
                record Outputs(@Jdbc.OutParameter(type = JDBCType.REF_CURSOR) List<Projection> rows) { }
                record Projection(int value) { }
                record Other(String value) { }
                """;
        for (String methods : List.of(aggregate + query, query + aggregate)) {
            TestCompiler.Result result = compile(methods + records);
            assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        }
    }

    /**
     * Parameter renaming reserves both component mapper names and mapper names reused from ordinary queries.
     */
    @Test
    void reservesFinalComponentMapperNamesAgainstInputParameters() {
        for (String mapper : List.of("MAPPER_AGGREGATE_OUTPUT_0", "MAPPER_PROJECTION")) {
            String query = mapper.equals("MAPPER_PROJECTION")
                    ? "@Jdbc.Statement(\"SELECT value FROM TEST\") Projection projection();\n" : "";
            TestCompiler.Result result = compile(query + """
                    @Jdbc.Statement("{call routine(:%s, :rows)}")
                    Outputs aggregate(String %s);
                    record Outputs(@Jdbc.OutParameter(type = JDBCType.REF_CURSOR) List<Projection> rows) { }
                    record Projection(int value) { }
                    """.formatted(mapper, mapper));
            assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        }
    }

    private static TestCompiler.Result compile(String declarations) {
        return compiler().addSource("Repository.java", """
                package example;
                import io.helidon.data.Data;
                import io.helidon.data.jdbc.Jdbc;
                import java.sql.JDBCType;
                import java.util.List;
                @Data.Repository @Data.Provider("jdbc")
                public interface Repository {
                """ + declarations + "\n}").build().compile();
    }

    private static TestCompiler.Builder compiler() {
        return TestCompiler.builder().currentRelease().printDiagnostics(false).addProcessor(AptProcessor::new)
                .addClasspath(List.of(RuntimeType.class, Data.class, JdbcClient.class, Service.class, Tx.class,
                                      Generated.class, TypeName.class, DataGeneratorProvider.class,
                                      RepositoryCodegenProvider.class, JdbcRepositoryGeneratorProvider.class));
    }
    private static final class GeneratedTypesProcessor extends AbstractProcessor {
        private boolean written;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_27;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (written || round.processingOver()) {
                return false;
            }
            written = true;
            write("example.GeneratedRow", """
                    package example;
                    public record GeneratedRow(int value) {
                    }
                    """);
            write("example.GeneratedMapper", """
                    package example;
                    import io.helidon.data.jdbc.JdbcClient;
                    import io.helidon.service.registry.Service;
                    @Service.Singleton
                    public class GeneratedMapper implements JdbcClient.RowMapper<GeneratedRow> {
                        public GeneratedRow map(JdbcClient.Row row) { return new GeneratedRow(row.get(1, Integer.class)); }
                    }
                    """);
            write("example.GeneratedRepository", """
                    package example;
                    import io.helidon.data.Data;
                    import io.helidon.data.jdbc.Jdbc;
                    import java.sql.JDBCType;
                    import java.util.List;
                    @Data.Repository @Data.Provider("jdbc")
                    public interface GeneratedRepository {
                        @Jdbc.Statement("{call routine(:rows)}")
                        @Jdbc.OutParameter(value = "rows", type = JDBCType.REF_CURSOR)
                        @Jdbc.RowMapper(GeneratedMapper.class)
                        List<GeneratedRow> execute();
                    }
                    """);
            return false;
        }

        private void write(String name, String source) {
            try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                writer.write(source);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }

}
