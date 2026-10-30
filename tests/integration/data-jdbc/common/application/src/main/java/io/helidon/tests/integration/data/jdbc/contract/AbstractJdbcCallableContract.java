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
package io.helidon.tests.integration.data.jdbc.contract;

import java.sql.JDBCType;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;
import io.helidon.data.jdbc.JdbcClient;
import io.helidon.data.jdbc.GeneratedJdbcData;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.tests.integration.data.jdbc.declarative.repository.CallableRoutineRepository;
import io.helidon.tests.integration.data.jdbc.support.TestConfigFactory;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public abstract class AbstractJdbcCallableContract {
    private ServiceRegistryManager manager;
    private JdbcClient client;

    protected abstract Config configuration();

    protected abstract String vendor();

    protected abstract boolean generated();

    @BeforeEach
    protected void start() throws Exception {
        Config config = configuration();
        Map<String, String> values = new HashMap<>(config.asMap().get());
        if (vendor().equals("pgsql")) {
            values.compute("data.clients.jdbc.0.connection.url", (_, url) -> url + "?escapeSyntaxCallMode=callIfNoReturn");
        }
        TestConfigFactory.config(Config.just(ConfigSources.create(values)));
        manager = ServiceRegistryManager.start();
        client = manager.registry().get(JdbcClient.class);
        Config connectionConfig = Config.just(ConfigSources.create(values)).get("data.clients.jdbc.0.connection");
        try (Connection connection = DriverManager.getConnection(connectionConfig.get("url").asString().get(),
                connectionConfig.get("username").asString().get(), connectionConfig.get("password").asString().get())) {
            DatabaseMetaData metadata = connection.getMetaData();
            System.out.println("Callable capability fixture: " + metadata.getDatabaseProductName() + " "
                    + metadata.getDatabaseProductVersion() + ", driver " + metadata.getDriverName() + " "
                    + metadata.getDriverVersion() + ", explicit transaction cursors, escapeSyntaxCallMode="
                    + (vendor().equals("pgsql") ? "callIfNoReturn" : "driver default"));
        }
        client.create("DELETE FROM TX_MATRIX WHERE DATA_VALUE <> ?").bind(1, "baseline").execute();
        switch (vendor()) {
        case "oracle" -> {
            client.create("CREATE OR REPLACE FUNCTION sp_add(p_input IN INTEGER) RETURN INTEGER AS BEGIN "
                                  + "RETURN p_input + 1; END;").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_values(p_input IN INTEGER, value IN OUT INTEGER, "
                                  + "other IN OUT VARCHAR2) AS BEGIN value := p_input + 1; other := 'done'; END;").execute();
            client.create("CREATE OR REPLACE FUNCTION sp_cursor(p_input IN INTEGER) RETURN SYS_REFCURSOR AS "
                                  + "c SYS_REFCURSOR; BEGIN IF p_input < 0 THEN RETURN NULL; END IF; "
                                  + "OPEN c FOR SELECT 42 FROM dual WHERE p_input > 0 UNION ALL SELECT 43 FROM dual WHERE p_input > 1 "
                                  + "ORDER BY 1; RETURN c; END;").execute();
        }
        case "pgsql" -> {
            client.create("CREATE OR REPLACE FUNCTION sp_add(p_input INTEGER) RETURNS INTEGER "
                                  + "LANGUAGE SQL AS $$ SELECT p_input + 1 $$").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_values(IN p_input INTEGER, INOUT value INTEGER, "
                                  + "INOUT other VARCHAR) LANGUAGE plpgsql AS $$ BEGIN value := p_input + 1; "
                                  + "other := 'done'; END $$").execute();
            client.create("CREATE OR REPLACE FUNCTION sp_cursor(p_input INTEGER) RETURNS refcursor "
                                  + "LANGUAGE plpgsql AS $$ DECLARE c refcursor; BEGIN IF p_input < 0 THEN RETURN NULL; "
                                  + "END IF; OPEN c FOR SELECT 42 WHERE p_input > 0 UNION ALL SELECT 43 WHERE p_input > 1 "
                                  + "ORDER BY 1; RETURN c; END $$").execute();
        }
        case "mysql" -> {
            client.create("DROP FUNCTION IF EXISTS sp_add").execute();
            client.create("CREATE FUNCTION sp_add(p_input INTEGER) RETURNS INTEGER DETERMINISTIC RETURN p_input + 1").execute();
            client.create("DROP PROCEDURE IF EXISTS sp_values").execute();
            client.create("CREATE PROCEDURE sp_values(IN p_input INTEGER, INOUT value INTEGER, OUT other VARCHAR(20)) "
                                  + "BEGIN SET value = p_input + 1; SET other = 'done'; END").execute();
            client.create("DROP PROCEDURE IF EXISTS sp_direct").execute();
            client.create("CREATE PROCEDURE sp_direct() BEGIN SELECT 42; SELECT 43; END").execute();
        }
        default -> throw new IllegalArgumentException("The test vendor is unknown.");
        }
        switch (vendor()) {
        case "oracle" -> {
            client.create("CREATE OR REPLACE PROCEDURE sp_noop AS BEGIN NULL; END;").execute();
            client.create("CREATE OR REPLACE FUNCTION sp_stamp RETURN TIMESTAMP AS BEGIN "
                                  + "RETURN TIMESTAMP '2026-10-28 12:13:14.123456'; END;").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_bundle(p_input IN INTEGER, value OUT INTEGER, "
                                  + "p_rows OUT SYS_REFCURSOR) AS BEGIN value := p_input + 1; "
                                  + "IF p_input < 0 THEN p_rows := NULL; ELSE OPEN p_rows FOR SELECT 42 AS ID FROM dual "
                                  + "WHERE p_input > 0; END IF; END;").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_inout(p_input IN INTEGER, p_value IN OUT INTEGER) "
                                  + "AS BEGIN p_value := p_value + p_input; END;").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_ddl AUTHID CURRENT_USER AS BEGIN EXECUTE IMMEDIATE "
                                  + "'CREATE TABLE SP_DDL_PROBE (ID INTEGER)'; END;").execute();
        }
        case "pgsql" -> {
            client.create("CREATE OR REPLACE PROCEDURE sp_noop() LANGUAGE plpgsql AS $$ BEGIN NULL; END $$").execute();
            client.create("CREATE OR REPLACE FUNCTION sp_stamp() RETURNS TIMESTAMP LANGUAGE SQL "
                                  + "AS $$ SELECT TIMESTAMP '2026-10-28 12:13:14.123456' $$").execute();
            client.create("CREATE OR REPLACE FUNCTION sp_time() RETURNS TIME LANGUAGE SQL "
                                  + "AS $$ SELECT TIME '12:13:14.123456' $$").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_inout(IN p_input INTEGER, INOUT p_value INTEGER) "
                                  + "LANGUAGE plpgsql AS $$ BEGIN p_value := p_value + p_input; END $$").execute();
            client.create("CREATE OR REPLACE PROCEDURE sp_ddl() LANGUAGE plpgsql AS $$ BEGIN COMMIT; END $$").execute();
        }
        case "mysql" -> {
            client.create("DROP FUNCTION IF EXISTS sp_stamp").execute();
            client.create("CREATE FUNCTION sp_stamp() RETURNS DATETIME(6) DETERMINISTIC "
                                  + "RETURN '2026-10-28 12:13:14.123456'").execute();
            client.create("DROP FUNCTION IF EXISTS sp_time").execute();
            client.create("CREATE FUNCTION sp_time() RETURNS TIME(6) DETERMINISTIC RETURN '12:13:14.123456'").execute();
            client.create("DROP PROCEDURE IF EXISTS sp_noop").execute();
            client.create("CREATE PROCEDURE sp_noop() BEGIN END").execute();
            client.create("DROP PROCEDURE IF EXISTS sp_inout").execute();
            client.create("CREATE PROCEDURE sp_inout(IN p_input INTEGER, INOUT p_value INTEGER) "
                                  + "BEGIN SET p_value = p_value + p_input; END").execute();
            client.create("DROP PROCEDURE IF EXISTS sp_ddl").execute();
            client.create("CREATE PROCEDURE sp_ddl() BEGIN CREATE TABLE SP_DDL_PROBE (ID INTEGER); END").execute();
        }
        default -> throw new IllegalArgumentException("The test vendor is unknown.");
        }

    }

    @AfterEach
    protected void stop() {
        if (manager != null) {
            manager.shutdown();
        }
        TestConfigFactory.reset();
    }

    @Test
    protected void scalarReturnAndNullableInput() {
        if (generated()) {
            CallableRoutineRepository repository = manager.registry().get(CallableRoutineRepository.class);
            assertThat(repository.scalar(41), is(42));
            assertThat(repository.nullable(null), is(Optional.empty()));
            assertThat(repository.mapped(41, result -> {
                result.skipRemainingResults();
                return result.get("return", Integer.class).toString();
            }), is("42"));
        } else {
            JdbcClient.OutParameters outputs = client.create("{? = call sp_add(?)}").bind(2, 41).call()
                    .out("return", 1, JDBCType.INTEGER).outputs();
            assertThat(outputs.get("return", Integer.class), is(42));
        }
    }

    @Test
    protected void scalarOutputsAndSnapshot() {
        if (generated()) {
            CallableRoutineRepository repository = manager.registry().get(CallableRoutineRepository.class);
            assertThat(repository.aggregate(41, null, null), is(new CallableRoutineRepository.Values(42, "done")));
            assertThat(repository.recordBundle_OUTPUT_0("baseline"),
                       is(new CallableRoutineRepository.QueryProjection("baseline")));
            assertThat(repository.snapshot(41, null, null).get("value", Integer.class), is(42));
            repository.consume(41, null, null, result -> {
                result.skipRemainingResults();
                assertThat(result.get("other", String.class), is("done"));
            });
        } else {
            JdbcClient.OutParameters result = client.create("{call sp_values(?, ?, ?)}").bind(1, 41).bind(2, 0).bind(3, "unused").call()
                    .out("value", 2, JDBCType.INTEGER).out("other", 3, JDBCType.VARCHAR).outputs();
            assertThat(result.get("value", Integer.class), is(42));
            assertThat(result.get("other", String.class), is("done"));
        }
    }

    /**
     * Detached timestamps retain fractional precision. Unsupported callable TIME conversions remain explicit driver failures.
     */
    @Test
    protected void detachedTemporalPrecisionAndDriverLimits() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 28, 12, 13, 14, 123456000);
        assertThat(client.create("{? = call sp_stamp()}").call().out("return", 1, JDBCType.TIMESTAMP)
                           .outputs().get("return", LocalDateTime.class), is(expected));
        if (vendor().equals("pgsql")) {
            DataException failure = assertThrows(DataException.class, () -> client.create("{? = call sp_time()}").call()
                    .out("return", 1, JDBCType.TIME).outputs());
            assertThat(failure.getMessage(), containsString("22023"));
        } else if (vendor().equals("mysql")) {
            assertThat(client.create("{? = call sp_time()}").call().out("return", 1, JDBCType.TIME)
                               .outputs().get("return", LocalTime.class), is(expected.toLocalTime()));
        }
        assertThat(client.create("{? = call sp_add(?)}").bind(2, 41).call().out("return", 1, JDBCType.INTEGER)
                           .outputs().get("return", Integer.class), is(42));
    }

    @Test
    protected void inoutAndRegistration() {
        if (generated()) {
            assertThat(manager.registry().get(CallableRoutineRepository.class).inout(2, 40), is(42));
        } else {
            assertThat(client.create("{call sp_inout(?, ?)}").bind(1, 2).bind(2, 40).call()
                               .out("value", 2, JDBCType.INTEGER).outputs().get("value", Integer.class), is(42));
        }
    }

    /**
     * MySQL direct results remain separate events and later events are discarded by automatic mapping.
     */
    @Test
    protected void directResultsOrMissingDirectResultDiagnostic() {
        if (vendor().equals("mysql")) {
            if (generated()) {
                assertThat(manager.registry().get(CallableRoutineRepository.class).direct(), is(42));
            } else {
                client.create("{call sp_direct()}").call().consume(result -> {
                    assertThat(result.rows(row -> row.get(1, Integer.class)).one(), is(42));
                    result.next();
                    assertThat(result.rows(row -> row.get(1, Integer.class)).one(), is(43));
                    result.skipRemainingResults();
                    assertThat(result.resultKind(), is(JdbcClient.CallResultKind.END));
                });
            }
        } else if (generated()) {
            assertThrows(DataException.class, () -> manager.registry().get(CallableRoutineRepository.class).noDirect());
        } else {
            client.create("{? = call sp_add(?)}").bind(2, 41).call().out("return", 1, JDBCType.INTEGER)
                    .consume(result -> {
                        result.skipRemainingResults();
                        assertThrows(IllegalStateException.class, () -> result.rows(row -> row.get(1, Integer.class)));
                    });
        }
    }

    /**
     * Implicit routine commits cannot be undone by rollback and are outside the supported local transaction contract.
     */
    @Test
    protected void transactionControllingRoutinesRemainUnsupported() {
        AtomicBoolean routineCompleted = new AtomicBoolean();
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            client.create("INSERT INTO TX_MATRIX (DATA_VALUE) VALUES (?)").bind(1, "implicit-commit").execute();
            client.create("{call sp_ddl()}").call().execute();
            routineCompleted.set(true);
            throw new IllegalArgumentException("The test requests rollback after the unsupported routine.");
        }));
        assertThat(routineCompleted.get(), is(!vendor().equals("pgsql")));
        // This operation obtains an independent connection after the transaction boundary completes.
        assertThat(client.create("SELECT COUNT(*) FROM TX_MATRIX WHERE DATA_VALUE = ?").bind(1, "implicit-commit")
                           .map(Integer.class).one(), is(vendor().equals("pgsql") ? 0 : 1));
        if (!vendor().equals("pgsql")) {
            client.create("DROP TABLE SP_DDL_PROBE").execute();
        }
    }

    /**
     * Cursor tests use an explicit local transaction. MySQL must reject REF_CURSOR without substitution.
     */
    @Test
    protected void cursorPresenceAndMaterialization() {
        if (vendor().equals("mysql")) {
            assertThrows(DataException.class, () -> client.create("{? = call sp_add(?)}").bind(2, 41).call()
                    .out("return", 1, JDBCType.REF_CURSOR).consume(result -> {
                        result.skipRemainingResults();
                        result.cursor("return", row -> row.get(1, Integer.class)).list();
                    }));
            return;
        }
        Tx.transaction(Tx.Type.REQUIRED, () -> {
            if (generated()) {
                CallableRoutineRepository repository = manager.registry().get(CallableRoutineRepository.class);
                assertThat(repository.cursor(1), is(List.of(42)));
                assertThat(repository.cursor(0), is(List.of()));
                assertThat(repository.cursor(2), is(List.of(42, 43)));
                assertThat(repository.cursorOne(1), is(42));
                assertThat(repository.cursorOptional(0), is(Optional.empty()));
                assertThat(repository.cursorOptional(1), is(Optional.of(42)));
                assertThat(repository.optionalCursor(-1), is(Optional.empty()));
                assertThat(repository.optionalCursor(0), is(Optional.of(List.of())));
                if (vendor().equals("oracle")) {
                    assertThat(repository.recordBundle(41),
                               is(new CallableRoutineRepository.RecordBundle(
                                       List.of(new CallableRoutineRepository.Projection(42)), 42)));
                    assertThat(repository.aggregateCursor(41), is(new CallableRoutineRepository.Bundle(List.of(42), 42)));
                    assertThat(repository.aggregateOptionalCursor(-1),
                               is(new CallableRoutineRepository.OptionalBundle(Optional.empty(), 0)));
                    assertThat(repository.aggregateOptionalCursor(0),
                               is(new CallableRoutineRepository.OptionalBundle(Optional.of(List.of()), 1)));
                    assertThat(repository.aggregateOptionalCursor(41),
                               is(new CallableRoutineRepository.OptionalBundle(Optional.of(List.of(42)), 42)));
                }
            } else {
                for (int input : new int[] {1, 0, -1}) {
                    client.create("{? = call sp_cursor(?)}").bind(2, input).call()
                            .out("return", 1, JDBCType.REF_CURSOR).consume(result -> {
                                result.skipRemainingResults();
                                Optional<JdbcClient.Rows<Integer>> cursor =
                                        result.optionalCursor("return", row -> row.get(1, Integer.class));
                                result.next();
                                result.skipRemainingResults();
                                if (input < 0) {
                                    assertThat(cursor, is(Optional.empty()));
                                } else {
                                    assertThat(cursor.orElseThrow().list(), is(input == 0 ? List.of() : List.of(42)));
                                }
                            });
                }
            }
            return null;
        });
    }

    /**
     * The first mapper error survives a later callback error and forces rollback even when the terminal is caught.
     */
    @Test
    protected void retainsFirstErrorAndReleasesTransactionReservation() {
        Error first = new AssertionError("The callable row mapper failed.");
        Error later = new AssertionError("The callable callback failed later.");
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            client.create("INSERT INTO TX_MATRIX (DATA_VALUE) VALUES (?)").bind(1, "call-error").execute();
            JdbcClient.Call call;
            if (vendor().equals("mysql")) {
                call = client.create("{call sp_direct()}").call();
            } else {
                call = client.create("{? = call sp_cursor(?)}").bind(2, 1).call()
                        .out("return", 1, JDBCType.REF_CURSOR);
            }
            Error observed = assertThrows(Error.class, () -> call.consume(result -> {
                Objects.requireNonNull(result, "The callable result must not be null.");
                JdbcClient.RowMapper<Integer> mapper = row -> {
                    Objects.requireNonNull(row, "The current row must not be null.");
                    row.get(1, Integer.class);
                    throw first;
                };
                JdbcClient.Rows<Integer> rows;
                if (vendor().equals("mysql")) {
                    rows = result.rows(mapper);
                } else {
                    result.skipRemainingResults();
                    rows = result.cursor("return", mapper);
                }
                assertThat(assertThrows(Error.class, rows::list), sameInstance(first));
                throw later;
            }));
            assertThat(observed, sameInstance(first));
            assertThat(observed.getSuppressed().length, is(1));
            assertThat(observed.getSuppressed()[0], sameInstance(later));
            // The terminal has released its reservation while the transaction remains marked for rollback.
            assertThat(client.create("SELECT COUNT(*) FROM TX_MATRIX WHERE DATA_VALUE = ?").bind(1, "call-error")
                               .map(Integer.class).one(), is(1));
            return null;
        }));
        assertThat(client.create("SELECT COUNT(*) FROM TX_MATRIX WHERE DATA_VALUE = ?").bind(1, "call-error")
                           .map(Integer.class).one(), is(0));
    }

    /**
     * Required cursor cardinality and NULL contracts fail through transaction completion without retaining a lease.
     */
    @Test
    protected void cursorCardinalityFailures() {
        if (vendor().equals("mysql")) {
            assertThrows(DataException.class, () -> client.create("{? = call sp_add(?)}").bind(2, 1).call()
                    .out("return", 1, JDBCType.REF_CURSOR).consume(result -> {
                        result.skipRemainingResults();
                        result.cursor("return", row -> row.get(1, Integer.class)).one();
                    }));
            return;
        }
        for (int input : new int[] {-1, 0, 2}) {
            assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
                if (generated()) {
                    manager.registry().get(CallableRoutineRepository.class).cursorOne(input);
                } else {
                    client.create("{? = call sp_cursor(?)}").bind(2, input).call()
                            .out("return", 1, JDBCType.REF_CURSOR).consume(result -> {
                                result.skipRemainingResults();
                                if (input < 0) {
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> result.cursor("return", row -> row.get(1, Integer.class)).one());
                                } else if (input == 0) {
                                    assertThrows(NoResultException.class,
                                                 () -> result.cursor("return", row -> row.get(1, Integer.class)).one());
                                } else {
                                    assertThrows(NonUniqueResultException.class,
                                                 () -> result.cursor("return", row -> row.get(1, Integer.class)).one());
                                }
                            });
                }
                return null;
            }));
        }
    }

    /**
     * A callback keeps the transaction connection reserved even at END and rejects suspension before association changes.
     */
    @Test
    protected void connectionReservationAndRelease() {
        Tx.transaction(Tx.Type.REQUIRED, () -> {
            client.create("{? = call sp_add(?)}").bind(2, 41).call().out("return", 1, JDBCType.INTEGER)
                    .consume(result -> {
                        result.skipRemainingResults();
                        assertThat(result.get("return", Integer.class), is(42));
                        assertThrows(IllegalStateException.class, () -> client.create("SELECT 1").map(Integer.class).one());
                        assertThrows(IllegalStateException.class, () -> Tx.transaction(Tx.Type.NEW, () -> 1));
                        assertThrows(IllegalStateException.class, () -> Tx.transaction(Tx.Type.UNSUPPORTED, () -> 1));
                    });
            assertThat(client.create("{? = call sp_add(?)}").bind(2, 41).call()
                               .out("return", 1, JDBCType.INTEGER).outputs().get("return", Integer.class), is(42));
            return null;
        });
    }

    /**
     * Catching a required output failure cannot make the terminal or its transaction successful.
     */
    @Test
    protected void caughtRequiredNullStillFailsTerminalAndTransaction() {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            client.create("INSERT INTO TX_MATRIX (DATA_VALUE) VALUES (?)").bind(1, "call-latched").execute();
            JdbcClient.Statement stage = client.create("{? = call sp_add(?)}");
            GeneratedJdbcData.bindNull(stage, 2, JDBCType.INTEGER);
            assertThrows(IllegalArgumentException.class, () -> stage.call().out("return", 1, JDBCType.INTEGER)
                    .consume(result -> {
                        result.skipRemainingResults();
                        assertThrows(IllegalArgumentException.class, () -> result.get("return", Integer.class));
                    }));
            return null;
        }));
        assertThat(client.create("SELECT COUNT(*) FROM TX_MATRIX WHERE DATA_VALUE = ?").bind(1, "call-latched")
                           .map(Integer.class).one(), is(0));
    }

}
