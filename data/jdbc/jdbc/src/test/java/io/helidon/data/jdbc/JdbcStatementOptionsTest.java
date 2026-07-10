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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcStatementOptionsTest {

    @Test
    void createsReusableEmptyOptions() {
        assertSame(JdbcStatementOptions.EMPTY, JdbcStatementOptions.builder().build());
    }

    @Test
    void buildsAndOverlaysOnlyExplicitValues() {
        JdbcStatementOptions defaults = JdbcStatementOptions.builder()
                .fetchSize(20)
                .queryTimeout(Duration.ofSeconds(5))
                .maxRows(100)
                .poolableHint(true)
                .build();
        JdbcStatementOptions override = JdbcStatementOptions.builder()
                .fetchSize(0)
                .maxRows(10)
                .poolableHint(false)
                .build();

        JdbcStatementOptions merged = defaults.overlay(override);

        assertEquals(0, merged.fetchSize());
        assertEquals(Duration.ofSeconds(5), merged.queryTimeout());
        assertEquals(10, merged.maxRows());
        assertFalse(merged.poolableHint());
        assertNull(JdbcStatementOptions.EMPTY.fetchSize());
        assertNull(JdbcStatementOptions.EMPTY.poolableHint());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcStatementOptions.builder().fetchSize(-1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcStatementOptions.builder().maxRows(-1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcStatementOptions.builder().queryTimeout(Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcStatementOptions.builder().queryTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcStatementOptions.builder()
                             .queryTimeout(Duration.ofSeconds((long) Integer.MAX_VALUE + 1)));
    }
}
