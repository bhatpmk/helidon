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
import java.util.Optional;

import io.helidon.codegen.apt.AptProcessor;
import io.helidon.codegen.testing.TestCompiler;
import io.helidon.data.codegen.DataGeneratorProvider;
import io.helidon.data.codegen.common.RepositoryCodegenProvider;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMethodGeneratorTest {

    @Test
    void generatesSharedStandardInputBindings() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("TypedBindingRepository.java", """
                        package example;

                        import java.sql.JDBCType;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface TypedBindingRepository {
                            @Jdbc.Statement("select ID from JOB where STATE = :state")
                            java.util.List<Long> find(@Jdbc.BindType(JDBCType.VARCHAR) String state);

                            @Jdbc.Statement("update JOB set STATE = :state where CURRENT_STATE = :state "
                                    + "and TENANT_KIND = :tenantKind")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            long update(@Jdbc.BindType(JDBCType.VARCHAR) String state,
                                        int tenantKind);

                            @Jdbc.Statement("insert into JOB(STATE) values (:state)")
                            @Jdbc.GeneratedKeys("ID")
                            long insert(@Jdbc.BindType(JDBCType.VARCHAR) String state);

                            @Jdbc.Statement("{call PROCESS(:jobId, :state)}")
                            @Jdbc.Execution(Jdbc.ExecutionType.CALL)
                            void process(@Jdbc.InParameter @Jdbc.BindType(JDBCType.BIGINT) long jobId,
                                         @Jdbc.InParameter String state);
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/TypedBindingRepository__Jdbc.java"));
        assertThat(source, containsString(".bind(1, state, JDBCType.VARCHAR).map(Long.class).list()"));
        assertThat(source,
                   containsString(".bind(1, state, JDBCType.VARCHAR).bind(2, state, JDBCType.VARCHAR)"
                                          + ".bind(3, tenantKind).execute()"));
        assertThat(source,
                   containsString(".bind(1, state, JDBCType.VARCHAR)"
                                          + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one()"));
        assertThat(source, containsString(".in(1).in(2).build()"));
        assertThat(source,
                   containsString(".bind(1, jobId, JDBCType.BIGINT).bind(2, state)"
                                          + ".call(CALL_PROCESS)"));
    }

    @Test
    void rejectsInvalidExplicitInputBindingTypes() {
        assertCompilationFailure("NullBindTypeRepository.java", """
                        package example;
                        import java.sql.JDBCType;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NullBindTypeRepository {
                            @Jdbc.Statement("update JOB set STATE = :state")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            long invalid(@Jdbc.BindType(JDBCType.NULL) String state);
                        }
                        """,
                                 "@Jdbc.BindType does not support JDBCType.NULL");
        assertCompilationFailure("CursorBindTypeRepository.java", """
                        package example;
                        import java.sql.JDBCType;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CursorBindTypeRepository {
                            @Jdbc.Statement("{call PROCESS(:state)}")
                            void invalid(@Jdbc.InParameter @Jdbc.BindType(JDBCType.REF_CURSOR) String state);
                        }
                        """,
                                 "@Jdbc.BindType does not support JDBCType.REF_CURSOR");
        assertCompilationFailure("InOutBindTypeRepository.java", """
                        package example;
                        import java.sql.JDBCType;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface InOutBindTypeRepository {
                            @Jdbc.Statement("{call PROCESS(:state)}")
                            String invalid(@Jdbc.InOutParameter(jdbcType = Types.VARCHAR)
                                           @Jdbc.BindType(JDBCType.VARCHAR) String state);
                        }
                        """,
                                 "@Jdbc.InOutParameter.jdbcType controls both directions");
        assertCompilationFailure("ControlBindTypeRepository.java", """
                        package example;
                        import java.sql.JDBCType;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcStatementOptions;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ControlBindTypeRepository {
                            @Jdbc.Statement("select STATE from JOB")
                            String invalid(@Jdbc.BindType(JDBCType.VARCHAR) JdbcStatementOptions options);
                        }
                        """,
                                 "invocation-control parameters cannot declare an input binding type");
    }

    @Test
    void generatesCallableLayoutsBindingsAndTerminals() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("ProcedureRepository.java", """
                        package example;

                        import java.math.BigDecimal;
                        import java.sql.Types;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface ProcedureRepository {
                            @Jdbc.Statement("{call TRANSFER(:accountId, :balance, :orders, :status)}")
                            @Jdbc.Execution(Jdbc.ExecutionType.CALL)
                            @Jdbc.OutParameter(
                                    name = "orders",
                                    jdbcType = Types.REF_CURSOR,
                                    kind = Jdbc.OutputKind.CURSOR)
                            @Jdbc.OutParameter(
                                    name = "status",
                                    jdbcType = Types.VARCHAR,
                                    javaType = String.class,
                                    typeName = "STATUS_TYPE")
                            TransferResult transfer(
                                    JdbcResultRequest.CallWith<TransferResult> request,
                                    @Jdbc.InParameter(name = "accountId") long accountId,
                                    @Jdbc.InOutParameter(name = "balance", jdbcType = Types.DECIMAL, scale = 2)
                                    BigDecimal balance);

                            @Jdbc.Statement("{:result = call CALCULATE_FEE(:accountId)}")
                            @Jdbc.ReturnParameter(
                                    name = "result",
                                    jdbcType = Types.DECIMAL,
                                    javaType = BigDecimal.class)
                            BigDecimal calculateFee(
                                    JdbcResultRequest.CallWith<BigDecimal> request,
                                    @Jdbc.InParameter(name = "accountId") long accountId);

                            @Jdbc.Statement("{call TOUCH(:accountId)}")
                            void touch(@Jdbc.InParameter(name = "accountId") long accountId);

                            @Jdbc.Statement("{call POSITIONAL(?, ?)}")
                            @Jdbc.OutParameter(
                                    name = "status",
                                    index = 2,
                                    jdbcType = Types.VARCHAR,
                                    javaType = String.class)
                            String positional(
                                    JdbcResultRequest.CallWith<String> request,
                                    @Jdbc.InParameter(index = 1) long accountId);

                            @Jdbc.Statement("{call DIRECT_RESULTS()}")
                            @Jdbc.Execution(Jdbc.ExecutionType.CALL)
                            void directResults(JdbcResultRequest.Call request);

                            @Jdbc.Statement("{:rows = call FIND_ROWS(:accountId)}")
                            @Jdbc.ReturnParameter(
                                    name = "rows",
                                    jdbcType = -10,
                                    javaType = Void.class,
                                    typeName = "SYS_REFCURSOR",
                                    kind = Jdbc.OutputKind.CURSOR)
                            void findRows(
                                    JdbcResultRequest.Call request,
                                    @Jdbc.InParameter(name = "accountId") long accountId);

                            @Jdbc.Statement("{call NORMALIZE(:state)}")
                            String normalize(
                                    @Jdbc.InOutParameter(
                                            name = "state",
                                            jdbcType = Types.STRUCT,
                                            typeName = "ORDER_STATE")
                                    String state);

                        }

                        record TransferResult(BigDecimal balance, String status) {
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/ProcedureRepository__Jdbc.java"));
        assertThat(source, containsString("JdbcCall CALL_TRANSFER = JdbcCall.builder()"));
        assertThat(source, containsString(".in(1).inOut(2, \"balance\", 3, BigDecimal.class, 2)"
                                                  + ".cursor(3, \"orders\").out(4, \"status\", 12, String.class, "
                                                  + "\"STATUS_TYPE\")"
                                                  + ".build()"));
        assertThat(source, containsString(".bind(1, accountId).bind(2, balance).call(CALL_TRANSFER, request)"));
        assertThat(source, containsString(".returns(\"result\", 3, BigDecimal.class).in(2).build()"));
        assertThat(source, containsString(".bind(2, accountId).call(CALL_CALCULATE_FEE, request)"));
        assertThat(source, containsString(".bind(1, accountId).call(CALL_TOUCH)"));
        assertThat(source, containsString(".in(1).out(2, \"status\", 12, String.class).build()"));
        assertThat(source, containsString(".bind(1, accountId).call(CALL_POSITIONAL, request)"));
        assertThat(source, containsString("jdbcClient.create(SQL_DIRECT_RESULTS).call(CALL_DIRECT_RESULTS, request)"));
        assertThat(source, containsString(".returnsCursor(\"rows\", -10, \"SYS_REFCURSOR\").in(2).build()"));
        assertThat(source, containsString(".bind(2, accountId).call(CALL_FIND_ROWS, request)"));
        assertThat(source,
                   containsString(".inOut(1, \"state\", 2002, String.class, \"ORDER_STATE\").build()"));
    }

    @Test
    void generatesDetachedScalarOptionalAndRecordCallReturns() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("DetachedCallRepository.java", """
                        package example;

                        import java.sql.Types;
                        import java.time.LocalDate;
                        import java.util.Optional;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcStatementOptions;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface DetachedCallRepository {
                            @Jdbc.Statement("{call STATUS(:status)}")
                            @Jdbc.OutParameter(
                                    name = "status",
                                    jdbcType = Types.VARCHAR,
                                    javaType = String.class)
                            String status();

                            @Jdbc.Statement("{call NOTE(:note)}")
                            @Jdbc.OutParameter(
                                    name = "note",
                                    jdbcType = Types.VARCHAR,
                                    javaType = String.class)
                            Optional<String> note();

                            @Jdbc.Statement("{call ARCHIVE(:before, :archivedCount, :warning)}")
                            @Jdbc.OutParameter(
                                    name = "archivedCount",
                                    jdbcType = Types.INTEGER,
                                    javaType = Integer.class)
                            @Jdbc.OutParameter(
                                    name = "warning",
                                    jdbcType = Types.VARCHAR,
                                    javaType = String.class)
                            ArchiveResult archive(
                                    JdbcStatementOptions options,
                                    @Jdbc.InParameter(name = "before") LocalDate before);
                        }

                        record ArchiveResult(int archivedCount, Optional<String> warning) {
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/DetachedCallRepository__Jdbc.java"));
        assertThat(source,
                   containsString("CallOutputValues callOutputValues = jdbcClient.create(SQL_STATUS)"
                                          + ".callForOutputs(CALL_STATUS);"));
        assertThat(source, containsString("return callOutputValues.required(\"status\", String.class);"));
        assertThat(source, containsString("return callOutputValues.optional(\"note\", String.class);"));
        assertThat(source,
                   containsString("jdbcClient.create(SQL_ARCHIVE).options(options).bind(1, before)"
                                          + ".callForOutputs(CALL_ARCHIVE);"));
        assertThat(source,
                   containsString("return new ArchiveResult(callOutputValues.required(\"archivedCount\", Integer.class), "
                                          + "callOutputValues.optional(\"warning\", String.class));"));
    }

    @Test
    void rejectsInvalidCallableContracts() {
        assertCompilationFailure("MissingInputDeclarationRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingInputDeclarationRepository {
                            @Jdbc.Statement("{call PROCESS(?)}")
                            @Jdbc.Execution(Jdbc.ExecutionType.CALL)
                            void invalid(String value);
                        }
                        """,
                                 "requires @Jdbc.InParameter or @Jdbc.InOutParameter");
        assertCompilationFailure("MixedMarkersRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MixedMarkersRepository {
                            @Jdbc.Statement("{call PROCESS(:value, ?)}")
                            void invalid(JdbcResultRequest.Call request,
                                         @Jdbc.InParameter(name = "value") String value,
                                         @Jdbc.InParameter(index = 2) int count);
                        }
                        """,
                                 "cannot mix named and positional markers");
        assertCompilationFailure("MismatchedCallLocatorRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MismatchedCallLocatorRepository {
                            @Jdbc.Statement("{call PROCESS(:value)}")
                            void invalid(@Jdbc.InParameter(name = "value", index = 2) String value);
                        }
                        """,
                                 "resolves to position 1 but index is 2");
        assertCompilationFailure("OutputWithoutCallbackRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface OutputWithoutCallbackRepository {
                            @Jdbc.Statement("{call PROCESS(?)}")
                            @Jdbc.OutParameter(name = "status", index = 1,
                                               jdbcType = Types.VARCHAR, javaType = String.class)
                            void invalid();
                        }
                        """,
                                 "void JDBC call cannot declare outputs without JdbcResultRequest.Call");
        assertCompilationFailure("DetachedCursorRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DetachedCursorRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            @Jdbc.OutParameter(
                                    name = "rows",
                                    index = 1,
                                    jdbcType = Types.REF_CURSOR,
                                    kind = Jdbc.OutputKind.CURSOR)
                            String invalid();
                        }
                        """,
                                 "cursor outputs requires a JdbcResultRequest.Call or CallWith");
        assertCompilationFailure("DetachedTypeMismatchRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DetachedTypeMismatchRepository {
                            @Jdbc.Statement("{call STATUS(?)}")
                            @Jdbc.OutParameter(name = "status", index = 1,
                                               jdbcType = Types.VARCHAR, javaType = String.class)
                            long invalid();
                        }
                        """,
                                 "output 'status' is declared as java.lang.String");
        assertCompilationFailure("DetachedRecordMismatchRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DetachedRecordMismatchRepository {
                            @Jdbc.Statement("{call STATUS(?)}")
                            @Jdbc.OutParameter(name = "status", index = 1,
                                               jdbcType = Types.VARCHAR, javaType = String.class)
                            Result invalid();
                        }
                        record Result(String message) {}
                        """,
                                 "record component has no matching output: message");
        assertCompilationFailure("DuplicateOutputRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DuplicateOutputRepository {
                            @Jdbc.Statement("{call PROCESS(?, ?)}")
                            @Jdbc.OutParameter(name = "status", index = 1,
                                               jdbcType = Types.VARCHAR, javaType = String.class)
                            @Jdbc.OutParameter(name = "status", index = 2,
                                               jdbcType = Types.INTEGER, javaType = Integer.class)
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "Duplicate JDBC call output name: status");
        assertCompilationFailure("MissingFunctionReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingFunctionReturnRepository {
                            @Jdbc.Statement("{? = call TOTAL(?)}")
                            Long invalid(JdbcResultRequest.CallWith<Long> request,
                                         @Jdbc.InParameter(index = 2) long id);
                        }
                        """,
                                 "JDBC function syntax requires @Jdbc.ReturnParameter");
        assertCompilationFailure("ReturnOnProcedureRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ReturnOnProcedureRepository {
                            @Jdbc.Statement("{call TOTAL(?)}")
                            @Jdbc.ReturnParameter(name = "result", jdbcType = Types.BIGINT, javaType = Long.class)
                            Long invalid(JdbcResultRequest.CallWith<Long> request,
                                         @Jdbc.InParameter(index = 1) long id);
                        }
                        """,
                                 "@Jdbc.ReturnParameter requires JDBC function escape syntax");
        assertCompilationFailure("InvalidCursorRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface InvalidCursorRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            @Jdbc.OutParameter(name = "rows", index = 1,
                                               jdbcType = Types.REF_CURSOR, javaType = String.class,
                                               kind = Jdbc.OutputKind.CURSOR)
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "cursor output must leave javaType as Void.class");
        assertCompilationFailure("InvalidScalarOutputRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface InvalidScalarOutputRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            @Jdbc.OutParameter(name = "status", index = 1, jdbcType = Types.VARCHAR)
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "scalar OUT parameter requires a supported javaType");
        assertCompilationFailure("ImplicitCursorKindRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ImplicitCursorKindRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            @Jdbc.OutParameter(name = "rows", index = 1, jdbcType = Types.REF_CURSOR)
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "Types.REF_CURSOR requires kind = Jdbc.OutputKind.CURSOR");
        assertCompilationFailure("ConflictingRegistrationRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ConflictingRegistrationRepository {
                            @Jdbc.Statement("{call TOTAL(?)}")
                            @Jdbc.OutParameter(name = "total", index = 1,
                                               jdbcType = Types.DECIMAL, javaType = java.math.BigDecimal.class,
                                               typeName = "MONEY", scale = 2)
                            java.math.BigDecimal invalid();
                        }
                        """,
                                 "cannot declare both typeName and scale");
        assertCompilationFailure("CursorScaleRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CursorScaleRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            @Jdbc.OutParameter(name = "rows", index = 1, jdbcType = Types.REF_CURSOR,
                                               scale = 2, kind = Jdbc.OutputKind.CURSOR)
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "cursor output cannot declare a scale");
        assertCompilationFailure("DetachedCursorReturnRepository.java", """
                        package example;
                        import java.sql.Types;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DetachedCursorReturnRepository {
                            @Jdbc.Statement("{? = call READ()}")
                            @Jdbc.ReturnParameter(name = "rows", jdbcType = Types.REF_CURSOR,
                                                  javaType = Void.class,
                                                  kind = Jdbc.OutputKind.CURSOR)
                            String invalid();
                        }
                        """,
                                 "cursor outputs requires a JdbcResultRequest.Call or CallWith");
        assertCompilationFailure("NamedPositionalInputRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NamedPositionalInputRepository {
                            @Jdbc.Statement("{call PROCESS(?)}")
                            void invalid(@Jdbc.InParameter(name = "value", index = 1) String value);
                        }
                        """,
                                 "Positional @Jdbc.InParameter must omit name and use index");
        assertCompilationFailure("BlankCallInputNameRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface BlankCallInputNameRepository {
                            @Jdbc.Statement("{call PROCESS(:value)}")
                            void invalid(@Jdbc.InParameter(name = " ") String value);
                        }
                        """,
                                 "JDBC call parameter name must not be blank");
        assertCompilationFailure("CallResultMismatchRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CallResultMismatchRepository {
                            @Jdbc.Statement("{call READ()}")
                            String invalid(JdbcResultRequest.CallWith<Long> request);
                        }
                        """,
                                 "CallWith result type must exactly match");
        assertCompilationFailure("CallReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CallReturnRepository {
                            @Jdbc.Statement("{call READ()}")
                            String invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "Call methods must return void");
        assertCompilationFailure("CallMappingRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CallMappingRepository {
                            @Jdbc.Statement("{call READ()}")
                            @Jdbc.RowMapper
                            void invalid(JdbcResultRequest.Call request);
                        }
                        """,
                                 "CALL execution does not support repository-level row mapping annotations");
        assertCompilationFailure("CallOptionsRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        import io.helidon.data.jdbc.JdbcStatementOptions;
                        @Data.Repository @Data.Provider("jdbc")
                        interface CallOptionsRepository {
                            @Jdbc.Statement("{call READ()}")
                            void invalid(JdbcResultRequest.Call request, JdbcStatementOptions options);
                        }
                        """,
                                 "cannot be combined with JdbcResultRequest");
        assertCompilationFailure("AnnotatedCallRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface AnnotatedCallRequestRepository {
                            @Jdbc.Statement("{call READ(?)}")
                            void invalid(@Jdbc.InParameter(index = 1) JdbcResultRequest.Call request);
                        }
                        """,
                                 "invocation-control parameters cannot be IN or INOUT");
    }

    @Test
    void generatesDirectPublicClientCalls() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("PokemonRepository.java", """
                        package example;

                        import java.util.List;
                        import java.util.Optional;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        import io.helidon.service.registry.Service;
                        import io.helidon.transaction.Tx;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface PokemonRepository {
                            @Jdbc.Statement("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            List<Pokemon> find(long minimum);

                            @Jdbc.Statement("select NAME from POKEMON where ID = :id")
                            Optional<String> name(long id);

                            @Jdbc.Statement("select count(*) from POKEMON")
                            @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
                            long count();

                            @Jdbc.Statement("update POKEMON set NAME = :name where ID = :id")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            @Tx.Required
                            long rename(String name, long id);

                            @Jdbc.Statement("update POKEMON set NAME = :name where ID = :id")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            int renameInt(String name, long id);

                            @Jdbc.Statement("insert into POKEMON(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            long insert(String name);

                            @Jdbc.Statement("insert into POKEMON(NAME) values (:name)")
                            @Jdbc.GeneratedKeys
                            long insertDefault(String name);

                            @Jdbc.Statement("insert into POKEMON(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            GeneratedPokemon insertRecord(String name);

                            @Jdbc.Statement("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Jdbc.RowMapper(PokemonMapper.class)
                            Pokemon mapped(long id);

                            @Jdbc.Statement("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Jdbc.RowMapper()
                            Pokemon mappedByGenericService(long id);

                            @Jdbc.Statement("select cast(ID as bigint) as id, NAME as name from POKEMON where ID >= :minimum")
                            void visitRecords(JdbcResultRequest.VisitAll<Pokemon> request, long minimum);

                            @Jdbc.Statement("select ID as id, NAME as name from POKEMON where ID = :id")
                            @Jdbc.RowMapper(PokemonMapper.class)
                            void visitMapped(JdbcResultRequest.VisitAll<Pokemon> request, long id);

                        }

                        record Pokemon(long id, Optional<String> name) {
                        }

                        record GeneratedPokemon(long id) {
                        }

                        @Service.Singleton
                        final class PokemonMapper implements JdbcClient.RowMapper<Pokemon> {
                            private final MapperDependency dependency;
                            PokemonMapper(MapperDependency dependency) {
                                this.dependency = dependency;
                            }
                            @Override
                            public Pokemon map(JdbcClient.Row row) {
                                return new Pokemon(row.required("id", Long.class), row.optional("name", String.class));
                            }
                        }

                        final class MapperDependency {
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
        assertThat(source, not(containsString("Optional<JdbcClient.RowMapper<Pokemon>>")));
        assertThat(source, containsString("JdbcClient.RowMapper<Pokemon> pokemonRowMapper"));
        assertThat(source, containsString("PokemonMapper pokemonMapper"));
        assertThat(source, containsString("MAPPER_FIND = row -> new Pokemon"));
        assertThat(source, containsString("MAPPER_VISIT_RECORDS = row -> new Pokemon"));
        assertThat(source, containsString("row.optional(\"name\", String.class)"));
        assertThat(source, not(containsString("new PokemonMapper()")));
        assertTrue(source.contains(".bind(1, minimum).map(MAPPER_FIND).list()"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).optional()"), source);
        assertTrue(source.contains("jdbcClient.create(SQL_COUNT).map(long.class).one()"), source);
        assertTrue(source.contains(".bind(1, name).bind(2, id).execute()"), source);
        assertThat(source, containsString("Math.toIntExact(jdbcClient.create(SQL_RENAME_INT)"));
        assertTrue(source.contains(".bind(1, name)"
                                           + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one()"), source);
        assertTrue(source.contains(".generatedKeys(row -> row.required(1, Long.class)).one()"), source);
        assertTrue(source.contains(".map(pokemonMapper).one()"), source);
        assertTrue(source.contains(".bind(1, id).map(pokemonRowMapper).one()"), source);
        assertTrue(source.contains(".generatedKeys(MAPPER_INSERT_RECORD, \"ID\").one()"), source);
        assertTrue(source.contains(".bind(1, minimum).map(MAPPER_VISIT_RECORDS).visitAll(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(pokemonMapper).visitAll(request)"), source);
        assertTrue(source.contains("@Tx.Required"), source);

    }

    @Test
    void rejectsUnmatchedAndPositionalDeclarativeParameters() {
        TestCompiler.Result result = compiler()
                .printDiagnostics(false)
                .addSource("InvalidRepository.java", """
                        package example;

                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface InvalidRepository {
                            @Jdbc.Statement("select NAME from POKEMON where ID = ?")
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
                        import io.helidon.data.jdbc.Jdbc;

                        @Data.Repository
                        interface DefaultRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
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

                        import java.util.List;
                        import java.util.Optional;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        @Data.PersistenceUnit(value = "contacts", required = false)
                        interface TraversalRepository {
                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            String one(long id);

                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            Optional<String> optional(long id);

                            @Jdbc.Statement("select NAME from CONTACT where ID = :id or PARENT_ID = :id and TYPE = :type")
                            List<String> list(long id, String type);

                            @Jdbc.Statement("select NAME from CONTACT where ID >= :id")
                            void visit(JdbcResultRequest.VisitAll<String> request, long id);

                            @Jdbc.Statement("select NAME from CONTACT where ID >= :id")
                            boolean visitUntil(JdbcResultRequest.VisitWhile<String> request, long id);

                            @Jdbc.Statement("delete from CONTACT where ID = :id")
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
        assertTrue(source.contains(".bind(1, id).bind(2, id).bind(3, type)"
                                           + ".map(String.class).list()"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).visitAll(request)"), source);
        assertTrue(source.contains(".bind(1, id).map(String.class).visitWhile(request)"), source);
        assertTrue(source.contains(".bind(1, id).execute();"), source);
    }

    @Test
    void generatesLeadingStatementOptionsBeforeBindings() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("OptionsRepository.java", """
                        package example;

                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        import io.helidon.data.jdbc.JdbcStatementOptions;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface OptionsRepository {
                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            List<String> query(JdbcStatementOptions options, long id);

                            @Jdbc.Statement("update CONTACT set NAME = :name where ID = :id")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            long update(JdbcStatementOptions options, String name, long id);

                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            long insert(JdbcStatementOptions options, String name);

                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            void visit(JdbcResultRequest.VisitAll<String> request, long id);
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/OptionsRepository__Jdbc.java"));
        assertThat(source, containsString("jdbcClient.create(SQL_QUERY).options(options).bind(1, id)"
                                                  + ".map(String.class).list()"));
        assertThat(source, containsString("jdbcClient.create(SQL_UPDATE).options(options).bind(1, name).bind(2, id)"
                                                  + ".execute()"));
        assertThat(source, containsString("jdbcClient.create(SQL_INSERT).options(options).bind(1, name)"
                                                  + ".generatedKeys(row -> row.required(1, Long.class), \"ID\").one()"));
        assertThat(source, containsString("jdbcClient.create(SQL_VISIT).bind(1, id).map(String.class)"
                                                  + ".visitAll(request)"));
    }

    @Test
    void rejectsInvalidStatementOptionsControls() {
        assertCompilationFailure("NonLeadingOptionsRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcStatementOptions;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NonLeadingOptionsRepository {
                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            String invalid(long id, JdbcStatementOptions options);
                        }
                        """,
                                 "JdbcStatementOptions is permitted once and only as the leading invocation control "
                                         + "parameter");
        assertCompilationFailure("DuplicateOptionsRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcStatementOptions;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DuplicateOptionsRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            String invalid(JdbcStatementOptions first, JdbcStatementOptions second);
                        }
                        """,
                                 "JdbcStatementOptions is permitted once and only as the leading invocation control "
                                         + "parameter");
        assertCompilationFailure("OptionsAndRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        import io.helidon.data.jdbc.JdbcStatementOptions;
                        @Data.Repository @Data.Provider("jdbc")
                        interface OptionsAndRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest.VisitAll<String> request,
                                         JdbcStatementOptions options);
                        }
                        """,
                                 "cannot be combined with JdbcResultRequest");
    }

    @Test
    void rejectsAmbiguousStatementsUnsupportedCollectionsAndAbstractMappers() {
        assertCompilationFailure("AmbiguousLongRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface AmbiguousLongRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            long invalid();
                        }
                        """,
                                 "Cannot infer JDBC execution from primitive long return type");
        assertCompilationFailure("AmbiguousIntRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface AmbiguousIntRepository {
                            @Jdbc.Statement("update CONTACT set NAME = 'updated'")
                            int invalid();
                        }
                        """,
                                 "Cannot infer JDBC execution from primitive int return type");
        assertCompilationFailure("MissingStatementRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingStatementRepository {
                            @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
                            String invalid();
                        }
                        """,
                                 "requires @Jdbc.Statement");
        assertCompilationFailure("QueryGeneratedKeysRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface QueryGeneratedKeysRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
                            @Jdbc.GeneratedKeys("ID")
                            long invalid(String name);
                        }
                        """,
                                 "@Jdbc.GeneratedKeys requires UPDATE execution");
        assertCompilationFailure("UpdateResultRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface UpdateResultRepository {
                            @Jdbc.Statement("update CONTACT set NAME = :name")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            String invalid(String name);
                        }
                        """,
                                 "UPDATE execution must return void, primitive int, or primitive long");
        assertCompilationFailure("SetRepository.java", """
                        package example;
                        import java.util.Set;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface SetRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            Set<String> invalid();
                        }
                        """,
                                 "Unsupported JDBC repository return type");
        assertCompilationFailure("MapperRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MapperRepository {
                            @Jdbc.Statement("select NAME as name from CONTACT")
                            @Jdbc.RowMapper(AbstractMapper.class)
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
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface BlankKeyRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.GeneratedKeys(" ")
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
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface TrailingCallbackRepository {
                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            void invalid(long id, Consumer<String> action);
                        }
                        """,
                                 "Traversal callbacks must be supplied through a leading JdbcResultRequest");
        assertCompilationFailure("NonLeadingRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NonLeadingRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT where ID = :id")
                            void invalid(long id, JdbcResultRequest.VisitAll<String> request);
                        }
                        """,
                                 "only as the leading invocation control parameter");
        assertCompilationFailure("DuplicateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DuplicateRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest.VisitAll<String> first,
                                         JdbcResultRequest.VisitAll<String> second);
                        }
                        """,
                                 "permitted once and only as the leading invocation control parameter");
        assertCompilationFailure("VisitAllReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface VisitAllReturnRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            String invalid(JdbcResultRequest.VisitAll<String> request);
                        }
                        """,
                                 "JdbcResultRequest.VisitAll methods must return void");
        assertCompilationFailure("VisitWhileReturnRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface VisitWhileReturnRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest.VisitWhile<String> request);
                        }
                        """,
                                 "JdbcResultRequest.VisitWhile methods must return primitive boolean");
        assertCompilationFailure("RawRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface RawRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest.VisitAll request);
                        }
                        """,
                                 "requires one concrete mapped row type");
        assertCompilationFailure("WildcardRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface WildcardRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest.VisitAll<?> request);
                        }
                        """,
                                 "wildcard row types are not supported");
        assertCompilationFailure("UpdateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface UpdateRequestRepository {
                            @Jdbc.Statement("delete from CONTACT")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            void invalid(JdbcResultRequest.VisitAll<String> request);
                        }
                        """,
                                 "traversal requests are supported only for QUERY execution");
        assertCompilationFailure("RegularUpdateRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface RegularUpdateRequestRepository {
                            @Jdbc.Statement("delete from CONTACT")
                            long invalid(JdbcResultRequest request);
                        }
                        """,
                                 "JdbcResultRequest supports only typed traversal and call requests");
        assertCompilationFailure("GeneratedKeyRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GeneratedKeyRequestRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values ('name')")
                            @Jdbc.GeneratedKeys("ID")
                            void invalid(JdbcResultRequest.VisitAll<Long> request);
                        }
                        """,
                                 "traversal requests are supported only for QUERY execution");
        assertCompilationFailure("MapperRequestMismatchRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MapperRequestMismatchRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            @Jdbc.RowMapper(LongMapper.class)
                            void invalid(JdbcResultRequest.VisitAll<String> request);
                        }
                        final class LongMapper implements JdbcClient.RowMapper<Long> {
                            public LongMapper() { }
                            public Long map(JdbcClient.Row row) { return row.required(1, Long.class); }
                        }
                        """,
                                 "Mapper must implement JdbcClient.RowMapper<java.lang.String>");
        assertCompilationFailure("GraphRequestRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GraphRequestRepository {
                            @Jdbc.Statement("select ID as contactId, CHILD_ID as children.childId from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = {"contactId", "children.childId"})
                            void invalid(JdbcResultRequest.VisitAll<Contact> request);
                        }
                        record Contact(Long contactId, List<Child> children) {
                        }
                        record Child(Long childId) {
                        }
                        """,
                                 "@Jdbc.IdentityReducer cannot use a JDBC query traversal request");
        assertCompilationFailure("VoidRegularRequestRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface VoidRegularRequestRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            void invalid(JdbcResultRequest request);
                        }
                        """,
                                 "JdbcResultRequest supports only typed traversal and call requests");
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
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;

                        @Data.Repository
                        @Data.Provider("jdbc")
                        interface ContactRepository {
                            @Jdbc.Statement(\"""
                                    select c.ID as "contactId", c.NAME as "name",
                                           p.ID as "phones.phoneId", p.NUMBER as "phones.number",
                                           t.ID as "phones.tags.tagId", t.NAME as "phones.tags.name"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    left join TAG t on t.PHONE_ID = p.ID
                                    order by c.ID, p.ID, t.ID
                                    \""")
                            @Jdbc.IdentityReducer(identityPaths = {
                                    "contactId", "phones.phoneId", "phones.tags.tagId"
                            })
                            List<Contact> findContacts();

                            @Jdbc.Statement("select ID as contactId from CONTACT order by ID")
                            @Jdbc.RowReducer(ContactIdsReducer.class)
                            List<Long> reduceContactIds();

                            @Jdbc.Statement("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Jdbc.IdentityReducer(identityPaths = "contactId")
                            ContactSummary findOne();

                            @Jdbc.Statement("select ID as contactId, NAME as name from CONTACT order by ID")
                            @Jdbc.IdentityReducer(identityPaths = "contactId")
                            Optional<ContactSummary> findOptional();

                            @Jdbc.Statement("select ID as contactId, NAME as name from CONTACT order by ID")
                            List<ContactSummary> listContacts();

                            @Jdbc.Statement(\"""
                                    select c.TENANT_ID as "tenantId", c.ID as "contactId",
                                           p.TYPE as "phones.type", p.NUMBER as "phones.number"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    order by c.TENANT_ID, c.ID, p.TYPE, p.NUMBER
                                    \""")
                            @Jdbc.IdentityReducer(identityPaths = {
                                    "tenantId", "contactId", "phones.type", "phones.number"
                            })
                            List<CompositeContact> findCompositeContacts();

                            @Jdbc.Statement(\"""
                                    select c.ID as "contactId",
                                           p.ID as "primaryPhones.phoneId",
                                           s.ID as "secondaryPhones.phoneId"
                                    from CONTACT c
                                    left join PHONE p on p.CONTACT_ID = c.ID
                                    left join PHONE s on s.CONTACT_ID = c.ID
                                    \""")
                            @Jdbc.IdentityReducer(identityPaths = {
                                    "contactId", "primaryPhones.phoneId", "secondaryPhones.phoneId"
                            })
                            List<DualContact> findDualContacts();
                        }

                        record Contact(Long contactId, Optional<String> name, List<Phone> phones) {
                        }

                        record Phone(Long phoneId, String number, List<Tag> tags) {
                        }

                        record Tag(Long tagId, String name) {
                        }

                        record ContactSummary(Long contactId, String name) {
                        }

                        record CompositeContact(Long tenantId,
                                                Long contactId,
                                                List<CompositePhone> phones) {
                        }

                        record CompositePhone(String type, String number) {
                        }

                        record DualContact(Long contactId,
                                           List<PhoneIdentity> primaryPhones,
                                           List<PhoneIdentity> secondaryPhones) {
                        }

                        record PhoneIdentity(Long phoneId) {
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

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/ContactRepository__Jdbc.java"));
        assertThat(source, containsString(".reduce(new Reducer_FindContacts())"));
        assertThat(source, containsString("LinkedHashMap<Long, RootAccumulator> rootsByIdentity"));
        assertThat(source, containsString("LinkedHashMap<Long, PhonesAccumulator> phonesByIdentity"));
        assertThat(source, containsString("row.optional(\"contactId\", Long.class).orElse(null)"));
        assertThat(source, containsString("row.optional(\"phones.phoneId\", Long.class).orElse(null)"));
        assertThat(source, containsString("row.optional(\"name\", String.class)"));
        assertThat(source, containsString("new Contact(contactId, name, List.copyOf(phonesValues))"));
        assertThat(source,
                   containsString("Conflicting projected value for record scope 'phones' property 'number'"));
        assertThat(source,
                   containsString("Record scope 'phones.tags' has an identity while ancestor scope 'phones' is absent"));
        assertThat(source, containsString("private static record RootIdentity"));
        assertThat(source, containsString("private static record PhonesIdentity"));
        assertThat(source, containsString("primaryPhonesByIdentity"));
        assertThat(source, containsString("secondaryPhonesByIdentity"));
        assertThat(source, containsString(".reduce(new ContactIdsReducer())"));
        assertThat(source, containsString(".reduce(new Reducer_FindOne())"));
        assertThat(source, containsString(".reduce(new Reducer_FindOptional())"));
        assertThat(source, not(containsString("Optional<JdbcClient.RowMapper<ContactSummary>>")));
        assertThat(source, containsString("MAPPER_LIST_CONTACTS = row ->"));
        assertThat(source, containsString(".map(MAPPER_LIST_CONTACTS).list()"));
        assertThat(source, not(containsString("java.lang.reflect")));
        assertThat(source, not(containsString("JdbcRunner")));
        assertGeneratedIdentityBehavior(result);
    }

    @Test
    void generatesGenericMapperServiceForGeneratedKeys() throws IOException {
        TestCompiler.Result result = compiler()
                .addSource("GeneratedKeyMapperRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GeneratedKeyMapperRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            @Jdbc.RowMapper
                            Long insert(String name);
                        }
                        """)
                .build()
                .compile();

        assertThat(String.join("\n", result.diagnostics()), result.success(), is(true));
        String source = Files.readString(result.sourceOutput().resolve("example/GeneratedKeyMapperRepository__Jdbc.java"));
        assertThat(source, containsString("JdbcClient.RowMapper<Long> longRowMapper"));
        assertThat(source, containsString(".generatedKeys(longRowMapper, \"ID\").one()"));
    }

    @Test
    void rejectsImplicitGraphsAndInvalidReducerDeclarations() {
        TestCompiler.Result mapperServiceResult = compiler()
                .addSource("ImplicitGraphRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ImplicitGraphRepository {
                            @Jdbc.Statement("select c.ID as contactId, p.ID as \\\"phones.phoneId\\\" from CONTACT c "
                                    + "left join PHONE p on p.CONTACT_ID = c.ID")
                            List<Contact> invalid();
                        }
                        class Contact { }
                        """)
                .build()
                .compile();
        assertThat(String.join("\n", mapperServiceResult.diagnostics()), mapperServiceResult.success(), is(true));
        try {
            String mapperServiceSource = Files.readString(
                    mapperServiceResult.sourceOutput().resolve("example/ImplicitGraphRepository__Jdbc.java"));
            assertThat(mapperServiceSource,
                       containsString("JdbcClient.RowMapper<Contact> contactRowMapper"));
            assertThat(mapperServiceSource, containsString(".map(contactRowMapper).list()"));
        } catch (IOException e) {
            throw new AssertionError("Failed to read generated mapper-service repository", e);
        }
        assertCompilationFailure("MissingIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingIdentityRepository {
                            @Jdbc.Statement("select c.ID as contactId, p.ID as \\\"children.childId\\\" from CONTACT c "
                                    + "left join CHILD p on p.CONTACT_ID = c.ID")
                            @Jdbc.IdentityReducer(identityPaths = "contactId")
                            List<Parent> invalid();
                        }
                        record Parent(Long contactId, List<Child> children) {
                        }
                        record Child(Long childId) {
                        }
                        """,
                                 "requires an identity for record scope 'children'");
        assertCompilationFailure("ReducerResultRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ReducerResultRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            @Jdbc.RowReducer(WrongReducer.class)
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
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ReducerConflictRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            @Jdbc.RowMapper(NameMapper.class)
                            @Jdbc.RowReducer(NameReducer.class)
                            String invalid();
                        }
                        final class NameMapper implements JdbcClient.RowMapper<String> {
                            public NameMapper() { }
                            public String map(JdbcClient.Row row) { return row.required(1, String.class); }
                        }
                        final class NameReducer implements JdbcClient.RowReducer<String> {
                            public NameReducer() { }
                            public void accept(JdbcClient.Row row) { }
                            public String finish() { return ""; }
                        }
                        """,
                                 "@Jdbc.RowReducer cannot be combined");
        assertCompilationFailure("GeneratedKeyReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface GeneratedKeyReducerRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            @Jdbc.RowReducer(KeyReducer.class)
                            Long invalid(String name);
                        }
                        final class KeyReducer implements JdbcClient.RowReducer<Long> {
                            public void accept(JdbcClient.Row row) { }
                            public Long finish() { return 1L; }
                        }
                        """,
                                 "@Jdbc.RowReducer is legal only for QUERY execution");
        assertCompilationFailure("TraversalReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        import io.helidon.data.jdbc.JdbcResultRequest;
                        @Data.Repository @Data.Provider("jdbc")
                        interface TraversalReducerRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            @Jdbc.RowReducer(NameReducer.class)
                            void invalid(JdbcResultRequest.VisitAll<String> request);
                        }
                        final class NameReducer implements JdbcClient.RowReducer<String> {
                            public void accept(JdbcClient.Row row) { }
                            public String finish() { return ""; }
                        }
                        """,
                                 "@Jdbc.RowReducer cannot use a JDBC query traversal request");
        assertCompilationFailure("AbstractReducerRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface AbstractReducerRepository {
                            @Jdbc.Statement("select NAME from CONTACT")
                            @Jdbc.RowReducer(AbstractReducer.class)
                            String invalid();
                        }
                        abstract class AbstractReducer implements JdbcClient.RowReducer<String> {
                            public void accept(JdbcClient.Row row) { }
                        }
                        """,
                                 "Reducer must be a concrete class");
    }

    @Test
    void rejectsInvalidIdentityReducerDeclarations() {
        assertCompilationFailure("EmptyIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface EmptyIdentityRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = {})
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "@Jdbc.IdentityReducer requires at least one identity path");
        assertCompilationFailure("InvalidIdentityPathRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface InvalidIdentityPathRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "children..id")
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "Invalid @Jdbc.IdentityReducer identity path");
        assertCompilationFailure("DuplicateIdentityPathRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface DuplicateIdentityPathRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = {"id", "id"})
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "Duplicate @Jdbc.IdentityReducer identity path: 'id'");
        assertCompilationFailure("MissingRootIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingRootIdentityRepository {
                            @Jdbc.Statement("select ID as id, CHILD_ID as \\\"children.id\\\" from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "children.id")
                            List<Contact> invalid();
                        }
                        record Contact(Long id, List<Child> children) {
                        }
                        record Child(Long id) {
                        }
                        """,
                                 "requires an identity for record scope 'root'");
        assertCompilationFailure("UnresolvedIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface UnresolvedIdentityRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "unknown")
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "Identity path does not resolve to a scalar record component: unknown");
        assertCompilationFailure("MissingAliasRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface MissingAliasRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        record Contact(Long id, String name) {
                        }
                        """,
                                 "SQL projection is missing record component alias: name");
        assertCompilationFailure("ExtraAliasRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ExtraAliasRepository {
                            @Jdbc.Statement("select ID as id, NAME as name from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "SQL projection alias does not match an identity-reduced record component: name");
        assertCompilationFailure("NonRecordRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface NonRecordRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        final class Contact {
                        }
                        """,
                                 "@Jdbc.IdentityReducer requires records at every record scope");
        assertCompilationFailure("UnsupportedCollectionRepository.java", """
                        package example;
                        import java.util.List;
                        import java.util.Set;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface UnsupportedCollectionRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        record Contact(Long id, Set<Child> children) {
                        }
                        record Child(Long id) {
                        }
                        """,
                                 "Identity-reduced record component must be a supported scalar, exact Optional<scalar>, "
                                         + "or exact List<Record>");
        assertCompilationFailure("ArrayIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface ArrayIdentityRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        record Contact(byte[] id) {
                        }
                        """,
                                 "Array record components cannot be identity values: id");
        assertCompilationFailure("OptionalIdentityRepository.java", """
                        package example;
                        import java.util.List;
                        import java.util.Optional;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface OptionalIdentityRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            List<Contact> invalid();
                        }
                        record Contact(Optional<Long> id) {
                        }
                        """,
                                 "Optional record components cannot be identity values: id");
        assertCompilationFailure("IdentityMapperConflictRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        import io.helidon.data.jdbc.JdbcClient;
                        @Data.Repository @Data.Provider("jdbc")
                        interface IdentityMapperConflictRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            @Jdbc.RowMapper(ContactMapper.class)
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        final class ContactMapper implements JdbcClient.RowMapper<Contact> {
                            public Contact map(JdbcClient.Row row) { return new Contact(row.required("id", Long.class)); }
                        }
                        """,
                                 "@Jdbc.RowMapper and @Jdbc.IdentityReducer cannot be combined");
        assertCompilationFailure("IdentityGenericMapperConflictRepository.java", """
                        package example;
                        import java.util.List;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface IdentityGenericMapperConflictRepository {
                            @Jdbc.Statement("select ID as id from CONTACT")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            @Jdbc.RowMapper()
                            List<Contact> invalid();
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "@Jdbc.RowMapper and @Jdbc.IdentityReducer cannot be combined");
        assertCompilationFailure("IdentityUpdateRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface IdentityUpdateRepository {
                            @Jdbc.Statement("update CONTACT set NAME = :name")
                            @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            long invalid(String name);
                        }
                        """,
                                 "Result mapping annotations are not legal on an update-count method");
        assertCompilationFailure("IdentityGeneratedKeysRepository.java", """
                        package example;
                        import io.helidon.data.Data;
                        import io.helidon.data.jdbc.Jdbc;
                        @Data.Repository @Data.Provider("jdbc")
                        interface IdentityGeneratedKeysRepository {
                            @Jdbc.Statement("insert into CONTACT(NAME) values (:name)")
                            @Jdbc.GeneratedKeys("ID")
                            @Jdbc.IdentityReducer(identityPaths = "id")
                            Contact invalid(String name);
                        }
                        record Contact(Long id) {
                        }
                        """,
                                 "@Jdbc.IdentityReducer is legal only for QUERY execution");
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

    private static void assertGeneratedIdentityBehavior(TestCompiler.Result result) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {result.classOutput().toUri().toURL()},
                                                        JdbcMethodGeneratorTest.class.getClassLoader())) {
            Class<?> repositoryType = Class.forName("example.ContactRepository__Jdbc", true, loader);
            Class<?> jdbcClientType = Class.forName("io.helidon.data.jdbc.JdbcClient");
            Class<?> rowType = Class.forName("io.helidon.data.jdbc.JdbcClient$Row");
            var constructor = repositoryType.getDeclaredConstructor(jdbcClientType);
            constructor.setAccessible(true);
            var findContacts = repositoryType.getMethod("findContacts");
            findContacts.setAccessible(true);
            var findOne = repositoryType.getMethod("findOne");
            findOne.setAccessible(true);
            var findOptional = repositoryType.getMethod("findOptional");
            findOptional.setAccessible(true);
            var findCompositeContacts = repositoryType.getMethod("findCompositeContacts");
            findCompositeContacts.setAccessible(true);
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
                    row("contactId", 2L, "name", "Grace", "phones.phoneId", 10L, "phones.number", "333",
                        "phones.tags.tagId", 100L, "phones.tags.name", "work"),
                    row("contactId", 3L, "name", "Lin", "phones.phoneId", null, "phones.number", null,
                        "phones.tags.tagId", null, "phones.tags.name", null))));

            List<?> contacts = (List<?>) findContacts.invoke(repository);
            assertThat(contacts.size(), is(3));
            Object first = contacts.getFirst();
            assertThat(property(first, "contactId"), is(1L));
            List<?> phones = (List<?>) property(first, "phones");
            assertThat(phones.size(), is(2));
            assertThat(property(phones.getFirst(), "phoneId"), is(10L));
            assertThat(property(phones.get(1), "phoneId"), is(11L));
            List<?> tags = (List<?>) property(phones.getFirst(), "tags");
            assertThat(tags.size(), is(2));
            assertThat(property(tags.getFirst(), "tagId"), is(100L));
            assertThat(property(tags.get(1), "tagId"), is(101L));
            List<?> secondRootPhones = (List<?>) property(contacts.get(1), "phones");
            assertThat(secondRootPhones.size(), is(1));
            assertThat(property(secondRootPhones.getFirst(), "phoneId"), is(10L));
            assertThat(((List<?>) property(contacts.get(2), "phones")).isEmpty(), is(true));
            assertThat(reduceContactIds.invoke(repository), is(List.of(1L, 1L, 1L, 1L, 2L, 3L)));

            Object compositeRepository = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("tenantId", 1L, "contactId", 10L, "phones.type", "mobile", "phones.number", "111"),
                    row("tenantId", 1L, "contactId", 10L, "phones.type", "mobile", "phones.number", "111"),
                    row("tenantId", 1L, "contactId", 10L, "phones.type", "work", "phones.number", "222"),
                    row("tenantId", 2L, "contactId", 10L, "phones.type", null, "phones.number", null))));
            List<?> compositeContacts = (List<?>) findCompositeContacts.invoke(compositeRepository);
            assertThat(compositeContacts.size(), is(2));
            assertThat(property(compositeContacts.getFirst(), "tenantId"), is(1L));
            assertThat(property(compositeContacts.getFirst(), "contactId"), is(10L));
            List<?> compositePhones = (List<?>) property(compositeContacts.getFirst(), "phones");
            assertThat(compositePhones.size(), is(2));
            assertThat(property(compositePhones.getFirst(), "type"), is("mobile"));
            assertThat(property(compositePhones.get(1), "type"), is("work"));

            Object oneRepository = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada"))));
            assertThat(property(findOne.invoke(oneRepository), "contactId"), is(1L));
            Optional<?> optional = (Optional<?>) findOptional.invoke(oneRepository);
            assertThat(optional.isPresent(), is(true));
            assertThat(property(optional.orElseThrow(), "name"), is("Ada"));

            Object emptyRepository = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of()));
            assertThat(findOptional.invoke(emptyRepository), is(Optional.empty()));
            assertInvocationCause(findOne, emptyRepository, "io.helidon.data.NoResultException");

            Object multipleRepository = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada"),
                    row("contactId", 2L, "name", "Grace"))));
            assertInvocationCause(findOne, multipleRepository, "io.helidon.data.NonUniqueResultException");
            assertInvocationCause(findOptional, multipleRepository, "io.helidon.data.NonUniqueResultException");

            Object conflicting = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada"),
                    row("contactId", 1L, "name", "Grace"))));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                                                              () -> findContacts.invoke(conflicting));
            Throwable cause = failure.getCause();
            assertThat(cause.getClass().getName(), is("io.helidon.data.DataException"));
            assertThat(cause.getMessage(), containsString("Conflicting projected value"));
            assertThat(cause.getMessage(), not(containsString("Ada")));
            assertThat(cause.getMessage(), not(containsString("Grace")));

            Object orphanedDescendant = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("contactId", 1L, "name", "Ada", "phones.phoneId", null, "phones.number", null,
                        "phones.tags.tagId", 100L, "phones.tags.name", "family"))));
            assertInvocationCause(findContacts, orphanedDescendant, "io.helidon.data.DataException");

            Object partialComposite = constructor.newInstance(reducingClient(jdbcClientType, rowType, List.of(
                    row("tenantId", 1L, "contactId", 10L, "phones.type", "mobile", "phones.number", null))));
            assertInvocationCause(findCompositeContacts, partialComposite, "io.helidon.data.DataException");
        }
    }

    private static void assertInvocationCause(java.lang.reflect.Method method,
                                              Object target,
                                              String expectedType) {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(target));
        assertThat(failure.getCause().getClass().getName(), is(expectedType));
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
                                          return "optional".equals(method.getName())
                                                  ? Optional.ofNullable(value)
                                                  : value;
                                      });
    }

    private static void assertCompilationFailure(String fileName, String source, String expectedDiagnostic) {
        TestCompiler.Result result = compiler()
                .printDiagnostics(false)
                .addSource(fileName, source)
                .build()
                .compile();
        assertThat(String.join("\n", result.diagnostics()), result.success(), is(false));
        assertThat(result.diagnostics(), hasItem(containsString(expectedDiagnostic)));
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing test classpath entry " + className, e);
        }
    }
}
