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

import io.helidon.common.types.Annotation;
import io.helidon.common.types.TypedElementInfo;

/**
 * Compile-time input-binding metadata shared by ordinary statements and callable inputs.
 */
final class JdbcBindTypePlan {
    private static final JdbcBindTypePlan INFERRED = new JdbcBindTypePlan(Kind.INFERRED, "");

    private final Kind kind;
    private final String standardType;

    private JdbcBindTypePlan(Kind kind, String standardType) {
        this.kind = kind;
        this.standardType = standardType;
    }

    /**
     * Validates and resolves the optional type selection on one bindable parameter.
     *
     * @param parameter repository method parameter
     * @param method enclosing repository method used for diagnostics
     * @return immutable binding-type plan
     */
    static JdbcBindTypePlan create(TypedElementInfo parameter, TypedElementInfo method) {
        Annotation standard = parameter.findAnnotation(JdbcCodegenTypes.JDBC_BIND_TYPE).orElse(null);
        if (standard != null) {
            String type = standard.stringValue()
                    .orElseThrow(() -> JdbcMethodPlan.failure(method, "@Jdbc.BindType value is missing"));
            // NULL is not a non-null input type, and REF_CURSOR identifies an output resource rather than an input value.
            if ("NULL".equals(type) || "REF_CURSOR".equals(type)) {
                throw JdbcMethodPlan.failure(method, "@Jdbc.BindType does not support JDBCType." + type
                        + ": " + parameter.elementName());
            }
            return new JdbcBindTypePlan(Kind.STANDARD, type);
        }
        return INFERRED;
    }

    /**
     * Tests whether either explicit binding annotation is present.
     *
     * @param parameter repository method parameter
     * @return true when the parameter declares binding metadata
     */
    static boolean declared(TypedElementInfo parameter) {
        return parameter.hasAnnotation(JdbcCodegenTypes.JDBC_BIND_TYPE);
    }

    /**
     * Returns the selected binding mode.
     *
     * @return binding mode
     */
    Kind kind() {
        return kind;
    }

    /**
     * Returns the standard JDBC enum constant name used in generated source.
     *
     * @return enum constant name, or an empty string for a non-standard binding mode
     */
    String standardType() {
        return standardType;
    }

    /**
     * Input binding modes understood by generated repositories.
     */
    enum Kind {
        /** Driver-inferred binding. */
        INFERRED,
        /** Standard {@code java.sql.JDBCType} binding. */
        STANDARD
    }
}
