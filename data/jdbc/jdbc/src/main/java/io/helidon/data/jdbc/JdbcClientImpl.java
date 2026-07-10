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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * Package-private implementation of the public {@link JdbcClient} contract.
 *
 * <p>The client owns the immutable runner configuration and creates only
 * in-memory statement stages. A generated declarative repository therefore
 * calls this implementation indirectly through the public client contract;
 * no connection is borrowed until a terminal operation reaches
 * {@link JdbcRunner}.</p>
 *
 * <p>The implementation is safe to share because its runner configuration and
 * bounded SQL-count cache are thread-safe. The {@link JdbcStatement} returned
 * by {@link #create(String)} remains single-use and is not thread-safe.</p>
 */
final class JdbcClientImpl implements JdbcClient {
    /** Maximum number of distinct SQL strings retained in the marker-count cache. */
    private static final int MAX_ANALYZED_SQL = 256;

    /** Runner that owns connection leases and all JDBC execution. */
    private final JdbcRunner runner;
    /** Bounded cache of lexical parameter counts for repeated SQL strings. */
    private final ConcurrentHashMap<String, Integer> parameterCounts = new ConcurrentHashMap<>();
    /** Number of cache reservations, kept separately for lock-free bounding. */
    private final AtomicInteger parameterCountEntries = new AtomicInteger();

    /**
     * Creates a client that owns connections obtained directly from the datasource.
     *
     * @param dataSource datasource used for terminal operations
     */
    JdbcClientImpl(DataSource dataSource) {
        this(dataSource, JdbcStatementOptions.EMPTY, JdbcConnectionLease.ownedProvider());
    }

    /**
     * Creates a client with datasource defaults and a connection-lease policy.
     *
     * @param dataSource datasource used for terminal operations
     * @param defaults default statement options
     * @param leaseProvider provider that decides whether an operation owns or borrows a connection
     */
    JdbcClientImpl(DataSource dataSource,
                   JdbcStatementOptions defaults,
                   JdbcConnectionLease.Provider leaseProvider) {
        this.runner = new JdbcRunner(Objects.requireNonNull(dataSource, "DataSource must not be null"),
                                     Objects.requireNonNull(defaults, "Default options must not be null"),
                                     Objects.requireNonNull(leaseProvider, "Connection lease provider must not be null"));
    }

    /**
     * Creates a new in-memory statement stage.
     *
     * <p>Declarative code has already rewritten named parameters to JDBC
     * positional markers. Counting those markers lets the stage allocate the
     * exact number of bind slots without preparing a JDBC statement. The
     * bounded cache benefits static generated SQL while preventing unbounded
     * retention when callers supply dynamic SQL.</p>
     *
     * @param sql SQL text using positional JDBC markers
     * @return a new single-use statement stage
     */
    @Override
    public Statement create(String sql) {
        Objects.requireNonNull(sql, "SQL must not be null");
        Integer cached = parameterCounts.get(sql);
        int parameterCount;
        if (cached == null) {
            // This lexical pass does not parse SQL; it only finds bind markers outside protected regions.
            parameterCount = JdbcOperation.parameterCount(sql);
            // Generated repository SQL is reused frequently, while dynamic SQL must not grow this cache without bound.
            if (reserveCacheEntry()) {
                Integer existing = parameterCounts.putIfAbsent(sql, parameterCount);
                if (existing != null) {
                    parameterCountEntries.decrementAndGet();
                    parameterCount = existing;
                }
            }
        } else {
            parameterCount = cached;
        }
        // Creating this stage performs no JDBC I/O; execution begins at its terminal operation.
        return new JdbcStatement(runner, sql, parameterCount);
    }

    /**
     * Reserves one cache slot without allowing dynamic SQL to exhaust memory.
     *
     * @return {@code true} when a new SQL count may be cached
     */
    private boolean reserveCacheEntry() {
        int current = parameterCountEntries.get();
        while (current < MAX_ANALYZED_SQL) {
            if (parameterCountEntries.compareAndSet(current, current + 1)) {
                return true;
            }
            current = parameterCountEntries.get();
        }
        return false;
    }
}
