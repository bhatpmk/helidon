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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.helidon.data.DataException;
import io.helidon.data.jdbc.tests.application.transaction.FocusedTransactionOperations;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused transaction behavior that must hold for both imperative and generated repository use.
 */
public abstract class AbstractJdbcFocusedTransactionContract {
    private ServiceRegistryManager manager;
    private DatabaseFixture database;
    private FocusedTransactionOperations operations;

    /**
     * Returns the operations adapter for one application style.
     *
     * @return focused transaction operations adapter type
     */
    protected abstract Class<? extends FocusedTransactionOperations> operationsType();

    @BeforeEach
    protected final void setUpApplication() {
        beforeStartApplication();
        manager = ServiceRegistryManager.start();
        database = manager.registry().get(DatabaseFixture.class);
        operations = manager.registry().get(operationsType());
        database.resetTransactionMatrix();
    }

    /**
     * Allows a database-specific leaf test to publish dynamic configuration before the registry starts.
     */
    protected void beforeStartApplication() {
    }

    /**
     * Verifies a caught failure in a joined REQUIRED operation still marks the
     * outer local transaction rollback-only. The final assertion uses committed
     * state to prove the insert before the caught failure did not commit.
     */
    @Test
    protected void caughtJoinedFailureMarksOuterTransactionRollbackOnly() {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            assertThat(operations.insertRequired("before-caught-failure"), is(1L));
            TxException failure = assertThrows(TxException.class, operations::failRequired);
            assertThat("Expected the failed JDBC operation in the transaction failure chain",
                       hasCause(failure, DataException.class),
                       is(true));
            return null;
        }));

        assertThat(database.committedTransactionValues(), is(List.of("baseline")));
    }

    /**
     * Verifies a NEW operation uses an independent local transaction. The inner
     * insert must remain committed even when the caller's outer REQUIRED
     * transaction rolls back.
     */
    @Test
    protected void newTransactionCommitSurvivesOuterRollback() {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            assertThat(operations.insertRequired("outer-new-rollback"), is(1L));
            assertThat(operations.insertNew("inner-new-committed"), is(1L));
            throw new DeliberateRollbackException();
        }));

        assertThat(database.committedTransactionValues(), is(List.of("baseline", "inner-new-committed")));
    }

    /**
     * Verifies an UNSUPPORTED operation runs outside a suspended caller
     * transaction. The unsupported insert must remain committed even when the
     * caller's outer REQUIRED transaction rolls back.
     */
    @Test
    protected void unsupportedCommitSurvivesOuterRollback() {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            assertThat(operations.insertRequired("outer-unsupported-rollback"), is(1L));
            assertThat(operations.insertUnsupported("unsupported-committed"), is(1L));
            throw new DeliberateRollbackException();
        }));

        assertThat(database.committedTransactionValues(), is(List.of("baseline", "unsupported-committed")));
    }

    /**
     * Verifies portable DDL succeeds through operation-owned connections when
     * NEVER propagation guarantees that no local transaction is active.
     */
    @Test
    protected void ddlExecutesWithoutLocalTransactionParticipation() {
        assertThat(operations.executeDdlWithoutTransaction(), is(1L));
    }

    /**
     * Verifies concurrent platform-thread transactions retain independent datasource associations.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    protected void concurrentPlatformThreadTransactionsCommitIndependentValues() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            assertConcurrentCommits(executor, "platform");
        }
    }

    /**
     * Verifies concurrent virtual-thread transactions retain independent datasource associations.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    protected void concurrentVirtualThreadTransactionsCommitIndependentValues() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertConcurrentCommits(executor, "virtual");
        }
    }

    /**
     * Verifies a committed transaction is isolated from a concurrent rollback.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    protected void concurrentCommitSurvivesUnrelatedRollback() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> rollback = executor.submit(() -> {
                await(start);
                return Tx.transaction(Tx.Type.REQUIRED, () -> {
                    operations.insertRequired("concurrent-rolled-back");
                    throw new DeliberateRollbackException();
                });
            });
            Future<Long> commit = executor.submit(() -> {
                await(start);
                return Tx.transaction(Tx.Type.REQUIRED,
                                      () -> operations.insertRequired("concurrent-committed"));
            });
            start.countDown();

            assertThrows(ExecutionException.class, () -> rollback.get(30, TimeUnit.SECONDS));
            assertThat(commit.get(30, TimeUnit.SECONDS), is(1L));
        }

        assertThat(database.committedTransactionValues(),
                   is(List.of("baseline", "concurrent-committed")));
    }

    /**
     * Verifies a reused worker does not retain rollback-only or datasource association state.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    protected void workerReuseAfterRollbackStartsWithCleanTransactionState() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> rollback = executor.submit(() -> Tx.transaction(Tx.Type.REQUIRED, () -> {
                operations.insertRequired("reused-worker-rolled-back");
                throw new DeliberateRollbackException();
            }));
            assertThrows(ExecutionException.class, () -> rollback.get(30, TimeUnit.SECONDS));

            Future<Long> recovery = executor.submit(() -> Tx.transaction(
                    Tx.Type.REQUIRED,
                    () -> operations.insertRequired("reused-worker-committed")));
            assertThat(recovery.get(30, TimeUnit.SECONDS), is(1L));
        }

        assertThat(database.committedTransactionValues(),
                   is(List.of("baseline", "reused-worker-committed")));
    }

    /**
     * Verifies child-thread work is independent of the parent thread-bound transaction.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    protected void childThreadDoesNotInheritParentTransaction() throws Exception {
        assertThrows(TxException.class, () -> Tx.transaction(Tx.Type.REQUIRED, () -> {
            operations.insertRequired("parent-thread-rolled-back");
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Long> child = executor.submit(
                        () -> operations.insertRequired("child-thread-committed"));
                assertThat(child.get(30, TimeUnit.SECONDS), is(1L));
            }
            throw new DeliberateRollbackException();
        }));

        assertThat(database.committedTransactionValues(),
                   is(List.of("baseline", "child-thread-committed")));
    }

    @AfterEach
    protected final void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        for (Throwable current = throwable;
                current != null && current != current.getCause();
                current = current.getCause()) {
            if (expectedType.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start concurrent transaction work.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start concurrent transaction work.", e);
        }
    }

    private void assertConcurrentCommits(ExecutorService executor, String prefix) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Long> first = executor.submit(() -> {
            ready.countDown();
            await(start);
            return Tx.transaction(Tx.Type.REQUIRED,
                                  () -> operations.insertRequired(prefix + "-first"));
        });
        Future<Long> second = executor.submit(() -> {
            ready.countDown();
            await(start);
            return Tx.transaction(Tx.Type.REQUIRED,
                                  () -> operations.insertRequired(prefix + "-second"));
        });
        if (!ready.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out preparing concurrent transaction work.");
        }
        start.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS), is(1L));
        assertThat(second.get(30, TimeUnit.SECONDS), is(1L));
        assertThat(database.committedTransactionValues(),
                   is(List.of("baseline", prefix + "-first", prefix + "-second")));
    }

    private static final class DeliberateRollbackException extends RuntimeException {
    }
}
