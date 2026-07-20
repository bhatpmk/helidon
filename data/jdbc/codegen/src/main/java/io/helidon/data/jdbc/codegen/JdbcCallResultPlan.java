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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.helidon.codegen.CodegenContext;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

/**
 * Validated mapping from scalar callable outputs to a detached repository return value.
 */
final class JdbcCallResultPlan {
    private static final JdbcCallResultPlan NONE =
            new JdbcCallResultPlan(Kind.NONE, TypeNames.PRIMITIVE_VOID, List.of());

    private final Kind kind;
    private final TypeName resultType;
    private final List<Component> components;

    private JdbcCallResultPlan(Kind kind, TypeName resultType, List<Component> components) {
        this.kind = kind;
        this.resultType = resultType;
        this.components = components;
    }

    static JdbcCallResultPlan create(TypedElementInfo method,
                                     CodegenContext context,
                                     JdbcCallParameterPlan call,
                                     boolean callback) {
        if (callback) {
            return NONE;
        }

        List<JdbcCallParameterPlan.Parameter> outputs = call.parameters()
                .stream()
                .filter(JdbcCallParameterPlan.Parameter::output)
                .toList();
        TypeName returnType = method.typeName();
        if (returnType.equals(TypeNames.PRIMITIVE_VOID)) {
            if (!outputs.isEmpty()) {
                throw JdbcMethodPlan.failure(method,
                                             "A void JDBC call cannot declare outputs without "
                                                     + "JdbcResultRequest.Call");
            }
            return NONE;
        }
        if (outputs.isEmpty()) {
            throw JdbcMethodPlan.failure(method, "A detached JDBC call return requires at least one scalar output");
        }
        if (outputs.stream().anyMatch(parameter -> parameter.direction() == JdbcCallParameterPlan.Direction.CURSOR)) {
            throw JdbcMethodPlan.failure(method,
                                         "A JDBC call with cursor outputs requires JdbcResultRequest.Call or CallWith");
        }

        if (returnType.isOptional()) {
            TypeName valueType = singleOptionalType(method, returnType);
            JdbcCallParameterPlan.Parameter output = singleOutput(method, outputs, returnType);
            requireCompatible(method, output, valueType, "detached optional return");
            return new JdbcCallResultPlan(Kind.OPTIONAL,
                                          returnType,
                                          List.of(new Component(output.name(), valueType, true)));
        }
        if (JdbcMethodPlan.isScalar(returnType)) {
            JdbcCallParameterPlan.Parameter output = singleOutput(method, outputs, returnType);
            requireCompatible(method, output, returnType, "detached scalar return");
            return new JdbcCallResultPlan(Kind.SCALAR,
                                          returnType,
                                          List.of(new Component(output.name(), returnType, false)));
        }

        TypeInfo resultInfo = context.typeInfo(returnType.genericTypeName()).orElse(null);
        if (resultInfo == null || resultInfo.kind() != ElementKind.RECORD) {
            throw JdbcMethodPlan.failure(method,
                                         "A detached JDBC call must return a scalar, Optional scalar, or record; "
                                                 + "use JdbcResultRequest.CallWith for custom result construction");
        }
        validateAccessibility(method, resultInfo);

        Map<String, JdbcCallParameterPlan.Parameter> byName = new HashMap<>();
        outputs.forEach(output -> byName.put(output.name(), output));
        List<TypedElementInfo> recordComponents = resultInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
        if (recordComponents.size() != outputs.size()) {
            throw JdbcMethodPlan.failure(method,
                                         "Detached JDBC call record components must exactly match all scalar outputs");
        }

        List<Component> components = recordComponents.stream()
                .map(component -> component(method, component, byName))
                .toList();
        return new JdbcCallResultPlan(Kind.RECORD, returnType, components);
    }

    private static Component component(TypedElementInfo method,
                                       TypedElementInfo component,
                                       Map<String, JdbcCallParameterPlan.Parameter> byName) {
        JdbcCallParameterPlan.Parameter output = byName.get(component.elementName());
        if (output == null) {
            throw JdbcMethodPlan.failure(method,
                                         "Detached JDBC call record component has no matching output: "
                                                 + component.elementName());
        }
        TypeName optionalType = JdbcMethodPlan.optionalScalarType(component.typeName());
        TypeName valueType = optionalType == null ? component.typeName() : optionalType;
        if (!JdbcMethodPlan.isScalar(valueType)) {
            throw JdbcMethodPlan.failure(method,
                                         "Unsupported detached JDBC call record component type "
                                                 + component.typeName().resolvedName() + " for "
                                                 + component.elementName());
        }
        requireCompatible(method, output, valueType, "record component '" + component.elementName() + "'");
        return new Component(component.elementName(), valueType, optionalType != null);
    }

    private static JdbcCallParameterPlan.Parameter singleOutput(TypedElementInfo method,
                                                                List<JdbcCallParameterPlan.Parameter> outputs,
                                                                TypeName returnType) {
        if (outputs.size() != 1) {
            throw JdbcMethodPlan.failure(method,
                                         "Detached JDBC call return " + returnType.resolvedName()
                                                 + " requires exactly one scalar output; use a record or "
                                                 + "JdbcResultRequest.CallWith");
        }
        return outputs.getFirst();
    }

    private static TypeName singleOptionalType(TypedElementInfo method, TypeName returnType) {
        TypeName valueType = JdbcMethodPlan.optionalScalarType(returnType);
        if (valueType == null) {
            throw JdbcMethodPlan.failure(method,
                                         "Detached JDBC call Optional return requires one supported scalar type");
        }
        return valueType;
    }

    private static void requireCompatible(TypedElementInfo method,
                                          JdbcCallParameterPlan.Parameter output,
                                          TypeName requestedType,
                                          String description) {
        if (!output.javaType().boxed().equals(requestedType.boxed())) {
            throw JdbcMethodPlan.failure(method,
                                         "JDBC call output '" + output.name() + "' is declared as "
                                                 + output.javaType().resolvedName() + " but " + description
                                                 + " uses " + requestedType.resolvedName());
        }
    }

    private static void validateAccessibility(TypedElementInfo method, TypeInfo recordInfo) {
        boolean samePackage = method.enclosingType()
                .map(TypeName::packageName)
                .filter(recordInfo.typeName().packageName()::equals)
                .isPresent();
        if (recordInfo.accessModifier() != AccessModifier.PUBLIC
                && !(samePackage && (recordInfo.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || recordInfo.accessModifier() == AccessModifier.PROTECTED))) {
            throw JdbcMethodPlan.failure(method,
                                         "Record type is not accessible to generated code: "
                                                 + recordInfo.typeName().resolvedName());
        }
    }

    Kind kind() {
        return kind;
    }

    TypeName resultType() {
        return resultType;
    }

    List<Component> components() {
        return components;
    }

    boolean detached() {
        return kind != Kind.NONE;
    }

    enum Kind {
        NONE,
        SCALAR,
        OPTIONAL,
        RECORD
    }

    record Component(String name, TypeName valueType, boolean optional) {
    }
}
