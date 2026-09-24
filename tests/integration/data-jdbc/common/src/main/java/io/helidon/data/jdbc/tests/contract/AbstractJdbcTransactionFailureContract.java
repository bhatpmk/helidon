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
package io.helidon.data.jdbc.tests.contract;

import java.util.List;
import io.helidon.data.jdbc.tests.declarative.DeclarativeGeneratedKeyOperations;
import io.helidon.data.jdbc.tests.declarative.repository.ContactRepository;
import io.helidon.data.jdbc.tests.declarative.repository.FocusedTransactionRepository;
import io.helidon.data.jdbc.tests.declarative.repository.TransactionFailureRepository;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Portable generated-repository transaction failure tests for real databases.
 */
public abstract class AbstractJdbcTransactionFailureContract {
    private ServiceRegistryManager manager;
    private DatabaseFixture database;
    private ContactRepository contacts;
    private FocusedTransactionRepository focusedTransactions;
    private TransactionFailureRepository failures;
    private RuntimeException mapperFailure;

    /**
     * Publishes database-specific configuration before the registry starts.
     */
    protected abstract void beforeStartApplication();

    @BeforeEach
    protected final void setUpApplication() {
        beforeStartApplication();
        manager = ServiceRegistryManager.start();
        database = manager.registry().get(DatabaseFixture.class);
        contacts = manager.registry().get(ContactRepository.class);
        focusedTransactions = manager.registry().get(FocusedTransactionRepository.class);
        failures = manager.registry().get(TransactionFailureRepository.class);
        mapperFailure = manager.registry().get(DeclarativeGeneratedKeyOperations.class).generatedKeyMapperFailure();
        database.reset();
        database.resetTransactionMatrix();
    }

    /**
     * Verifies a failed NEW method owns and rolls back its transaction.
     */
    @Test
    protected void failingNewOutsideTransactionRollsBack() {
        TxException thrown = assertThrows(TxException.class, () -> failures.failNew("new-failed"));

        assertThat(database.committedByName("new-failed").isEmpty(), is(true));
        assertThat(failures.insertNew("new-recovery"), greaterThan(0L));
        assertThat(database.committedByName("new-recovery").isPresent(), is(true));
        assertThat(hasCause(thrown, mapperFailure), is(true));
    }

    /**
     * Verifies a failed inner NEW method rolls back and resumes an outer transaction that commits.
     */
    @Test
    protected void failingInnerNewResumesOuterTransactionWhichCommits() {
        Tx.transaction(Tx.Type.REQUIRED, () -> {
            assertThat(contacts.insert("outer-before-new-failure"), greaterThan(0L));
            TxException thrown = assertThrows(TxException.class,
                                              () -> failures.failNew("inner-new-failed"));
            assertThat(hasCause(thrown, mapperFailure), is(true));
            assertThat(contacts.insert("outer-after-new-failure"), greaterThan(0L));
            return null;
        });

        assertThat(database.committedByName("inner-new-failed").isEmpty(), is(true));
        assertThat(database.committedByName("outer-before-new-failure").isPresent(), is(true));
        assertThat(database.committedByName("outer-after-new-failure").isPresent(), is(true));
    }

    /**
     * Verifies a caught MANDATORY failure marks its joined transaction rollback-only.
     */
    @Test
    protected void caughtMandatoryFailureMarksOuterTransactionRollbackOnly() {
        assertJoinedFailureRollsBack(failures::failMandatory, "mandatory");
    }

    /**
     * Verifies a caught SUPPORTED failure marks its joined transaction rollback-only.
     */
    @Test
    protected void caughtSupportedFailureMarksOuterTransactionRollbackOnly() {
        assertJoinedFailureRollsBack(failures::failSupportedQuery, "supported");
    }

    @AfterEach
    protected final void shutDownApplication() {
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

    private void assertJoinedFailureRollsBack(Runnable failingOperation, String label) {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            assertThat(focusedTransactions.insertRequired(label + "-before-failure"), is(1L));
            assertThrows(TxException.class, failingOperation::run);
            assertThat(focusedTransactions.insertRequired(label + "-after-failure"), is(1L));
            return null;
        }));

        assertThat(database.committedTransactionValues(), is(List.of("baseline")));
        Tx.transaction(Tx.Type.REQUIRED, () -> focusedTransactions.insertRequired(label + "-recovery"));
        assertThat(database.committedTransactionValues(), is(List.of("baseline", label + "-recovery")));
    }
}
