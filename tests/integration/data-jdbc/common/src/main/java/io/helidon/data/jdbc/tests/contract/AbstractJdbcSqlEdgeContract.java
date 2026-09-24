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

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import io.helidon.data.DataException;
import io.helidon.data.jdbc.tests.application.ContactOperations;
import io.helidon.data.jdbc.tests.application.ContactView;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.data.jdbc.tests.support.DirectJdbcFixture;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-driver SQL comment, delimiter, and label metadata Bug Hunt coverage.
 */
public abstract class AbstractJdbcSqlEdgeContract {
    private ServiceRegistryManager manager;
    private ContactOperations contacts;
    private DirectJdbcFixture directJdbc;

    /**
     * Returns the adapter type for one application programming style.
     *
     * @return operations adapter type
     */
    protected abstract Class<? extends ContactOperations> operationsType();

    /**
     * Publishes database-specific configuration before the registry starts.
     */
    protected abstract void beforeStartApplication();

    @BeforeEach
    protected final void setUpApplication() {
        beforeStartApplication();
        manager = ServiceRegistryManager.start();
        DatabaseFixture database = manager.registry().get(DatabaseFixture.class);
        database.reset();
        contacts = manager.registry().get(operationsType());
        directJdbc = manager.registry().get(DirectJdbcFixture.class);
    }

    /**
     * Proves valid leading, embedded, trailing, and marker-shaped SQL comments execute unchanged.
     */
    @Test
    protected final void executesValidCommentedSqlThroughBothApplicationStyles() {
        assertThat(contacts.commentedNames(1), is(List.of("alpha", "alpha", "alpha", "alpha", "alpha", "alpha")));
        assertRecovery();
    }

    /**
     * Proves real-driver metadata permits unused duplicates but rejects a requested case-insensitive duplicate.
     */
    @Test
    protected final void enforcesLabelAmbiguityAgainstExternalDriverMetadata() {
        DirectJdbcFixture.MetadataOutcome metadata = directJdbc.metadata("""
                SELECT NAME AS "detail", EMAIL AS "DETAIL"
                FROM CONTACT
                WHERE ID = 1
                """);

        assertThat(metadata.successful(), is(true));
        assertThat(metadata.labels(), is(List.of("detail", "DETAIL")));
        assertThat(contacts.uniqueLabelAmongUnusedDuplicates(1), is(1L));
        DataException ambiguous = assertThrows(DataException.class,
                                               () -> contacts.duplicatedCaseInsensitiveLabel(1));
        assertThat(ambiguous.getMessage(), is("The result contains more than one column labeled 'detail'."));
        assertRecovery();
    }

    /**
     * Proves physical-name fallback when the current driver can expose a blank column label.
     */
    @Test
    protected final void fallsBackToPhysicalNameForDriverExposedBlankLabel() {
        DirectJdbcFixture.MetadataOutcome metadata =
                directJdbc.metadata("SELECT NAME AS \"\" FROM CONTACT WHERE ID = 1");
        assumeTrue(metadata.successful(),
                   () -> "The driver cannot produce a blank label: SQLSTATE=" + metadata.sqlState()
                           + ", vendorCode=" + metadata.vendorCode());
        assertThat(metadata.labels().size(), is(1));
        assertThat(metadata.labels().getFirst() == null || metadata.labels().getFirst().isBlank(), is(true));
        assertThat(metadata.names().getFirst().equalsIgnoreCase("NAME"), is(true));

        assertThat(contacts.blankLabelFallback(1), is("alpha"));
        assertRecovery();
    }

    /**
     * Characterizes semicolon-only SQL as driver-delegated while preserving valid terminal semicolons.
     */
    @Test
    protected final void characterizesSemicolonOnlyDriverDelegationAndRecovery() {
        DirectJdbcFixture.SqlOutcome direct = directJdbc.execute(";");
        assertThat(direct.successful(), is(false));

        DataException failure = assertThrows(DataException.class, contacts::executeSemicolonOnly);
        SQLException driverFailure = findSqlException(failure);
        assertThat(Optional.ofNullable(driverFailure.getSQLState()), is(direct.sqlState()));
        assertThat(driverFailure.getErrorCode(), is(direct.vendorCode()));
        assertThat(contacts.nameWithTerminalSemicolon(1), is("alpha"));
        assertRecovery();
    }

    @AfterEach
    protected final void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    private static SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure;
                current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        throw new AssertionError("The provider failure did not retain sanitized JDBC metadata.", failure);
    }

    private void assertRecovery() {
        assertThat(contacts.findByName("alpha"),
                   is(Optional.of(new ContactView(1, "alpha", Optional.of("alpha@example.test")))));
    }
}
