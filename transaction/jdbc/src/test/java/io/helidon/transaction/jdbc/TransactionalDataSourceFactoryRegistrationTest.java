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

import javax.sql.DataSource;

import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

class TransactionalDataSourceFactoryRegistrationTest {

    private static ServiceRegistryManager serviceRegistryManager;

    private ServiceRegistry serviceRegistry;

    private TransactionalDataSourceFactoryRegistrationTest() {
        super();
    }

    @BeforeAll
    static void createServiceRegistryManager() {
        serviceRegistryManager = ServiceRegistryManager.create();
    }

    @BeforeEach
    void acquireServiceRegistry() {
        this.serviceRegistry = serviceRegistryManager.registry();
    }

    @AfterAll
    static void shutdownServiceRegistryManager() {
        serviceRegistryManager.shutdown();
    }

    @DisplayName("The TransactionalDataSourceFactory installs itself in the ServiceRegistry properly")
    @Test
    void namedDatasourceIsWrappedByTransactionalDatasourceFactory() {
        DataSource dataSource = this.serviceRegistry.getNamed(DataSource.class, "test");
        assertThat(dataSource, instanceOf(TransactionalDataSource.class));
    }

}
