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

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JdbcClientApiTest {

    @Test
    void exposesOnlyApprovedTopLevelApiTypes() {
        Set<Class<?>> publicTypes = Arrays.stream(new Class<?>[] {
                        JdbcClient.class,
                        Jdbc.class,
                        JdbcCall.class,
                        JdbcResultRequest.class,
                        JdbcStatementOptions.class,
                        JdbcClientImpl.class,
                        JdbcStatement.class,
                        JdbcRows.class,
                        JdbcRunner.class,
                        JdbcQueryHandler.class,
                        JdbcUpdateHandler.class,
                        JdbcCallHandler.class,
                        JdbcRow.class,
                        JdbcColumnLayout.class,
                        JdbcConnectionLease.class,
                        JdbcOperation.class,
                        JdbcPreparationPlan.class,
                        JdbcExceptionTranslator.class,
                        JdbcPersistenceUnitConfig.class
                })
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .collect(Collectors.toSet());

        assertThat(publicTypes,
                   is(Set.of(JdbcClient.class,
                             Jdbc.class,
                             JdbcCall.class,
                             JdbcResultRequest.class,
                             JdbcStatementOptions.class,
                             JdbcPersistenceUnitConfig.class)));
        assertThat(JdbcClient.class.isInterface(), is(true));
        assertThat(Arrays.stream(JdbcClient.Row.class.getDeclaredMethods())
                           .map(java.lang.reflect.Method::getName)
                           .collect(Collectors.toSet()),
                   is(Set.of("optional", "required")));
    }
}
