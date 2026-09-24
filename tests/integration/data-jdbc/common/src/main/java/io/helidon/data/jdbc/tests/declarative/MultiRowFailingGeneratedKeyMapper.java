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
package io.helidon.data.jdbc.tests.declarative;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.data.jdbc.JdbcClient;
import io.helidon.data.jdbc.tests.application.MapperFailureContact;
import io.helidon.service.registry.Service;

/**
 * Mapper that fails with a stable application exception on its second generated-key row.
 */
@Service.Singleton
public final class MultiRowFailingGeneratedKeyMapper implements JdbcClient.RowMapper<MapperFailureContact> {
    private final IllegalStateException failure = new IllegalStateException("second generated key mapper failure");
    private final AtomicInteger invocation = new AtomicInteger();

    @Override
    public MapperFailureContact map(JdbcClient.Row row) {
        Objects.requireNonNull(row, "The JDBC row must not be null.");
        if (invocation.incrementAndGet() == 2) {
            throw failure;
        }
        return new MapperFailureContact(Long.toString(row.get(1, Long.class)));
    }

    /**
     * Arms this mapper for one two-row generated-key operation.
     */
    public void arm() {
        invocation.set(0);
    }

    /**
     * Returns the stable failure instance used to verify exception identity.
     *
     * @return mapper failure
     */
    public RuntimeException failure() {
        return failure;
    }
}
