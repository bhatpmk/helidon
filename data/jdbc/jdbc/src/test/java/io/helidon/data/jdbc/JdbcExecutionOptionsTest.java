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

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcExecutionOptionsTest {

    @Test
    void createsReusableEmptyOptions() {
        assertSame(JdbcExecutionOptions.EMPTY, JdbcExecutionOptions.builder().build());
    }

    @Test
    void buildsAndOverlaysOnlyExplicitValues() {
        JdbcExecutionOptions defaults = JdbcExecutionOptions.builder()
                .fetchSize(20)
                .queryTimeout(Duration.ofSeconds(5))
                .maxRows(100)
                .build();
        JdbcExecutionOptions override = JdbcExecutionOptions.builder()
                .fetchSize(0)
                .maxRows(10)
                .build();

        JdbcExecutionOptions merged = defaults.overlay(override);

        assertEquals(0, merged.fetchSize());
        assertEquals(Duration.ofSeconds(5), merged.queryTimeout());
        assertEquals(10, merged.maxRows());
        assertNull(JdbcExecutionOptions.EMPTY.fetchSize());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcExecutionOptions.builder().fetchSize(-1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcExecutionOptions.builder().maxRows(-1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcExecutionOptions.builder().queryTimeout(Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcExecutionOptions.builder().queryTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcExecutionOptions.builder().queryTimeout(Duration.ofSeconds((long) Integer.MAX_VALUE + 1)));
    }
}
