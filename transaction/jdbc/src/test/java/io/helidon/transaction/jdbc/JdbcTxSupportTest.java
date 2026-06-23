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
package io.helidon.transaction.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.transaction.Tx.Type.MANDATORY;
import static io.helidon.transaction.Tx.Type.NEW;
import static io.helidon.transaction.Tx.Type.NEVER;
import static io.helidon.transaction.Tx.Type.REQUIRED;
import static io.helidon.transaction.Tx.Type.SUPPORTED;
import static io.helidon.transaction.Tx.Type.UNSUPPORTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcTxSupportTest {

    private JdbcTxSupport t;
    
    private JdbcTxSupportTest() {
        super();
    }

    @BeforeEach
    void createJdbcTxSupport() {
        this.t = new JdbcTxSupport();
    }

    @Test
    void requiredWithoutTransactionCreatesAndCompletesOne() {
        AtomicReference<String> transactionId = new AtomicReference<>();
        String result =
            t.transaction(REQUIRED,
                          () -> {
                              assertThat(t.transactionActive(),
                                         is(true));
                              transactionId.set(t.currentTransactionId().orElseThrow(AssertionError::new));
                              return "ok";
                          });
        assertThat(result,
                   is("ok"));
        assertThat(transactionId.get(),
                   is(not("")));
        assertThat(t.transactionActive(),
                   is(false));
        assertThat(t.depth(),
                   is(0));
    }

    @Test
    void requiredWithTransactionJoinsCurrent() {
        AtomicReference<String> outerId = new AtomicReference<>();
        t.transaction(REQUIRED, () -> {
                outerId.set(t.currentTransactionId().orElseThrow(AssertionError::new));
                String result =
                    t.transaction(REQUIRED,
                                  () -> {
                                      assertThat(t.currentTransactionId().orElseThrow(AssertionError::new),
                                                 is(outerId.get()));
                                      assertThat(t.depth(),
                                                 is(1));
                                      return "ok";
                                  });
                assertThat(result,
                           is("ok"));
                assertThat(t.currentTransactionId().orElseThrow(AssertionError::new),
                           is(outerId.get()));
                return null;
            });
    }

    @Test
    void mandatoryOutsideTransactionFails() {
        assertThrows(TxException.class,
                     () -> t.transaction(MANDATORY,
                                         () -> "nope"));
    }

    @Test
    void neverInsideTransactionFails() {

        assertThrows(TxException.class,
                     () -> t.transaction(REQUIRED,
                                         () -> t.transaction(NEVER, () -> "nope")));
    }

    @Test
    void supportedOutsideTransactionDoesNotCreateOne() {
        String result =
            t.transaction(SUPPORTED,
                          () -> {
                              assertThat(t.transactionActive(),
                                         is(false));
                              return "ok";
                          });
        assertThat(result,
                   is("ok"));
        assertThat(t.depth(),
                   is(0));
    }

    @Test
    void supportedInsideTransactionUsesCurrent() {
        String result =
            t.transaction(REQUIRED,
                          () -> t.transaction(SUPPORTED,
                                              () -> {
                                                  assertThat(t.transactionActive(),
                                                             is(true));
                                                  assertThat(t.depth(),
                                                             is(1));
                                                  return "ok";
                                              }));
        assertThat(result,
                   is("ok"));
    }

    @Test
    void newInsideTransactionSuspendsOuterAndResumes() {
        AtomicReference<String> outerId = new AtomicReference<>();
        AtomicReference<String> innerId = new AtomicReference<>();
        String result =
            t.transaction(REQUIRED,
                          () -> {
                              outerId.set(t.currentTransactionId().orElseThrow(AssertionError::new));
                              return
                                  t.transaction(NEW,
                                                () -> {
                                                    innerId.set(t.currentTransactionId().orElseThrow(AssertionError::new));
                                                    assertThat(innerId.get(),
                                                               is(not(outerId.get())));
                                                    assertThat(t.depth(),
                                                               is(2));
                                                    return "ok";
                                                });
                          });
        assertThat(result,
                   is("ok"));
        assertThat(innerId.get(),
                   is(not(outerId.get())));
        assertThat(t.transactionActive(),
                   is(false));
        assertThat(t.depth(),
                   is(0));
    }

    @Test
    void unsupportedInsideTransactionSuspendsOuterAndRunsWithout() {
        String result =
            t.transaction(REQUIRED,
                          () -> t.transaction(UNSUPPORTED,
                                              () -> {
                                                  assertThat(t.transactionActive(),
                                                             is(false));
                                                  assertThat(t.currentTransactionId().isPresent(),
                                                             is(false));
                                                  assertThat(t.depth(),
                                                             is(1));
                                                  return "ok";
                                              }));
        assertThat(result,
                   is("ok"));
        assertThat(t.transactionActive(),
                   is(false));
    }

    @Test
    void joinedTransactionFailureCausesOuterCompletionToFailRollbackOnly() {
        assertThrows(IllegalStateException.class,
                     () -> t.transaction(REQUIRED,
                                         () -> t.transaction(REQUIRED,
                                                             () -> {
                                                                 throw new IllegalStateException("boom");
                                                             })));
        assertThat(t.transactionActive(),
                   is(false));
    }

    @Test
    void exceptionInJoinedTransactionMarksRollbackOnlyInsideOuter() {
        AtomicReference<String> currentId = new AtomicReference<>();
        assertThrows(TxException.class,
                     () -> t.transaction(REQUIRED,
                                         () -> {
                                             currentId.set(t.currentTransactionId().orElseThrow(AssertionError::new));
                                             assertThrows(IllegalStateException.class,
                                                          () -> t.transaction(REQUIRED,
                                                                              () -> {
                                                                                  throw new IllegalStateException("boom");
                                                                              }));
                                             assertThat(t.currentTransactionId().orElseThrow(AssertionError::new),
                                                        is(currentId.get()));
                                             assertThat(t.currentActiveTransactionRollbackOnly(),
                                                        is(true));
                                             return null;
                                         }));
        assertThat(t.transactionActive(),
                   is(false));
    }

    @Test
    void exceptionInNewTransactionRollsItBackAndLeavesNoAmbientTransaction() {
        assertThrows(IllegalStateException.class,
                     () -> t.transaction(REQUIRED,
                                         () -> {
                                             throw new IllegalStateException("boom");
                                         }));
        assertThat(t.transactionActive(),
                   is(false));
        assertThat(t.depth(),
                   is(0));
    }

    @Test
    void nestedRequiredFailureMakesOuterCompletionFailRollbackOnly() {
        assertThrows(TxException.class,
                     () -> t.transaction(REQUIRED,
                                         () -> {
                                             assertThrows(IllegalStateException.class,
                                                          () -> t.transaction(REQUIRED,
                                                                              () -> {
                                                                                  throw new IllegalStateException("boom");
                                                                              }));
                                             return "ok";
                                         }));
        assertThat(t.transactionActive(),
                   is(false));
        assertThat(t.depth(),
                   is(0));
    }

    @Test
    void localTransactionRollbackRevertsJdbcWork() throws Exception {
        JdbcDataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE pokemon (
                        id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        name VARCHAR(64) NOT NULL
                    )
                    """);
        }
        TransactionalDataSourceFactory txDataSources =
                new TransactionalDataSourceFactory(ServiceRegistryManager.create().registry());
        DataSource transactionalDataSource = new TransactionalDataSource(txDataSources, dataSource);
        JdbcTxSupport tx = new JdbcTxSupport(txDataSources);

        assertThrows(IllegalStateException.class,
                     () -> tx.transaction(REQUIRED,
                                          () -> {
                                              try (Connection connection = transactionalDataSource.getConnection();
                                                   Statement statement = connection.createStatement()) {
                                                  statement.executeUpdate("INSERT INTO pokemon (name) VALUES ('Bulbasaur')");
                                              }
                                              throw new IllegalStateException("rollback");
                                          }));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM pokemon")) {
            resultSet.next();
            assertThat(resultSet.getInt(1), is(0));
        }
    }

    @Test
    void transactionNotifiesLifecycleListeners() {
        RecordingLifeCycle recording = new RecordingLifeCycle();
        new JdbcTxSupport(recording).transaction(REQUIRED, () -> "ok");
        assertThat(recording.events(),
                   contains("start:jdbc", "begin", "commit", "end"));
    }

    private static final class RecordingLifeCycle implements TxLifeCycle {

        private final List<String> events;

        private RecordingLifeCycle() {
            super();
            this.events = new ArrayList<>();
        }

        private List<String> events() {
            return this.events;
        }

        @Override
        public void start(String type) {
            this.events.add("start:" + type);
        }

        @Override
        public void end() {
            this.events.add("end");
        }

        @Override
        public void begin(String txIdentity) {
            this.events.add("begin");
        }

        @Override
        public void commit(String txIdentity) {
            this.events.add("commit");
        }

        @Override
        public void rollback(String txIdentity) {
            this.events.add("rollback");
        }

        @Override
        public void suspend(String txIdentity) {
            this.events.add("suspend");
        }

        @Override
        public void resume(String txIdentity) {
            this.events.add("resume");
        }

    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

}
