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
package io.helidon.data.jdbc.codegen;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.helidon.common.types.TypeName;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.fail;

class JdbcCodegenTypesTest {

    private static final Set<Field> TYPES = new HashSet<>();
    private static final Set<String> TO_CHECK = new HashSet<>();
    private static final Set<String> CHECKED = new HashSet<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    @BeforeAll
    static void before() {
        Field[] declaredFields = JdbcCodegenTypes.class.getDeclaredFields();
        for (Field declaredField : declaredFields) {
            if (declaredField.getType() == TypeName.class) {
                TYPES.add(declaredField);
            }
        }
        for (Field declaredField : TYPES) {
            String name = declaredField.getName();
            TO_CHECK.add(name);
            FIELDS.put(name, declaredField);
        }
    }

    @AfterAll
    static void after() {
        assertThat(TO_CHECK, empty());
    }

    @Test
    void allFieldsTest() {
        for (Field declaredField : TYPES) {
            String name = declaredField.getName();
            assertThat(name + " must be a TypeName",
                       declaredField.getType(),
                       CoreMatchers.sameInstance(TypeName.class));
            assertThat(name + " must be static", Modifier.isStatic(declaredField.getModifiers()), is(true));
            assertThat(name + " must be package local, not public",
                       Modifier.isPublic(declaredField.getModifiers()),
                       is(false));
            assertThat(name + " must be package local, not private",
                       Modifier.isPrivate(declaredField.getModifiers()),
                       is(false));
            assertThat(name + " must be package local, not protected",
                       Modifier.isProtected(declaredField.getModifiers()),
                       is(false));
            assertThat(name + " must be final", Modifier.isFinal(declaredField.getModifiers()), is(true));
        }
    }

    @Test
    void testDataTypes() {
        checkField("DATA_BIND", "io.helidon.data.Data.Bind");
        checkField("DATA_EXCEPTION", "io.helidon.data.DataException");
        checkField("DATA_GENERATED_KEYS", "io.helidon.data.Data.GeneratedKeys");
        checkField("DATA_GENERIC_REPOSITORY", "io.helidon.data.Data.GenericRepository");
        checkField("DATA_KEY", "io.helidon.data.Data.Key");
        checkField("DATA_KEYS", "io.helidon.data.Data.Keys");
        checkField("DATA_MAP", "io.helidon.data.Data.Map");
        checkField("DATA_MAPPER", "io.helidon.data.Data.Mapper");
        checkField("DATA_MAPS", "io.helidon.data.Data.Maps");
        checkField("DATA_MAP_WITH", "io.helidon.data.Data.MapWith");
        checkField("DATA_PAGE", "io.helidon.data.Page");
        checkField("DATA_PARAM", "io.helidon.data.Data.Param");
        checkField("DATA_PERSISTENCE_UNIT", "io.helidon.data.Data.PersistenceUnit");
        checkField("DATA_QUERY", "io.helidon.data.Data.Query");
        checkField("DATA_REDUCE_WITH", "io.helidon.data.Data.ReduceWith");
        checkField("DATA_SLICE", "io.helidon.data.Slice");
    }

    @Test
    void testJdbcTypes() {
        checkField("JDBC_BINDER", "io.helidon.data.jdbc.JdbcBinder");
        checkField("JDBC_OPERATIONS", "io.helidon.data.jdbc.JdbcOperations");
        checkField("JDBC_RESULT_SET_ROW_VIEW", "io.helidon.data.jdbc.JdbcResultSetRowView");
        checkField("JDBC_ROW_MAPPER", "io.helidon.data.jdbc.JdbcRowMapper");
        checkField("JDBC_ROW_REDUCER", "io.helidon.data.jdbc.JdbcRowReducer");
        checkField("JDBC_STATEMENT_PLAN", "io.helidon.data.jdbc.JdbcStatementPlan");
    }

    @Test
    void testServiceTypes() {
        checkField("SERVICE_NAMED", "io.helidon.service.registry.Service.Named");
        checkField("SERVICE_SINGLETON", "io.helidon.service.registry.Service.Singleton");
    }

    @Test
    void testJdkTypes() {
        checkField("STREAM", "java.util.stream.Stream");
    }

    private static void checkField(String name, String expectedType) {
        Field field = FIELDS.get(name);
        assertThat("Field " + name + " does not exist in the class", field, notNullValue());
        try {
            TO_CHECK.remove(name);
            if (CHECKED.add(name)) {
                Class<?> actualType = loadType(expectedType);
                TypeName value = (TypeName) field.get(null);
                assertThat("Field " + name, value.fqName(), is(actualType.getCanonicalName()));
            } else {
                fail("Field " + name + " is checked more than once.class");
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Class<?> loadType(String canonicalName) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return Class.forName(binaryName(canonicalName), false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Expected type is not available: " + canonicalName, e);
        }
    }

    private static String binaryName(String canonicalName) {
        if (canonicalName.startsWith("io.helidon.data.Data.")) {
            return "io.helidon.data.Data$" + canonicalName.substring("io.helidon.data.Data.".length());
        }
        if (canonicalName.startsWith("io.helidon.service.registry.Service.")) {
            return "io.helidon.service.registry.Service$"
                    + canonicalName.substring("io.helidon.service.registry.Service.".length());
        }
        return canonicalName;
    }
}
