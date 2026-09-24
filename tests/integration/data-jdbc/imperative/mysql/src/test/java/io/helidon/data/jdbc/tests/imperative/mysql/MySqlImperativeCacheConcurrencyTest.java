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
package io.helidon.data.jdbc.tests.imperative.mysql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.helidon.data.jdbc.JdbcClient;
import io.helidon.data.jdbc.tests.support.ExternalPoolSupport;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Exercises one shared client while its parameter-count cache churns against MySQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlImperativeCacheConcurrencyTest {
    @Container
    static final MySQLContainer<?> MYSQL = MySqlImperativeTestSupport.MYSQL;

    private static final int CACHE_CAPACITY = 4;
    private static final int MAX_SQL_LENGTH = 128;
    private static final int WORKERS = 8;
    private static final int ITERATIONS = 4;

    /**
     * Proves marker counts, binds, committed values, and leases remain isolated during concurrent cache eviction.
     *
     * @throws Exception if the bounded concurrency harness fails
     */
    @Test
    void executesCorrectlyWhileSharedParameterCountCacheChurns() throws Exception {
        try (HikariDataSource pool = ExternalPoolSupport.pool(MySqlImperativeTestSupport.config())) {
            JdbcClient client = JdbcClient.builder()
                    .dataSource(pool)
                    .parameterCountCacheCapacity(CACHE_CAPACITY)
                    .parameterCountCacheMaxSqlLength(MAX_SQL_LENGTH)
                    .build();
            client.create("DELETE FROM CONTACT").execute();
            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            try (ExecutorService executor = Executors.newFixedThreadPool(WORKERS)) {
                for (int worker = 0; worker < WORKERS; worker++) {
                    int workerId = worker;
                    futures.add(executor.submit(() -> executeWorker(client, ready, start, workerId)));
                }
                assertThat(ready.await(30, TimeUnit.SECONDS), is(true));
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            }

            List<String> expected = new ArrayList<>();
            for (int worker = 0; worker < WORKERS; worker++) {
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    expected.add(value(worker, iteration));
                }
            }
            Collections.sort(expected);
            assertThat(client.create("SELECT NAME FROM CONTACT ORDER BY NAME").map(String.class).list(), is(expected));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections(), is(0));
            assertThat(client.create("SELECT COUNT(*) FROM CONTACT").map(Long.class).one(),
                       is((long) WORKERS * ITERATIONS));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections(), is(0));
        }
    }

    private static void executeWorker(JdbcClient client,
                                      CountDownLatch ready,
                                      CountDownLatch start,
                                      int worker) {
        ready.countDown();
        await(start);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            String value = value(worker, iteration);
            int oneMarkerLength = iteration == 0 ? MAX_SQL_LENGTH : MAX_SQL_LENGTH - 1;
            int repeatedMarkerLength = iteration == 0 ? MAX_SQL_LENGTH + 1 : MAX_SQL_LENGTH - 2;
            assertThat(client.create(paddedSql("SELECT 'constant'", "zero-" + value, MAX_SQL_LENGTH - 3))
                               .map(String.class)
                               .one(),
                       is("constant"));
            assertThat(client.create(paddedSql("SELECT ?", "one-" + value, oneMarkerLength))
                               .bind(1, value)
                               .map(String.class)
                               .one(),
                       is(value));
            assertThat(client.create(paddedSql("SELECT CONCAT(?, ':', ?)",
                                               "repeat-" + value,
                                               repeatedMarkerLength))
                               .bind(1, value)
                               .bind(2, iteration)
                               .map(String.class)
                               .one(),
                       is(value + ":" + iteration));
            assertThat(client.create(paddedSql("INSERT INTO CONTACT (NAME, EMAIL) VALUES (?, NULL)",
                                               "insert-" + value,
                                               MAX_SQL_LENGTH - 1))
                               .bind(1, value)
                               .execute(),
                       is(1L));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start cache churn workers.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start cache churn workers.", e);
        }
    }

    private static String paddedSql(String sql, String discriminator, int length) {
        String prefix = sql + " /* " + discriminator + " ";
        int padding = length - prefix.length() - 2;
        if (padding < 0) {
            throw new IllegalArgumentException("The requested SQL length is too short for its discriminator.");
        }
        return prefix + "x".repeat(padding) + "*/";
    }

    private static String value(int worker, int iteration) {
        return "bh-cache-%02d-%02d".formatted(worker, iteration);
    }
}
