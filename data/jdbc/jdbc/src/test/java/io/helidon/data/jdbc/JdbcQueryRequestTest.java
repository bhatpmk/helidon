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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcQueryRequestTest {

    @Test
    void defaultRequestIsSharedAndPreservesDriverSettings() {
        assertSame(JdbcQueryRequest.defaults(), JdbcQueryRequest.defaults());
        assertEmpty(JdbcQueryRequest.defaults().options());
    }

    @Test
    void directFactoriesUseDriverDefaultsAndPermitCallbackVariance() {
        List<Object> values = new ArrayList<>();
        Consumer<Object> consumer = values::add;
        JdbcQueryRequest.VisitAll<String> each = JdbcQueryRequest.visitAll(consumer);

        Predicate<CharSequence> whileNonEmpty = value -> !value.isEmpty();
        JdbcQueryRequest.VisitWhile<String> eachWhile = JdbcQueryRequest.visitWhile(whileNonEmpty);

        each.accept("first");
        each.accept("second");
        assertEquals(List.of("first", "second"), values);
        assertTrue(eachWhile.test("value"));
        assertFalse(eachWhile.test(""));
        assertEmpty(each.options());
        assertEmpty(eachWhile.options());
    }

    @Test
    void configuredBuilderCreatesAnImmutableRequestSnapshot() {
        List<String> values = new ArrayList<>();
        JdbcQueryRequest.VisitAll<String> request = JdbcQueryRequest.<String>builder()
                .fetchSize(25)
                .queryTimeout(Duration.ofSeconds(3))
                .maxRows(200)
                .poolableHint(true)
                .visitAll(values::add);

        request.accept("one");
        request.accept("two");

        assertEquals(List.of("one", "two"), values);
        assertEquals(25, request.options().fetchSize());
        assertEquals(Duration.ofSeconds(3), request.options().queryTimeout());
        assertEquals(200, request.options().maxRows());
        assertTrue(request.options().poolableHint());
    }

    @Test
    void builderCreatesAConfiguredRegularRequest() {
        JdbcQueryRequest request = JdbcQueryRequest.builder()
                .fetchSize(25)
                .queryTimeout(Duration.ofSeconds(3))
                .maxRows(200)
                .poolableHint(false)
                .build();

        assertEquals(25, request.options().fetchSize());
        assertEquals(Duration.ofSeconds(3), request.options().queryTimeout());
        assertEquals(200, request.options().maxRows());
        assertFalse(request.options().poolableHint());
    }

    @Test
    void builderRejectsEveryOperationAfterCreatingARequest() {
        JdbcQueryRequest.Builder<String> builder = JdbcQueryRequest.builder();
        JdbcQueryRequest.VisitAll<String> request = builder.fetchSize(4).visitAll(ignored -> { });

        assertThrows(IllegalStateException.class, () -> builder.fetchSize(5));
        assertThrows(IllegalStateException.class, () -> builder.queryTimeout(Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> builder.maxRows(5));
        assertThrows(IllegalStateException.class, () -> builder.poolableHint(false));
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder.visitAll(ignored -> { }));
        assertThrows(IllegalStateException.class, () -> builder.visitWhile(ignored -> true));

        request.accept("still immutable and usable");
        assertEquals(4, request.options().fetchSize());
    }

    @Test
    void eitherRequestVariantCompletesTheSameBuilder() {
        JdbcQueryRequest.Builder<String> builder = JdbcQueryRequest.builder();
        JdbcQueryRequest.VisitWhile<String> request = builder.visitWhile(ignored -> false);

        assertFalse(request.test("value"));
        assertThrows(IllegalStateException.class, () -> builder.visitAll(ignored -> { }));
        assertThrows(IllegalStateException.class, () -> builder.visitWhile(ignored -> true));
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void regularRequestCompletesTheSameBuilder() {
        JdbcQueryRequest.Builder<String> builder = JdbcQueryRequest.builder();
        JdbcQueryRequest request = builder.fetchSize(9).build();

        assertEquals(9, request.options().fetchSize());
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder.visitAll(ignored -> { }));
        assertThrows(IllegalStateException.class, () -> builder.visitWhile(ignored -> true));
        assertThrows(IllegalStateException.class, () -> builder.fetchSize(10));
    }

    @Test
    void nullCallbackIsRejectedWithoutCompletingTheBuilder() {
        assertThrows(NullPointerException.class, () -> JdbcQueryRequest.visitAll(null));
        assertThrows(NullPointerException.class, () -> JdbcQueryRequest.visitWhile(null));

        JdbcQueryRequest.Builder<String> builder = JdbcQueryRequest.builder();
        assertThrows(NullPointerException.class, () -> builder.visitAll(null));
        builder.visitAll(ignored -> { });
    }

    @Test
    void builderUsesStatementOptionValidation() {
        assertThrows(IllegalArgumentException.class, () -> JdbcQueryRequest.builder().fetchSize(-1));
        assertThrows(IllegalArgumentException.class, () -> JdbcQueryRequest.builder().maxRows(-1));
        assertThrows(IllegalArgumentException.class,
                     () -> JdbcQueryRequest.builder().queryTimeout(Duration.ofMillis(1)));
    }

    private static void assertEmpty(JdbcStatementOptions options) {
        assertNull(options.fetchSize());
        assertNull(options.queryTimeout());
        assertNull(options.maxRows());
        assertNull(options.poolableHint());
    }
}
