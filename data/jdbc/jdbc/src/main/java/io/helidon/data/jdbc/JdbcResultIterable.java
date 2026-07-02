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

/**
 * Single-use, closeable rows produced by a JDBC streaming terminal.
 * <p>
 * The result is sequential and thread-confined. It owns the JDBC operation internally, without exposing its connection,
 * statement, or result set. Exhausting the iterator closes those resources automatically. Callers must still use
 * try-with-resources so early termination and application failures close the operation promptly.
 * <p>
 * Closure releases the result set, statement, and logical connection handle in that order. With a transaction-aware
 * data source, closing the logical handle does not necessarily close the physical connection; transaction integration
 * controls the physical connection lifecycle.
 * <p>
 * A result must be consumed and closed before its caller-owned transaction scope ends. Calling {@link #iterator()} more
 * than once is an error.
 *
 * @param <T> mapped row type
 */
public interface JdbcResultIterable<T> extends Iterable<T>, AutoCloseable {

    /**
     * Close the streaming operation.
     * <p>
     * Closing is idempotent. A JDBC close failure is reported as an unchecked Helidon data exception.
     */
    @Override
    void close();
}
