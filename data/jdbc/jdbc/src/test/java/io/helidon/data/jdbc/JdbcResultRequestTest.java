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

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcResultRequestTest {

    @Test
    void visitAllRequestForwardsRowsToItsConsumer() {
        List<String> values = new ArrayList<>();
        JdbcResultRequest.VisitAll<String> request = JdbcResultRequest.visitAll(values::add);

        request.accept("one");
        request.accept("two");

        assertEquals(List.of("one", "two"), values);
    }

    @Test
    void visitWhileRequestReturnsPredicateDecision() {
        JdbcResultRequest.VisitWhile<String> request = JdbcResultRequest.visitWhile(value -> !value.isEmpty());

        assertTrue(request.test("value"));
        assertFalse(request.test(""));
    }

    @Test
    void requestsRejectNullCallbacks() {
        assertThrows(NullPointerException.class, () -> JdbcResultRequest.visitAll(null));
        assertThrows(NullPointerException.class, () -> JdbcResultRequest.visitWhile(null));
        assertThrows(NullPointerException.class, () -> JdbcResultRequest.visitAll(ignored -> { }).accept(null));
        assertThrows(NullPointerException.class, () -> JdbcResultRequest.visitWhile(ignored -> true).test(null));
    }

    @Test
    void requestsAreImmutableAndCanBeUsedForMultipleExecutions() {
        List<String> values = new ArrayList<>();
        JdbcResultRequest.VisitAll<String> request = JdbcResultRequest.visitAll(values::add);

        request.accept("first");
        request.accept("second");

        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void optionsCreateANewImmutableCallbackRequest() {
        JdbcResultRequest.VisitAll<String> original = JdbcResultRequest.visitAll(ignored -> { });
        JdbcResultRequest.VisitAll<String> configured = original.withOptions(JdbcStatementOptions.builder()
                                                                                   .fetchSize(25)
                                                                                   .queryTimeout(Duration.ofSeconds(3))
                                                                                   .build());

        assertThat(original.options().fetchSize(), nullValue());
        assertThat(configured.options().fetchSize(), is(25));
        assertThat(configured.options().queryTimeout(), is(Duration.ofSeconds(3)));
        assertThrows(NullPointerException.class, () -> original.withOptions(null));
    }

    @Test
    void callableRequestsForwardScopesAndRemainImmutable() {
        JdbcClient.CallScope scope = new TestCallScope();
        List<JdbcClient.CallScope> consumed = new ArrayList<>();
        JdbcResultRequest.Call use = JdbcResultRequest.call(call -> {
            consumed.add(call);
        });
        JdbcResultRequest.CallWith<String> with = JdbcResultRequest.call(call -> call == scope ? "result" : "wrong");

        use.accept(scope);

        assertEquals(List.of(scope), consumed);
        assertEquals("result", with.apply(scope));
        assertThrows(NullPointerException.class, () -> JdbcResultRequest.call((JdbcResultRequest.CallConsumer) null));
        assertThrows(NullPointerException.class,
                     () -> JdbcResultRequest.call((JdbcResultRequest.CallFunction<String>) null));
        assertThrows(NullPointerException.class, () -> use.accept(null));
        assertThrows(NullPointerException.class, () -> with.apply(null));
        assertThrows(NullPointerException.class,
                     () -> JdbcResultRequest.call(call -> (String) null).apply(scope));
    }

    @Test
    void callableOptionsCreateNewRequests() {
        JdbcResultRequest.Call original = JdbcResultRequest.call(call -> { });
        JdbcResultRequest.Call configured = original.withOptions(JdbcStatementOptions.builder()
                                                                            .fetchSize(17)
                                                                            .build());
        JdbcResultRequest.CallWith<String> with = JdbcResultRequest.call(call -> "detached");
        JdbcResultRequest.CallWith<String> configuredWith = with.withOptions(JdbcStatementOptions.builder()
                                                                                     .maxRows(29)
                                                                                     .build());

        assertThat(original.options().fetchSize(), nullValue());
        assertThat(configured.options().fetchSize(), is(17));
        assertThat(with.options().maxRows(), nullValue());
        assertThat(configuredWith.options().maxRows(), is(29L));
        assertThrows(NullPointerException.class, () -> original.withOptions(null));
        assertThrows(NullPointerException.class, () -> with.withOptions(null));
    }

    private static final class TestCallScope implements JdbcClient.CallScope {
        @Override
        public JdbcClient.CallResults results() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JdbcClient.CallOutputs outputs() {
            throw new UnsupportedOperationException();
        }
    }
}
