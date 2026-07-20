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

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;

class DataAnnotationsTest {

    @Test
    void preservesExistingRepositoryAnnotationMetadata() {
        assertAnnotationMetadata(Data.Repository.class, RetentionPolicy.CLASS, ElementType.TYPE);
    }

    @Test
    void preservesExistingQueryAnnotationMetadata() throws NoSuchMethodException {
        assertAnnotationMetadata(Data.Query.class, RetentionPolicy.SOURCE, ElementType.METHOD);
        assertThat(Data.Query.class.getDeclaredMethod("value").getReturnType(), is((Object) String.class));
        assertThat(Data.Query.class.getAnnotation(Repeatable.class), nullValue());
    }

    private static void assertAnnotationMetadata(Class<? extends Annotation> annotationType,
                                                 RetentionPolicy expectedRetention,
                                                 ElementType... expectedTargets) {
        assertThat(annotationType.getAnnotation(Retention.class).value(), is(expectedRetention));
        assertThat(annotationType.getAnnotation(Target.class).value(), arrayContaining(expectedTargets));
    }
}
