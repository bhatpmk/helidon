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
package io.helidon.data.jdbc.tests.imperative.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.data.Data;
import io.helidon.data.NonUniqueResultException;
import io.helidon.data.jdbc.JdbcClient;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.data.jdbc.tests.support.TestConfigFactory;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises Connector/J multi-row generated keys through the imperative JDBC client.
 */
@SuppressWarnings("helidon:api:preview")
@Testcontainers(disabledWithoutDocker = true)
class MySqlImperativeMultiRowGeneratedKeysTest {
    @Container
    static final MySQLContainer<?> MYSQL = MySqlImperativeTestSupport.MYSQL;

    private static final String INSERT_TWO = "INSERT INTO CONTACT (NAME) VALUES (?), (?)";

    private ServiceRegistryManager manager;
    private DatabaseFixture database;
    private JdbcClient client;

    @BeforeEach
    void setUpApplication() {
        TestConfigFactory.config(MySqlImperativeTestSupport.config());
        manager = ServiceRegistryManager.start();
        database = manager.registry().get(DatabaseFixture.class);
        database.reset();
        Qualifier provider = Qualifier.builder()
                .typeName(Data.ProviderType.TYPE)
                .value("jdbc")
                .build();
        client = manager.registry().get(JdbcClient.class,
                                        Qualifier.createNamed(Service.Named.DEFAULT_NAME),
                                        provider);
    }

    /**
     * Compares Helidon's key count and order with a direct Connector/J control.
     *
     * @throws Exception if direct JDBC setup fails
     */
    @Test
    void listPreservesDriverMultiRowGeneratedKeys() throws Exception {
        List<Long> direct = directKeys("direct-first", "direct-second");
        List<Long> helidon = keys("helidon-first", "helidon-second")
                .map(row -> row.get(1, Long.class))
                .list();

        assertThat(direct.size(), is(2));
        assertThat(direct.get(1) - direct.get(0), is(1L));
        assertThat(helidon.size(), is(direct.size()));
        assertThat(helidon.get(1) - helidon.get(0), is(direct.get(1) - direct.get(0)));
        assertThat(database.committedByName("helidon-first").isPresent(), is(true));
        assertThat(database.committedByName("helidon-second").isPresent(), is(true));
    }

    /**
     * Verifies singular cardinality reports both committed rows and releases resources.
     */
    @Test
    void oneRejectsMultipleKeysAfterAutoCommitAndRecovers() {
        assertThrows(NonUniqueResultException.class,
                     () -> keys("one-first", "one-second").map(row -> row.get(1, Long.class)).one());

        assertThat(database.committedByName("one-first").isPresent(), is(true));
        assertThat(database.committedByName("one-second").isPresent(), is(true));
        assertThat(insertOne("one-recovery"), is(1));
    }

    /**
     * Verifies optional cardinality reports both committed rows and releases resources.
     */
    @Test
    void optionalRejectsMultipleKeysAfterAutoCommitAndRecovers() {
        assertThrows(NonUniqueResultException.class,
                     () -> keys("optional-first", "optional-second")
                             .map(row -> row.get(1, Long.class))
                             .optional());

        assertThat(database.committedByName("optional-first").isPresent(), is(true));
        assertThat(database.committedByName("optional-second").isPresent(), is(true));
        assertThat(insertOne("optional-recovery"), is(1));
    }

    /**
     * Verifies a mapper failure on the second key escapes unchanged after auto-commit.
     */
    @Test
    void secondKeyMapperFailurePreservesIdentityAndCommittedState() {
        IllegalStateException expected = new IllegalStateException("second generated key failure");
        AtomicInteger invocation = new AtomicInteger();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                                               () -> keys("mapper-first", "mapper-second")
                                                       .map(row -> {
                                                           if (invocation.incrementAndGet() == 2) {
                                                               throw expected;
                                                           }
                                                           return row.get(1, Long.class);
                                                       })
                                                       .list());

        assertThat(database.committedByName("mapper-first").isPresent(), is(true));
        assertThat(database.committedByName("mapper-second").isPresent(), is(true));
        assertThat(insertOne("mapper-recovery"), is(1));
        assertSame(expected, thrown);
    }

    /**
     * Verifies the same second-key mapper failure rolls back a required transaction.
     */
    @Test
    void secondKeyMapperFailureRollsBackRequiredTransaction() {
        IllegalStateException expected = new IllegalStateException("transactional second generated key failure");
        AtomicInteger invocation = new AtomicInteger();

        TxException thrown = assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () ->
                keys("transaction-first", "transaction-second")
                        .map(row -> {
                            if (invocation.incrementAndGet() == 2) {
                                throw expected;
                            }
                            return row.get(1, Long.class);
                        })
                        .list()));

        assertThat(database.committedByName("transaction-first").isEmpty(), is(true));
        assertThat(database.committedByName("transaction-second").isEmpty(), is(true));
        assertThat(insertOne("transaction-recovery"), is(1));
        assertThat(hasCause(thrown, expected), is(true));
    }

    @AfterEach
    void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    private static boolean hasCause(Throwable throwable, Throwable expected) {
        for (Throwable current = throwable;
                current != null && current != current.getCause();
                current = current.getCause()) {
            if (current == expected) {
                return true;
            }
        }
        return false;
    }

    private List<Long> directKeys(String first, String second) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(),
                                                                  MYSQL.getUsername(),
                                                                  MYSQL.getPassword());
                PreparedStatement statement = connection.prepareStatement(INSERT_TWO,
                                                                          Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, first);
            statement.setString(2, second);
            assertThat(statement.executeUpdate(), is(2));
            List<Long> result = new ArrayList<>();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                while (keys.next()) {
                    result.add(keys.getLong(1));
                }
            }
            return List.copyOf(result);
        }
    }

    private JdbcClient.GeneratedKeys keys(String first, String second) {
        return client.create(INSERT_TWO)
                .bind(1, first)
                .bind(2, second)
                .generatedKeys()
                .addColumn("id");
    }

    private int insertOne(String name) {
        return Math.toIntExact(client.create("INSERT INTO CONTACT (NAME) VALUES (?)")
                                       .bind(1, name)
                                       .execute());
    }
}
