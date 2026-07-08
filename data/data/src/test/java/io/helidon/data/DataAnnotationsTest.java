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
package io.helidon.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.sql.JDBCType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataAnnotationsTest {

    @Data.BeanMapper(value = RootBean.class, identity = "rootId")
    @Data.BeanMapper(value = ChildBean.class, prefix = "children", identity = "childId")
    private void repeatedBeanMappersCompile() {
    }

    @Data.BeanMappers({
            @Data.BeanMapper(value = RootBean.class, identity = "rootId"),
            @Data.BeanMapper(value = ChildBean.class, prefix = "children", identity = "childId")
    })
    private void explicitBeanMapperContainerCompiles() {
    }

    @Data.RowReducer(Reducer.class)
    private void rowReducerCompiles() {
    }

    @Data.Update("INSERT INTO EXAMPLE (NAME) VALUES (:name)")
    @Data.GeneratedKeys("ID")
    private long generatedKeysAnnotationsCompile(@Data.JdbcType(JDBCType.VARCHAR) String name) {
        return 0;
    }

    @Test
    void preservesExistingRepositoryAnnotationMetadata() {
        assertAnnotationMetadata(Data.Repository.class, RetentionPolicy.CLASS, ElementType.TYPE);
    }

    @Test
    void preservesExistingQueryAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.Query.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertEquals(String.class, Data.Query.class.getDeclaredMethod("value").getReturnType());
        assertNull(Data.Query.class.getAnnotation(Repeatable.class));
    }

    @Test
    void definesUpdateAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.Update.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertEquals(String.class, Data.Update.class.getDeclaredMethod("value").getReturnType());
        assertNull(Data.Update.class.getAnnotation(Repeatable.class));
    }

    @Test
    void definesGeneratedKeysAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.GeneratedKeys.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method value = Data.GeneratedKeys.class.getDeclaredMethod("value");
        assertEquals(String[].class, value.getReturnType());
        assertArrayEquals(new String[0], (String[]) value.getDefaultValue());
        assertNull(Data.GeneratedKeys.class.getAnnotation(Repeatable.class));
    }

    @Test
    void definesRepeatableBeanMapperMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.BeanMapper.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertAnnotationMetadata(Data.BeanMappers.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertEquals(Data.BeanMappers.class, Data.BeanMapper.class.getAnnotation(Repeatable.class).value());
        assertEquals(Class.class, Data.BeanMapper.class.getDeclaredMethod("value").getReturnType());
        assertEquals("", Data.BeanMapper.class.getDeclaredMethod("prefix").getDefaultValue());
        assertEquals("", Data.BeanMapper.class.getDeclaredMethod("identity").getDefaultValue());
        assertEquals(Data.BeanMapper[].class, Data.BeanMappers.class.getDeclaredMethod("value").getReturnType());
        assertNull(Data.BeanMappers.class.getAnnotation(Repeatable.class));
    }

    @Test
    void definesRowReducerMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.RowReducer.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertEquals(Class.class, Data.RowReducer.class.getDeclaredMethod("value").getReturnType());
    }

    @Test
    void definesRowMapperAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.RowMapper.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertEquals(Class.class, Data.RowMapper.class.getDeclaredMethod("value").getReturnType());
        assertNull(Data.RowMapper.class.getAnnotation(Repeatable.class));
    }

    @Test
    void definesJdbcTypeAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.JdbcType.class, RetentionPolicy.SOURCE, ElementType.PARAMETER);
        assertEquals(JDBCType.class, Data.JdbcType.class.getDeclaredMethod("value").getReturnType());
        assertNull(Data.JdbcType.class.getAnnotation(Repeatable.class));
    }

    private static void assertAnnotationMetadata(Class<? extends Annotation> annotationType,
                                                 RetentionPolicy expectedRetention,
                                                 ElementType... expectedTargets) {
        assertEquals(expectedRetention, annotationType.getAnnotation(Retention.class).value());
        assertArrayEquals(expectedTargets, annotationType.getAnnotation(Target.class).value());
    }

    private static final class RootBean {
    }

    private static final class ChildBean {
    }

    private static final class Reducer {
    }
}
