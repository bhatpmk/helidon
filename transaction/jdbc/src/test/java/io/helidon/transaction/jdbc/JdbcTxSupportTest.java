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

import java.util.ArrayList;
import java.util.List;

import io.helidon.transaction.Tx;
import io.helidon.transaction.TxException;
import io.helidon.transaction.spi.TxLifeCycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTxSupportTest {

    @Test
    void requiredBeginsAndCommitsOneTransaction() {
        RecordingLifeCycle events = new RecordingLifeCycle();
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        assertEquals("result", support.transaction(Tx.Type.REQUIRED, () -> "result"));

        assertEquals(List.of("start:jdbc", "begin", "commit", "end"), events.eventKinds());
    }

    @Test
    void nestedRequiredJoinsAndFailureMarksOuterRollbackOnly() {
        RecordingLifeCycle events = new RecordingLifeCycle();
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        assertThrows(TxException.class, () -> support.transaction(Tx.Type.REQUIRED, () -> {
            support.transaction(Tx.Type.REQUIRED, () -> {
                throw new IllegalStateException("boom");
            });
            return null;
        }));

        assertEquals(1, events.count("begin"));
        assertEquals(0, events.count("commit"));
        assertEquals(1, events.count("rollback"));
        assertEquals(2, events.count("start:jdbc"));
        assertEquals(2, events.count("end"));
    }

    @Test
    void newSuspendsAndResumesOuterTransaction() {
        RecordingLifeCycle events = new RecordingLifeCycle();
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        support.transaction(Tx.Type.REQUIRED, () -> {
            support.transaction(Tx.Type.NEW, () -> null);
            return null;
        });

        assertEquals(List.of("start:jdbc",
                             "begin",
                             "start:jdbc",
                             "suspend",
                             "begin",
                             "commit",
                             "resume",
                             "end",
                             "commit",
                             "end"),
                     events.eventKinds());
    }

    @Test
    void enforcesMandatoryAndNeverPropagation() {
        JdbcTxSupport support = new JdbcTxSupport(List.of());

        assertThrows(TxException.class, () -> support.transaction(Tx.Type.MANDATORY, () -> null));
        support.transaction(Tx.Type.REQUIRED, () -> {
            assertThrows(TxException.class, () -> support.transaction(Tx.Type.NEVER, () -> null));
            return null;
        });
    }

    @Test
    void caughtJoinedFailureStillMarksTransactionRollbackOnly() {
        RecordingLifeCycle events = new RecordingLifeCycle();
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        assertThrows(TxException.class, () -> support.transaction(Tx.Type.REQUIRED, () -> {
            assertThrows(TxException.class, () -> support.transaction(Tx.Type.SUPPORTED, () -> {
                throw new Exception("joined failure");
            }));
            return "ignored";
        }));

        assertEquals(1, events.count("begin"));
        assertEquals(1, events.count("rollback"));
        assertEquals(0, events.count("commit"));
    }

    @Test
    void unsupportedSuspendsWithoutStartingAnotherTransaction() {
        RecordingLifeCycle events = new RecordingLifeCycle();
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        support.transaction(Tx.Type.REQUIRED, () -> {
            support.transaction(Tx.Type.UNSUPPORTED, () -> null);
            return null;
        });

        assertEquals(List.of("start:jdbc",
                             "begin",
                             "start:jdbc",
                             "suspend",
                             "resume",
                             "end",
                             "commit",
                             "end"),
                     events.eventKinds());
    }

    @Test
    void resumeFailureDoesNotReplaceTheTransactionFailure() {
        RecordingLifeCycle events = new RecordingLifeCycle() {
            @Override
            public void resume(String txIdentity) {
                super.resume(txIdentity);
                throw new IllegalStateException("resume failed");
            }
        };
        JdbcTxSupport support = new JdbcTxSupport(List.of(events));

        TxException failure = assertThrows(TxException.class, () -> support.transaction(Tx.Type.REQUIRED, () -> {
            support.transaction(Tx.Type.NEW, () -> {
                throw new IllegalArgumentException("task failed");
            });
            return null;
        }));

        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(2, events.count("rollback"));
    }

    private static class RecordingLifeCycle implements TxLifeCycle {
        private final List<String> events = new ArrayList<>();

        @Override
        public void start(String type) {
            events.add("start:" + type);
        }

        @Override
        public void end() {
            events.add("end");
        }

        @Override
        public void begin(String txIdentity) {
            assertTrue(!txIdentity.isBlank());
            events.add("begin:" + txIdentity);
        }

        @Override
        public void commit(String txIdentity) {
            events.add("commit:" + txIdentity);
        }

        @Override
        public void rollback(String txIdentity) {
            events.add("rollback:" + txIdentity);
        }

        @Override
        public void suspend(String txIdentity) {
            events.add("suspend:" + txIdentity);
        }

        @Override
        public void resume(String txIdentity) {
            events.add("resume:" + txIdentity);
        }

        private List<String> eventKinds() {
            return events.stream().map(event -> event.substring(0, event.indexOf(':') < 0
                    ? event.length()
                    : event.indexOf(':'))).map(kind -> kind.equals("start") ? "start:jdbc" : kind).toList();
        }

        private long count(String kind) {
            return eventKinds().stream().filter(kind::equals).count();
        }
    }
}
