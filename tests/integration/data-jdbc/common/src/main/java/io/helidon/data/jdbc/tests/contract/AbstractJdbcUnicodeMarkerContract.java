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
import java.util.Optional;

import io.helidon.data.jdbc.tests.application.ContactView;
import io.helidon.data.jdbc.tests.declarative.repository.UnicodeMarkerRepository;
import io.helidon.data.jdbc.tests.support.DatabaseFixture;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Portable end-to-end supplementary Unicode bind-marker tests for real databases.
 */
public abstract class AbstractJdbcUnicodeMarkerContract {
    private ServiceRegistryManager manager;
    private UnicodeMarkerRepository repository;

    /**
     * Publishes database-specific configuration before the registry starts.
     */
    protected abstract void beforeStartApplication();

    @BeforeEach
    protected final void setUpApplication() {
        beforeStartApplication();
        manager = ServiceRegistryManager.start();
        manager.registry().get(DatabaseFixture.class).reset();
        repository = manager.registry().get(UnicodeMarkerRepository.class);
    }

    /**
     * Proves a named marker beginning with a supplementary code point binds end to end.
     */
    @Test
    protected void supplementaryIdentifierStartBindsThroughGeneratedRepository() {
        ContactView contact = repository.findBySupplementaryStart("alpha");

        assertThat(contact.name(), is("alpha"));
        assertThat(contact.email(), is(Optional.of("alpha@example.test")));
    }

    /**
     * Proves a repeated marker containing a supplementary code point binds every physical position.
     */
    @Test
    protected void repeatedSupplementaryIdentifierPartBindsEveryPosition() {
        assertThat(repository.findByRepeatedSupplementaryPart("alpha"),
                   is(List.of(new ContactView(1L, "alpha", Optional.of("alpha@example.test")))));
    }

    @AfterEach
    protected final void shutDownApplication() {
        if (manager != null) {
            manager.shutdown();
        }
    }
}
