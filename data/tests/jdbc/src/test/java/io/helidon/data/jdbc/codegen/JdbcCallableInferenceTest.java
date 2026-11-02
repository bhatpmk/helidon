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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

class JdbcCallableInferenceTest {
    @Test
    void infersPortableScalarRegistrationsAndOptionalWrappers() throws IOException {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("boolean", "BOOLEAN");
        types.put("byte", "TINYINT");
        types.put("short", "SMALLINT");
        types.put("int", "INTEGER");
        types.put("long", "BIGINT");
        types.put("float", "REAL");
        types.put("double", "DOUBLE");
        types.put("Boolean", "BOOLEAN");
        types.put("Byte", "TINYINT");
        types.put("Short", "SMALLINT");
        types.put("Integer", "INTEGER");
        types.put("Long", "BIGINT");
        types.put("Float", "REAL");
        types.put("Double", "DOUBLE");
        types.put("BigDecimal", "DECIMAL");
        types.put("String", "VARCHAR");
        types.put("byte[]", "VARBINARY");
        types.put("LocalDate", "DATE");
        types.put("LocalTime", "TIME");
        types.put("LocalDateTime", "TIMESTAMP");
        types.put("java.sql.Date", "DATE");
        types.put("java.sql.Time", "TIME");
        types.put("java.sql.Timestamp", "TIMESTAMP");
        StringBuilder methods = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, String> type : types.entrySet()) {
            methods.append("@Jdbc.Statement(\"{call routine(:out" + index + ")}\")\n")
                    .append("@Jdbc.OutParameter(\"out" + index + "\")\n")
                    .append(type.getKey()).append(" value").append(index).append("();\n");
            index++;
        }
        for (String type : List.of("Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "BigDecimal",
                                   "String", "byte[]", "LocalDate", "LocalTime", "LocalDateTime",
                                   "java.sql.Date", "java.sql.Time", "java.sql.Timestamp")) {
            methods.append("@Jdbc.Statement(\"{call routine(:out" + index + ")}\")\n")
                    .append("@Jdbc.OutParameter(\"out" + index + "\")\n")
                    .append("Optional<").append(type).append("> value").append(index).append("();\n");
            types.put("Optional<" + type + ">", types.get(type));
            index++;
        }
        TestCompiler.Result result = compile(methods.toString());
        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = generated(result);
        index = 0;
        for (String registration : types.values()) {
            assertThat(generated, containsString(".out(\"out" + index + "\", 1, JDBCType." + registration + ");"));
            index++;
        }
    }

    @Test
    void infersOutputRecordsAndMaterializedCursors() throws IOException {
        TestCompiler.Result result = compile("""
                record Row(int value) { }
                record Other(String label) { }
                record Outputs(@Jdbc.OutParameter List<Row> rows,
                               @Jdbc.OutParameter(scale = 2) BigDecimal total,
                               @Jdbc.OutParameter Optional<String> status,
                               @Jdbc.OutParameter Optional<List<Integer>> checks,
                               @Jdbc.OutParameter Optional<Row> optionalRow,
                               @Jdbc.OutParameter Row oneRow) { }
                @Jdbc.Statement("{call routine(:MAPPER_AGGREGATE_OUTPUT_0_2, :rows, :total, :status, :checks, :optionalRow, :oneRow)}")
                Outputs aggregate(String MAPPER_AGGREGATE_OUTPUT_0_2);
                @Jdbc.Statement("select 1")
                Other aggregate_OUTPUT_0();
                @Jdbc.Statement("{call routine(:rows)}") @Jdbc.OutParameter("rows")
                List<Row> rows();
                @Jdbc.Statement("{call routine(:rows)}") @Jdbc.OutParameter("rows")
                Optional<List<Integer>> optionalRows();
                @Jdbc.Statement("{call routine(:rows)}") @Jdbc.OutParameter("rows")
                Optional<Row> optionalRow();
                @Jdbc.Statement("{call routine(:rows)}") @Jdbc.OutParameter("rows")
                Row oneRow();
                """);
        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = generated(result);
        assertThat(generated, containsString(".out(\"total\", 3, JDBCType.DECIMAL, 2);"));
        assertThat(generated, containsString(".out(\"status\", 4, JDBCType.VARCHAR);"));
        assertThat(generated, containsString(".out(\"rows\", 1, JDBCType.REF_CURSOR);"));
        assertThat(generated, containsString(".optionalCursor(\"checks\""));
    }

    @Test
    void preservesExplicitOverridesAndResolvesTypedNullInout() throws IOException {
        TestCompiler.Result result = compile("""
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.NULL)
                String explicitNull();
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.NUMERIC)
                String converted();
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.NVARCHAR)
                String national();
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.REF_CURSOR)
                int scalarCursor();
                @Jdbc.Statement("{call routine(:value)}") @Jdbc.OutParameter(value = "value", scale = 2)
                BigDecimal inout(BigDecimal value);
                """);
        assertThat(result.diagnostics().toString(), result.success(), is(true));
        String generated = generated(result);
        for (String type : List.of("NULL", "NUMERIC", "NVARCHAR", "REF_CURSOR")) {
            assertThat(generated, containsString(".out(\"return\", 1, JDBCType." + type + ");"));
        }
        assertThat(generated, containsString(".out(\"value\", 1, JDBCType.DECIMAL, 2);"));
        assertThat(generated, containsString("GeneratedJdbcData.bindNull(jdbcStatement, 1, JDBCType.DECIMAL);"));
    }

    @Test
    void requiresExplicitTypesWhenOutputShapeCannotDetermineRegistration() {
        for (String method : List.of(
                "void execute()",
                "JdbcClient.OutParameters execute()",
                "void execute(Consumer<JdbcClient.CallResult> action)",
                "String execute(JdbcClient.CallMapper<String> mapper)")) {
            TestCompiler.Result result = compile("""
                    @Jdbc.Statement("{call routine(:value)}") @Jdbc.OutParameter("value")
                    """ + method + ";");
            assertThat(result.diagnostics().toString(), result.success(), is(false));
            assertThat(result.diagnostics().toString(), containsString("explicit JDBC type"));
        }
        for (String attributes : List.of("typeName = \"APP.STATUS\"", "scale = 2")) {
            TestCompiler.Result result = compile("""
                    @Jdbc.Statement("{? = call routine()}")
                    @Jdbc.OutParameter(""" + attributes + ") String execute();");
            assertThat(result.diagnostics().toString(), result.success(), is(false));
        }
        TestCompiler.Result multiple = compile("""
                @Jdbc.Statement("{call routine(:value, :other)}")
                @Jdbc.OutParameter("value") @Jdbc.OutParameter(value = "other", type = JDBCType.INTEGER)
                int execute();
                """);
        assertThat(multiple.diagnostics().toString(), multiple.success(), is(false));
        assertThat(multiple.diagnostics().toString(), containsString("explicit JDBC type"));
    }

    @Test
    void rejectsAmbiguousAndUnsupportedInferredOutputs() {
        for (String type : List.of("List", "Optional", "Optional<Optional<Integer>>", "List<List<Integer>>",
                                   "Stream<Integer>", "java.sql.Blob", "java.util.UUID", "java.time.OffsetTime",
                                   "java.math.BigInteger", "Object")) {
            TestCompiler.Result result = compile("""
                    @Jdbc.Statement("{call routine(:value)}") @Jdbc.OutParameter("value")
                    """ + type + " execute();");
            assertThat(type + result.diagnostics(), result.success(), is(false));
        }
        TestCompiler.Result unresolved = compile("""
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter
                <T> T execute();
                """);
        assertThat(unresolved.diagnostics().toString(), unresolved.success(), is(false));
        TestCompiler.Result scalarMapper = compile("""
                @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter @Jdbc.RowMapper
                Integer execute();
                """);
        assertThat(scalarMapper.diagnostics().toString(), scalarMapper.success(), is(false));
        assertThat(scalarMapper.diagnostics().toString(), containsString("explicit REF_CURSOR"));
        TestCompiler.Result inout = compile("""
                @Jdbc.Statement("{call routine(:value)}") @Jdbc.OutParameter("value")
                int execute(String value);
                """);
        assertThat(inout.diagnostics().toString(), inout.success(), is(false));
        assertThat(inout.diagnostics().toString(), containsString("INOUT"));
    }

    @Test
    void resolvesEquivalentSourceAndSeparatelyCompiledGenericParents() throws IOException {
        for (boolean separate : List.of(false, true)) {
            TestCompiler.Builder child = compiler();
            TestCompiler.Builder parents = separate ? TestCompiler.builder().currentRelease()
                    .addClasspath(List.of(RuntimeType.class, JdbcClient.class)) : child;
            parents.addSource("example/Parent.java", """
                    package example;
                    import io.helidon.data.jdbc.Jdbc;
                    import java.sql.JDBCType;
                    public interface Parent<T> {
                        @Jdbc.Statement("{? = call routine(?)}") @Jdbc.OutParameter
                        T value(T value);
                        @Jdbc.Statement("{call routine(:total)}") Outputs<T> aggregate();
                        @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.NULL)
                        String explicitNull();
                    }
                    """).addSource("example/Other.java", """
                    package example;
                    import io.helidon.data.jdbc.Jdbc;
                    import java.sql.JDBCType;
                    public interface Other {
                        @Jdbc.Statement("{? = call routine(?)}") @Jdbc.OutParameter(type = JDBCType.INTEGER)
                        Integer value(Integer value);
                    }
                    """).addSource("example/Outputs.java", """
                    package example;
                    import io.helidon.data.jdbc.Jdbc;
                    public record Outputs<T>(@Jdbc.OutParameter T total) { }
                    """);
            if (separate) {
                TestCompiler.Result library = parents.build().compile();
                assertThat(library.diagnostics().toString(), library.success(), is(true));
                child.addClasspathEntry(library.classOutput());
            }
            TestCompiler.Result result = child.addSource("example/Repository.java", """
                    package example;
                    import io.helidon.data.Data;
                    @Data.Repository @Data.Provider("jdbc")
                    public interface Repository extends Parent<Integer>, Other { }
                    """).build().compile();
            assertThat("separate=" + separate + result.diagnostics(), result.success(), is(true));
            assertThat(generated(result), containsString(".out(\"total\", 1, JDBCType.INTEGER);"));
            assertThat(generated(result), containsString(".out(\"return\", 1, JDBCType.NULL);"));
        }
    }

    @Test
    void rejectsGenuinelyDifferentInheritedRegistrations() {
        TestCompiler.Result result = compiler().addSource("example/Repository.java", """
                package example;
                import io.helidon.data.Data;
                import io.helidon.data.jdbc.Jdbc;
                import java.sql.JDBCType;
                import java.math.BigDecimal;
                interface First {
                    @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter
                    BigDecimal value();
                }
                interface Second {
                    @Jdbc.Statement("{? = call routine()}") @Jdbc.OutParameter(type = JDBCType.NUMERIC)
                    BigDecimal value();
                }
                @Data.Repository @Data.Provider("jdbc")
                public interface Repository extends First, Second { }
                """).build().compile();
        assertThat(result.diagnostics().toString(), result.success(), is(false));
        assertThat(result.diagnostics().toString(), containsString("conflicting"));
    }

    private static String generated(TestCompiler.Result result) throws IOException {
        return Files.readString(result.sourceOutput().resolve("example/Repository__Jdbc.java"));
    }

    private static TestCompiler.Result compile(String declarations) {
        return compiler().addSource("example/Repository.java", """
                package example;
                import io.helidon.data.Data;
                import io.helidon.data.jdbc.Jdbc;
                import io.helidon.data.jdbc.JdbcClient;
                import java.sql.JDBCType;
                import java.math.BigDecimal;
                import java.time.LocalDate;
                import java.time.LocalTime;
                import java.time.LocalDateTime;
                import java.util.List;
                import java.util.Optional;
                import java.util.function.Consumer;
                import java.util.stream.Stream;
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
}
