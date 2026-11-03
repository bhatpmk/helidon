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
package io.helidon.tests.integration.data.jdbc.declarative.pgsql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.helidon.data.DataException;
import io.helidon.tests.integration.data.jdbc.contract.AbstractDeclarativeScalarContract;
import io.helidon.tests.integration.data.jdbc.support.TestConfigFactory;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Executes the generated scalar and typed-null contract against PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlDeclarativeScalarTest extends AbstractDeclarativeScalarContract {
    @Container
    static final JdbcDatabaseContainer<?> POSTGRESQL = PostgreSqlDeclarativeTestSupport.POSTGRESQL;

    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID OTHER = UUID.fromString("11112233-4455-6677-8899-aabbccddeeff");

    @Override
    protected void beforeStartApplication() {
        TestConfigFactory.config(PostgreSqlDeclarativeTestSupport.config());
    }

    @Test
    void bindsContextualNullBeforeNonNullAndMapsUuidShapes() throws Exception {
        setUpUuidTable();
        UuidRepository uuidRepository = service(UuidRepository.class);
        uuidRepository.insert(null, null);
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ID, RELATED FROM DECLARATIVE_UUID")) {
            assertThat(rows.next(), is(true));
            assertThat(rows.getObject(1, UUID.class), nullValue());
            assertThat(rows.getObject(2, UUID.class), nullValue());
            statement.execute("DELETE FROM DECLARATIVE_UUID");
        }
        assertThat(uuidRepository.repeated(null), is(List.of()));
        assertThat(uuidRepository.optional(null), is(Optional.empty()));
        uuidRepository.insert(ID, OTHER);
        uuidRepository.insert(OTHER, null);
        assertThat(uuidRepository.update(ID, null), is(1L));
        assertThat(uuidRepository.projection(ID), is(new UuidRepository.Projection(ID, Optional.empty())));
        assertThat(uuidRepository.update(ID, OTHER), is(1L));
        assertThat(uuidRepository.repeated(OTHER), is(List.of(ID, OTHER)));
        assertThat(uuidRepository.ids(), is(List.of(ID, OTHER)));
        assertThat(uuidRepository.one(ID), is(ID));
        assertThat(uuidRepository.optional(ID), is(Optional.of(ID)));
        assertThat(uuidRepository.projection(ID), is(new UuidRepository.Projection(ID, Optional.of(OTHER))));
        try (Connection connection = connection(); var statement = connection.prepareStatement(
                "SELECT RELATED FROM DECLARATIVE_UUID WHERE ID = ?")) {
            statement.setObject(1, ID);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next(), is(true));
                assertThat(rows.getObject(1, UUID.class), is(OTHER));
            }
        }
    }

    @Test
    void reportsAmbiguousNullAndRecoversWithoutFallback() throws Exception {
        setUpUuidTable();
        UuidRepository uuidRepository = service(UuidRepository.class);
        DataException failure = assertThrows(DataException.class, () -> uuidRepository.ambiguous(null));
        assertThat(failure.getMessage(), containsString("42P18"));
        assertThat(uuidRepository.castNull(null), is(true));
        uuidRepository.insert(ID, null);
        assertThat(uuidRepository.one(ID), is(ID));
    }

    @Test
    void readsNativeGeneratedKeysThroughEveryShape() throws Exception {
        setUpUuidTable();
        UuidRepository uuidRepository = service(UuidRepository.class);
        UUID key = uuidRepository.key();
        UUID optional = uuidRepository.optionalKey().orElseThrow();
        UUID listed = uuidRepository.keys().getFirst();
        UuidRepository.Projection projection = uuidRepository.keyProjection();
        assertThat(projection.related(), is(Optional.empty()));
        List<UUID> inserted = new ArrayList<>();
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT ID FROM DECLARATIVE_UUID")) {
            while (rows.next()) {
                inserted.add(rows.getObject(1, UUID.class));
            }
        }
        assertThat(inserted.size(), is(4));
        assertThat(Set.copyOf(inserted), is(Set.of(key, optional, listed, projection.id())));
    }

    /**
     * Verifies rollback through an independent connection after generated UUID
     * key conversion fails, and preserves the auto commit insert on failure.
     */
    @Test
    void preservesTransactionOutcomeAfterGeneratedKeyFailure() throws Exception {
        setUpUuidTable();
        UuidRepository uuidRepository = service(UuidRepository.class);
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> uuidRepository.invalidKey(ID)));
        assertThat(committedCount(), is(0L));
        assertThrows(DataException.class, () -> uuidRepository.invalidKey(ID));
        assertThat(committedCount(), is(1L));
        assertThat(uuidRepository.one(ID), is(ID));
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private static long committedCount() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM DECLARATIVE_UUID")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void setUpUuidTable() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            System.out.printf("The PostgreSQL server version is %s and the JDBC driver version is %s.%n",
                              connection.getMetaData().getDatabaseProductVersion(),
                              connection.getMetaData().getDriverVersion());
            statement.execute("DROP TABLE IF EXISTS DECLARATIVE_UUID");
            statement.execute("CREATE TABLE DECLARATIVE_UUID "
                                      + "(ID UUID DEFAULT gen_random_uuid(), RELATED UUID, TITLE VARCHAR(100))");
        }
    }
}
