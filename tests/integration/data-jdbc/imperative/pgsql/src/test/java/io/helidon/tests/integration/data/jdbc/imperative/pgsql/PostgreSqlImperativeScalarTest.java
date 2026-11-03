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
package io.helidon.tests.integration.data.jdbc.imperative.pgsql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.helidon.data.DataException;
import io.helidon.data.jdbc.JdbcClient;
import io.helidon.tests.integration.data.jdbc.contract.AbstractJdbcScalarContract;
import io.helidon.tests.integration.data.jdbc.support.TestConfigFactory;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.Test;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Executes the imperative scalar contract against PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlImperativeScalarTest extends AbstractJdbcScalarContract {
    @Container
    static final JdbcDatabaseContainer<?> POSTGRESQL = PostgreSqlImperativeTestSupport.POSTGRESQL;

    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Override
    protected void beforeStartApplication() {
        TestConfigFactory.config(PostgreSqlImperativeTestSupport.config());
    }

    /**
     * Verifies UUID values and the selected wire format on fresh connections,
     * without relying on result metadata from a previously prepared query.
     */
    @Test
    void roundTripsAllBitsInTextAndBinaryModes() throws Exception {
        setUpUuidTable();
        List<UUID> values = List.of(new UUID(0, 0), ID, new UUID(-1, -1));
        for (boolean binary : List.of(false, true)) {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(POSTGRESQL.getJdbcUrl());
            source.setUser(POSTGRESQL.getUsername());
            source.setPassword(POSTGRESQL.getPassword());
            // Force an advance describe so the first query can return UUID values in binary format.
            source.setPrepareThreshold(binary ? -1 : 1);
            source.setBinaryTransfer(binary);
            source.setBinaryTransferEnable(binary ? "uuid" : "");
            JdbcClient direct = JdbcClient.builder().dataSource(source).build();
            direct.create("DELETE FROM IMPERATIVE_UUID").execute();
            for (UUID id : values) {
                assertThat(direct.create("INSERT INTO IMPERATIVE_UUID (ID) VALUES (?)").bind(1, id).execute(), is(1L));
                for (int repeat = 0; repeat < 3; repeat++) {
                    assertThat(direct.create("SELECT ID FROM IMPERATIVE_UUID WHERE ID = ?")
                                       .bind(1, id).map(UUID.class).one(), is(id));
                }
            }
            assertThat(direct.create("SELECT ID FROM IMPERATIVE_UUID ORDER BY ID").map(UUID.class).list(), is(values));
            assertThat(direct.create("SELECT ID FROM IMPERATIVE_UUID WHERE ID = ?").bind(1, ID)
                               .map(row -> List.of(row.get(1, UUID.class), row.get("id", UUID.class),
                                                   row.optional(1, UUID.class).orElseThrow(),
                                                   row.optional("id", UUID.class).orElseThrow())).one(),
                       is(List.of(ID, ID, ID, ID)));
            try (Connection connection = source.getConnection();
                 var statement = connection.prepareStatement("SELECT ID FROM IMPERATIVE_UUID ORDER BY ID");
                 var rows = statement.executeQuery()) {
                assertThat("The UUID column must use the selected transfer format.",
                           rows.getMetaData().unwrap(PGResultSetMetaData.class).getFormat(1), is(binary ? 1 : 0));
                for (UUID id : values) {
                    assertThat(rows.next(), is(true));
                    assertThat(rows.getObject(1, UUID.class), is(id));
                }
                assertThat(rows.next(), is(false));
            }
        }
    }

    @Test
    void readsDefaultAndNamedGeneratedUuidKeys() throws Exception {
        setUpUuidTable();
        UUID first = client().create("INSERT INTO IMPERATIVE_UUID (TITLE) VALUES ('first')")
                .generatedKeys().map(row -> row.get("id", UUID.class)).one();
        UUID second = client().create("INSERT INTO IMPERATIVE_UUID (TITLE) VALUES ('second')")
                .generatedKeys().addColumn("id").map(row -> row.get(1, UUID.class)).one();
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ID FROM IMPERATIVE_UUID ORDER BY TITLE")) {
            assertThat(rows.next(), is(true));
            assertThat(rows.getObject(1, UUID.class), is(first));
            assertThat(rows.next(), is(true));
            assertThat(rows.getObject(1, UUID.class), is(second));
        }
    }

    /**
     * Observes committed state independently after a UUID key conversion fails
     * following an insert, both inside and outside a local transaction.
     */
    @Test
    void preservesTransactionOutcomeAfterKeyConversionFailure() throws Exception {
        setUpUuidTable();
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            client().create("INSERT INTO IMPERATIVE_UUID (ID, TITLE) VALUES (?, 'invalid UUID')").bind(1, ID)
                    .generatedKeys().map(row -> row.get("title", UUID.class)).one();
            return null;
        }));
        assertThat(committedCount(), is(0L));
        assertThrows(DataException.class, () -> client().create(
                "INSERT INTO IMPERATIVE_UUID (ID, TITLE) VALUES (?, 'invalid UUID')").bind(1, ID)
                .generatedKeys().map(row -> row.get("title", UUID.class)).one());
        assertThat(committedCount(), is(1L));
        assertThat(client().create("SELECT ID FROM IMPERATIVE_UUID").map(UUID.class).optional(), is(Optional.of(ID)));
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private static long committedCount() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM IMPERATIVE_UUID")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void setUpUuidTable() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            System.out.printf("The PostgreSQL server version is %s and the JDBC driver version is %s.%n",
                              connection.getMetaData().getDatabaseProductVersion(),
                              connection.getMetaData().getDriverVersion());
            statement.execute("DROP TABLE IF EXISTS IMPERATIVE_UUID");
            statement.execute("CREATE TABLE IMPERATIVE_UUID (ID UUID DEFAULT gen_random_uuid(), TITLE VARCHAR(100))");
        }
    }
}
