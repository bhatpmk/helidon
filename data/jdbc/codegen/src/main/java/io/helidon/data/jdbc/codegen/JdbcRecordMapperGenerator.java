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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;

/**
 * Generates direct record canonical-constructor row mappers.
 */
final class JdbcRecordMapperGenerator {
    private JdbcRecordMapperGenerator() {
    }

    static boolean canGenerate(TypedElementInfo method,
                               TypeName mappedType,
                               List<String> projectionAliases,
                               CodegenContext context) {
        TypeInfo recordInfo = context.typeInfo(mappedType.genericTypeName())
                .orElse(null);
        if (recordInfo == null || recordInfo.kind() != ElementKind.RECORD) {
            return false;
        }
        validateAccessibility(method, recordInfo);
        Set<String> aliases = normalizedAliases(projectionAliases);
        return components(recordInfo).stream()
                .allMatch(component -> supportedComponent(component.typeName())
                        && aliases.contains(component.elementName().toLowerCase(Locale.ROOT)));
    }

    static void generate(JdbcMethodPlan plan,
                         String fieldName,
                         ClassModel.Builder classModel,
                         CodegenContext context) {
        TypeInfo recordInfo = context.typeInfo(plan.mappedType().genericTypeName())
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Record type information is unavailable: "
                                                                  + plan.mappedType().resolvedName()));
        validateAccessibility(plan.method(), recordInfo);
        List<TypedElementInfo> components = components(recordInfo);
        Set<String> aliases = normalizedAliases(plan.aliases());
        validateComponents(plan, components, aliases);

        TypeName mapperType = TypeName.builder(JdbcCodegenTypes.ROW_MAPPER)
                .addTypeArgument(plan.mappedType())
                .build();
        classModel.addField(field -> {
            field.name(fieldName)
                    .type(mapperType)
                    .isStatic(true)
                    .isFinal(true)
                    .addContent("row -> new ")
                    .addContent(plan.mappedType())
                    .addContent("(");
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) {
                    field.addContent(", ");
                }
                TypedElementInfo component = components.get(i);
                TypeName optionalType = JdbcMethodPlan.optionalScalarType(component.typeName());
                field.addContent("row.")
                        .addContent(optionalType == null ? "required(" : "optional(")
                        .addContentLiteral(component.elementName())
                        .addContent(", ")
                        .addContent((optionalType == null ? component.typeName() : optionalType).boxed())
                        .addContent(".class)");
            }
            field.addContent(")");
        });
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

    private static List<TypedElementInfo> components(TypeInfo recordInfo) {
        return recordInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
    }

    private static Set<String> normalizedAliases(List<String> projectionAliases) {
        Set<String> aliases = new HashSet<>();
        projectionAliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).forEach(aliases::add);
        return aliases;
    }

    private static void validateComponents(JdbcMethodPlan plan,
                                           List<TypedElementInfo> components,
                                           Set<String> aliases) {
        for (TypedElementInfo component : components) {
            if (!supportedComponent(component.typeName())) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Unsupported record component type " + component.typeName().resolvedName()
                                                     + " for " + component.elementName());
            }
            if (!aliases.contains(component.elementName().toLowerCase(Locale.ROOT))) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "SQL projection is missing record component alias: "
                                                     + component.elementName());
            }
        }
    }

    private static boolean supportedComponent(TypeName type) {
        return JdbcMethodPlan.isScalar(type) || JdbcMethodPlan.optionalScalarType(type) != null;
    }
}
