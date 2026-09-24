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

import io.helidon.config.Config;
import io.helidon.data.jdbc.tests.application.GeneratedKeyOperations;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.data.jdbc.tests.support.ExternalPoolSupport;
import io.helidon.data.jdbc.tests.support.TestConfigFactory;
import io.helidon.data.jdbc.tests.support.TestDataSourceFactory;
import io.helidon.service.registry.ServiceRegistryManager;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Post-execution failure and one-connection pool recovery contract.
 */
public abstract class AbstractJdbcResourceFailureContract {
    private static final String DATA_SOURCE_NAME = "bug-hunt-one-connection-pool";

    private ServiceRegistryManager manager;
    private HikariDataSource pool;
    private DatabaseFixture database;
    private GeneratedKeyOperations generatedKeys;

    /**
     * Returns the adapter type for one application programming style.
     *
     * @return generated-key operations adapter type
     */
    protected abstract Class<? extends GeneratedKeyOperations> operationsType();

    /**
     * Returns the active external database configuration.
     *
     * @return database configuration
     */
    protected abstract Config databaseConfig();

    @BeforeEach
    protected final void setUpApplication() {
        pool = ExternalPoolSupport.pool(databaseConfig());
        TestDataSourceFactory.dataSource(DATA_SOURCE_NAME, pool);
        TestConfigFactory.config(ExternalPoolSupport.clientConfig(DATA_SOURCE_NAME));
        manager = ServiceRegistryManager.start();
        database = manager.registry().get(DatabaseFixture.class);
        database.reset();
        generatedKeys = manager.registry().get(operationsType());
    }

    /**
     * Proves a post-commit mapper failure returns the only lease and permits state inspection and recovery.
     */
    @Test
    protected final void mapperFailureReturnsOnlyPoolLeaseAfterCommittedWrite() {
        String failedName = "resource-mapper-failure-committed";
        RuntimeException expected = generatedKeys.generatedKeyMapperFailure();
        assertPoolIdle();

        RuntimeException failure = assertThrows(RuntimeException.class,
                                                () -> generatedKeys.insertWithMapperFailure(failedName));

        assertThat(failure, sameInstance(expected));
        assertPoolIdle();
        assertThat(database.committedByName(failedName).isPresent(), is(true));
        assertPoolIdle();
        generatedKeys.insertScalar("resource-recovery");
        assertThat(database.committedByName("resource-recovery").isPresent(), is(true));
        assertPoolIdle();
        assertThat(pool.getHikariPoolMXBean().getTotalConnections(), is(1));
    }

    @AfterEach
    protected final void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
        TestDataSourceFactory.reset();
        TestConfigFactory.reset();
        if (pool != null) {
            pool.close();
        }
    }

    private void assertPoolIdle() {
        assertThat(pool.getHikariPoolMXBean().getActiveConnections(), is(0));
    }
}
