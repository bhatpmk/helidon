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
 * Default client backed by one datasource and one connection-lease policy.
 */
final class JdbcClientImpl implements JdbcClient {
    private static final int MAX_ANALYZED_SQL = 256;

    private final JdbcRunner runner;
    private final ConcurrentHashMap<String, Integer> parameterCounts = new ConcurrentHashMap<>();
    private final AtomicInteger parameterCountEntries = new AtomicInteger();

    JdbcClientImpl(DataSource dataSource) {
        this(dataSource, JdbcExecutionOptions.EMPTY, JdbcConnectionLease.ownedProvider());
    }

    JdbcClientImpl(DataSource dataSource,
                   JdbcExecutionOptions defaults,
                   JdbcConnectionLease.Provider leaseProvider) {
        this.runner = new JdbcRunner(Objects.requireNonNull(dataSource, "DataSource must not be null"),
                                     Objects.requireNonNull(defaults, "Default options must not be null"),
                                     Objects.requireNonNull(leaseProvider, "Connection lease provider must not be null"));
    }

    @Override
    public Statement create(String sql) {
        Objects.requireNonNull(sql, "SQL must not be null");
        Integer cached = parameterCounts.get(sql);
        int parameterCount;
        if (cached == null) {
            parameterCount = JdbcOperation.parameterCount(sql);
            // Repository SQL is static and benefits from one lexical scan. Bound the cache for imperative dynamic SQL.
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
        return new JdbcStatement(runner, sql, parameterCount);
    }

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
