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

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.emptyArray;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcAnnotationsTest {

    @Jdbc.Statement("SELECT ID AS id FROM EXAMPLE")
    @Jdbc.Execution(Jdbc.ExecutionType.QUERY)
    @Jdbc.IdentityReducer(identityPaths = {"id", "children.id"})
    private void queryAnnotationsCompile() {
    }

    @Jdbc.Statement("INSERT INTO EXAMPLE(NAME) VALUES (:name)")
    @Jdbc.GeneratedKeys("ID")
    @Jdbc.RowMapper()
    private long generatedKeyAnnotationsCompile() {
        return 0;
    }

    @Jdbc.RowReducer(Reducer.class)
    private String rowReducerCompiles() {
        return "";
    }

    @Jdbc.Statement("UPDATE EXAMPLE SET NAME = :name, STATUS = :status")
    @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
    private long bindingAnnotationsCompile(@Jdbc.BindType(java.sql.JDBCType.VARCHAR) String name,
                                           String status) {
        return 0;
    }

    @Jdbc.Statement("{call PROCESS(:input, :state, :rows, :status)}")
    @Jdbc.Execution(Jdbc.ExecutionType.CALL)
    @Jdbc.OutParameter(name = "rows",
                       jdbcType = java.sql.Types.REF_CURSOR,
                       kind = Jdbc.OutputKind.CURSOR)
    @Jdbc.OutParameter(name = "status", jdbcType = java.sql.Types.VARCHAR, javaType = String.class)
    private void callAnnotationsCompile(@Jdbc.InParameter(name = "input")
                                        @Jdbc.BindType(java.sql.JDBCType.VARCHAR) String input,
                                        @Jdbc.InOutParameter(name = "state", jdbcType = java.sql.Types.INTEGER)
                                        int state) {
    }

    @Jdbc.Statement("{? = call TOTAL(?)}")
    @Jdbc.ReturnParameter(name = "result", jdbcType = java.sql.Types.BIGINT, javaType = Long.class)
    private void functionAnnotationsCompile(@Jdbc.InParameter(index = 2) String group) {
    }

    @Test
    void keepsDriverInferenceSentinelOutOfPublicApi() throws NoSuchFieldException {
        assertFalse(Modifier.isPublic(Jdbc.class.getDeclaredField("INFERRED_TYPE").getModifiers()));
    }

    @Test
    void definesStatementMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.Statement.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertThat(Jdbc.Statement.class.getDeclaredMethod("value").getReturnType(), is((Object) String.class));
        assertThat(Jdbc.Statement.class.getAnnotation(Repeatable.class), nullValue());
    }

    @Test
    void definesExecutionMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.Execution.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method value = Jdbc.Execution.class.getDeclaredMethod("value");
        assertThat(value.getReturnType(), is((Object) Jdbc.ExecutionType.class));
        assertThat(value.getDefaultValue(), nullValue());
        assertThat(EnumSet.allOf(Jdbc.ExecutionType.class),
                   is(EnumSet.of(Jdbc.ExecutionType.QUERY,
                                 Jdbc.ExecutionType.UPDATE,
                                 Jdbc.ExecutionType.CALL)));
        assertThat(EnumSet.allOf(Jdbc.OutputKind.class),
                   is(EnumSet.of(Jdbc.OutputKind.SCALAR, Jdbc.OutputKind.CURSOR)));
    }

    @Test
    void definesGeneratedKeysMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.GeneratedKeys.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method value = Jdbc.GeneratedKeys.class.getDeclaredMethod("value");
        assertThat(value.getReturnType(), is((Object) String[].class));
        assertThat((String[]) value.getDefaultValue(), emptyArray());
        assertThat(Jdbc.GeneratedKeys.class.getAnnotation(Repeatable.class), nullValue());
    }

    @Test
    void definesIdentityReducerMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.IdentityReducer.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method identityPaths = Jdbc.IdentityReducer.class.getDeclaredMethod("identityPaths");
        assertThat(identityPaths.getReturnType(), is((Object) String[].class));
        assertThat(identityPaths.getDefaultValue(), nullValue());
    }

    @Test
    void definesRowMapperMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.RowMapper.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method value = Jdbc.RowMapper.class.getDeclaredMethod("value");
        assertThat(value.getReturnType(), is((Object) Class.class));
        assertThat(value.getDefaultValue(), is((Object) Void.class));
    }

    @Test
    void definesRowReducerMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.RowReducer.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        Method value = Jdbc.RowReducer.class.getDeclaredMethod("value");
        assertThat(value.getReturnType(), is((Object) Class.class));
        assertThat(value.getDefaultValue(), nullValue());
    }

    @Test
    void definesBindingTypeMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.BindType.class, RetentionPolicy.SOURCE, ElementType.PARAMETER);
        Method standard = Jdbc.BindType.class.getDeclaredMethod("value");
        assertThat(standard.getReturnType(), is((Object) java.sql.JDBCType.class));
        assertThat(standard.getDefaultValue(), nullValue());
    }

    @Test
    void definesCallableParameterMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Jdbc.InParameter.class, RetentionPolicy.SOURCE, ElementType.PARAMETER);
        assertThat(Jdbc.InParameter.class.getDeclaredMethod("name").getDefaultValue(), is((Object) ""));
        assertThat(Jdbc.InParameter.class.getDeclaredMethod("index").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));

        assertAnnotationMetadata(Jdbc.InOutParameter.class, RetentionPolicy.SOURCE, ElementType.PARAMETER);
        assertThat(Jdbc.InOutParameter.class.getDeclaredMethod("name").getDefaultValue(), is((Object) ""));
        assertThat(Jdbc.InOutParameter.class.getDeclaredMethod("index").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));
        assertThat(Jdbc.InOutParameter.class.getDeclaredMethod("jdbcType").getDefaultValue(), nullValue());
        assertThat(Jdbc.InOutParameter.class.getDeclaredMethod("typeName").getDefaultValue(), is((Object) ""));
        assertThat(Jdbc.InOutParameter.class.getDeclaredMethod("scale").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));

        assertAnnotationMetadata(Jdbc.OutParameter.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertThat(Jdbc.OutParameter.class.getAnnotation(Repeatable.class).value(),
                   is((Object) Jdbc.OutParameters.class));
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("name").getDefaultValue(), nullValue());
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("index").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("javaType").getDefaultValue(), is((Object) Void.class));
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("typeName").getDefaultValue(), is((Object) ""));
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("scale").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));
        assertThat(Jdbc.OutParameter.class.getDeclaredMethod("kind").getDefaultValue(),
                   is((Object) Jdbc.OutputKind.SCALAR));

        assertAnnotationMetadata(Jdbc.OutParameters.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertThat(Jdbc.OutParameters.class.getDeclaredMethod("value").getReturnType(),
                   is((Object) Jdbc.OutParameter[].class));

        assertAnnotationMetadata(Jdbc.ReturnParameter.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("name").getDefaultValue(), nullValue());
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("jdbcType").getDefaultValue(), nullValue());
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("javaType").getDefaultValue(), nullValue());
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("typeName").getDefaultValue(), is((Object) ""));
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("scale").getDefaultValue(),
                   is((Object) Integer.valueOf(-1)));
        assertThat(Jdbc.ReturnParameter.class.getDeclaredMethod("kind").getDefaultValue(),
                   is((Object) Jdbc.OutputKind.SCALAR));
    }

    private static void assertAnnotationMetadata(Class<? extends Annotation> annotationType,
                                                 RetentionPolicy expectedRetention,
                                                 ElementType... expectedTargets) {
        assertThat(annotationType.getAnnotation(Retention.class).value(), is(expectedRetention));
        assertThat(annotationType.getAnnotation(Target.class).value(), arrayContaining(expectedTargets));
    }

    private static final class Reducer implements JdbcClient.RowReducer<String> {
        @Override
        public void accept(JdbcClient.Row row) {
        }

        @Override
        public String finish() {
            return "";
        }
    }
}
