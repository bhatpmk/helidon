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
package io.helidon.data.jdbc.tests.declarative.mysql;

import java.util.List;

import io.helidon.data.NonUniqueResultException;
import io.helidon.data.jdbc.tests.declarative.MultiRowFailingGeneratedKeyMapper;
import io.helidon.data.jdbc.tests.declarative.repository.ContactRepository;
import io.helidon.data.jdbc.tests.declarative.repository.MultiRowGeneratedKeysRepository;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.data.jdbc.tests.support.TestConfigFactory;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises Connector/J multi-row generated keys through a generated repository.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlDeclarativeMultiRowGeneratedKeysTest {
    @Container
    static final MySQLContainer<?> MYSQL = MySqlDeclarativeTestSupport.MYSQL;

    private ServiceRegistryManager manager;
    private DatabaseFixture database;
    private MultiRowGeneratedKeysRepository repository;
    private MultiRowFailingGeneratedKeyMapper mapper;
    private ContactRepository contacts;

    @BeforeEach
    void setUpApplication() {
        TestConfigFactory.config(MySqlDeclarativeTestSupport.config());
        manager = ServiceRegistryManager.start();
        database = manager.registry().get(DatabaseFixture.class);
        database.reset();
        repository = manager.registry().get(MultiRowGeneratedKeysRepository.class);
        mapper = manager.registry().get(MultiRowFailingGeneratedKeyMapper.class);
        contacts = manager.registry().get(ContactRepository.class);
    }

    /**
     * Verifies a generated repository preserves Connector/J key count and order.
     */
    @Test
    void listPreservesMultiRowGeneratedKeyOrder() {
        List<Long> keys = repository.insertList("list-first", "list-second");

        assertThat(keys.size(), is(2));
        assertThat(keys.get(1) - keys.get(0), is(1L));
        assertThat(database.committedByName("list-first").isPresent(), is(true));
        assertThat(database.committedByName("list-second").isPresent(), is(true));
    }

    /**
     * Verifies generated singular cardinality rejects multiple committed keys and recovers.
     */
    @Test
    void oneRejectsMultipleKeysAfterAutoCommitAndRecovers() {
        assertThrows(NonUniqueResultException.class,
                     () -> repository.insertOne("one-first", "one-second"));

        assertThat(database.committedByName("one-first").isPresent(), is(true));
        assertThat(database.committedByName("one-second").isPresent(), is(true));
        assertThat(contacts.insert("one-recovery"), greaterThan(0L));
    }

    /**
     * Verifies generated optional cardinality rejects multiple committed keys and recovers.
     */
    @Test
    void optionalRejectsMultipleKeysAfterAutoCommitAndRecovers() {
        assertThrows(NonUniqueResultException.class,
                     () -> repository.insertOptional("optional-first", "optional-second"));

        assertThat(database.committedByName("optional-first").isPresent(), is(true));
        assertThat(database.committedByName("optional-second").isPresent(), is(true));
        assertThat(contacts.insert("optional-recovery"), greaterThan(0L));
    }

    /**
     * Verifies generated mapping preserves exact second-row failure identity after auto-commit.
     */
    @Test
    void secondKeyMapperFailurePreservesIdentityAndCommittedState() {
        mapper.arm();
        RuntimeException expected = mapper.failure();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                                               () -> repository.insertWithMapperFailure(
                                                       "mapper-first",
                                                       "mapper-second"));

        assertThat(database.committedByName("mapper-first").isPresent(), is(true));
        assertThat(database.committedByName("mapper-second").isPresent(), is(true));
        assertThat(contacts.insert("mapper-recovery"), greaterThan(0L));
        assertSame(expected, thrown);
    }

    /**
     * Verifies generated mapping failure rolls back both rows in a required transaction.
     */
    @Test
    void secondKeyMapperFailureRollsBackRequiredTransaction() {
        mapper.arm();
        RuntimeException expected = mapper.failure();

        TxException thrown = assertThrows(TxException.class,
                                          () -> repository.insertWithTransactionalMapperFailure(
                                                  "transaction-first",
                                                  "transaction-second"));

        assertThat(database.committedByName("transaction-first").isEmpty(), is(true));
        assertThat(database.committedByName("transaction-second").isEmpty(), is(true));
        assertThat(contacts.insert("transaction-recovery"), greaterThan(0L));
        assertSame(expected, thrown.getCause());
    }

    @AfterEach
    void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
    }
}
