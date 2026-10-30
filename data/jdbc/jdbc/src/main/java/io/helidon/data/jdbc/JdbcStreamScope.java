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
package io.helidon.data.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Retains processing failures and confines a JDBC stream to its callback thread.
 */
final class JdbcStreamScope {
    private final Thread thread = Thread.currentThread();
    private final List<Runnable> closeHandlers = new ArrayList<>();
    private final Runnable parentCheck;
    private final Consumer<Throwable> failureHandler;
    private volatile boolean closed;
    private Throwable failure;

    JdbcStreamScope() {
        this(() -> { }, _ -> { });
    }

    JdbcStreamScope(Runnable parentCheck, Consumer<Throwable> failureHandler) {
        this.parentCheck = parentCheck;
        this.failureHandler = failureHandler;
    }

    static Throwable mergeProcessingFailures(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (secondary == null || secondary == primary) {
            return primary;
        }
        if (secondary instanceof Error && !(primary instanceof Error)) {
            Throwable earlier = primary;
            primary = secondary;
            secondary = earlier;
        }
        // Nested processing boundaries can observe the same secondary failure more than once.
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) {
                return primary;
            }
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    void check() {
        checkThread();
        if (closed) {
            throw new IllegalStateException("The JDBC stream is closed.");
        }
        if (failure != null) {
            throw new IllegalStateException("The JDBC stream cannot be used after a processing failure.");
        }
        parentCheck.run();
    }

    void checkThread() {
        if (Thread.currentThread() != thread) {
            throw new IllegalStateException("The JDBC stream can be used only on its callback thread.");
        }
    }

    boolean failed() {
        return failure != null;
    }

    void close() {
        checkThread();
        closed = true;
    }

    RuntimeException fail(Throwable caught) {
        record(caught);
        if (caught instanceof Error error) {
            throw error;
        }
        return (RuntimeException) caught;
    }

    void record(Throwable caught) {
        failure = mergeProcessingFailures(failure, caught);
        failureHandler.accept(caught);
    }

    void onClose(Runnable handler) {
        closeHandlers.add(handler);
    }

    void runCloseHandlers() {
        Throwable closingFailure = null;
        // One JDK handler preserves registration order while allowing fatal errors to remain primary.
        for (Runnable handler : closeHandlers) {
            try {
                handler.run();
            } catch (RuntimeException | Error caught) {
                closingFailure = mergeProcessingFailures(closingFailure, caught);
            }
        }
        closeHandlers.clear();
        if (closingFailure != null) {
            record(closingFailure);
            if (closingFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) closingFailure;
        }
    }

    void rethrow() {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }
}
