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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;

final class JdbcTypes {

    static final TypeName PU_NAME_ANNOTATION = TypeName.create("io.helidon.data.Data.PersistenceUnit");
    static final TypeName QUERY_ANNOTATION = TypeName.create("io.helidon.data.Data.Query");
    static final TypeName CALL_ANNOTATION = TypeName.create("io.helidon.data.Data.Call");
    static final TypeName OUT_ANNOTATION = TypeName.create("io.helidon.data.Data.Out");
    static final TypeName OUT_LIST_ANNOTATION = TypeName.create("io.helidon.data.Data.Out.List");
    static final TypeName IN_OUT_ANNOTATION = TypeName.create("io.helidon.data.Data.InOut");
    static final TypeName OUT_CURSOR_ANNOTATION = TypeName.create("io.helidon.data.Data.OutCursor");
    static final TypeName OUT_CURSOR_LIST_ANNOTATION = TypeName.create("io.helidon.data.Data.OutCursor.List");
    static final TypeName GENERIC_REPOSITORY = TypeName.create("io.helidon.data.Data.GenericRepository");
    static final TypeName CLIENT = TypeName.create("io.helidon.data.jdbc.JdbcClient");
    static final TypeName PARAMETER = TypeName.create("io.helidon.data.jdbc.JdbcParameter");

    static final TypeName NUMBER = TypeName.create(Number.class);
    static final TypeName BIG_INTEGER = TypeName.create(BigInteger.class);
    static final TypeName BIG_DECIMAL = TypeName.create(BigDecimal.class);
    static final TypeName LOCAL_DATE = TypeName.create(LocalDate.class);
    static final TypeName LOCAL_TIME = TypeName.create(LocalTime.class);
    static final TypeName LOCAL_DATE_TIME = TypeName.create(LocalDateTime.class);

    static final Annotation INJECTION_SINGLETON = Annotation.create(TypeName.create(
            "io.helidon.service.registry.Service.Singleton"));

    private JdbcTypes() {
    }

    static boolean isScalar(TypeName typeName) {
        TypeName type = wrapper(typeName);
        return type.equals(TypeNames.BOXED_BOOLEAN)
                || type.equals(TypeNames.BOXED_BYTE)
                || type.equals(TypeNames.BOXED_SHORT)
                || type.equals(TypeNames.BOXED_INT)
                || type.equals(TypeNames.BOXED_LONG)
                || type.equals(TypeNames.BOXED_FLOAT)
                || type.equals(TypeNames.BOXED_DOUBLE)
                || type.equals(TypeNames.STRING)
                || type.equals(NUMBER)
                || type.equals(BIG_INTEGER)
                || type.equals(BIG_DECIMAL)
                || type.equals(LOCAL_DATE)
                || type.equals(LOCAL_TIME)
                || type.equals(LOCAL_DATE_TIME);
    }

    static TypeName wrapper(TypeName typeName) {
        if (typeName.equals(TypeNames.PRIMITIVE_BOOLEAN)) {
            return TypeNames.BOXED_BOOLEAN;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_BYTE)) {
            return TypeNames.BOXED_BYTE;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_SHORT)) {
            return TypeNames.BOXED_SHORT;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_INT)) {
            return TypeNames.BOXED_INT;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_LONG)) {
            return TypeNames.BOXED_LONG;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_FLOAT)) {
            return TypeNames.BOXED_FLOAT;
        }
        if (typeName.equals(TypeNames.PRIMITIVE_DOUBLE)) {
            return TypeNames.BOXED_DOUBLE;
        }
        return typeName;
    }
}
