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
import java.util.Locale;
import java.util.Map;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypedElementInfo;

/**
 * Generates direct mutable-bean construction and setter calls without reflection.
 */
final class JdbcBeanMapperGenerator {
    private JdbcBeanMapperGenerator() {
    }

    static void generate(JdbcMethodPlan plan, ClassModel.Builder classModel, CodegenContext context) {
        JdbcMethodPlan.BeanMapping mapping = plan.beanMappings().getFirst();
        if (!mapping.type().genericTypeName().equals(plan.mappedType().genericTypeName())) {
            throw JdbcMethodPlan.failure(plan.method(), "@Data.BeanMapper type must equal the mapped result type");
        }
        TypeInfo beanInfo = context.typeInfo(mapping.type().genericTypeName())
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Bean type information is unavailable: "
                                                                  + mapping.type().resolvedName()));
        validateConstructor(plan, beanInfo);
        Map<String, TypedElementInfo> setters = setters(plan, beanInfo);
        if (plan.aliases().isEmpty()) {
            throw JdbcMethodPlan.failure(plan.method(), "Bean mapping requires explicit SQL projection aliases");
        }

        TypeName mapperType = TypeName.builder(JdbcCodegenTypes.ROW_MAPPER)
                .addTypeArgument(plan.mappedType())
                .build();
        classModel.addField(field -> {
            field.name(plan.mapperFieldName())
                    .type(mapperType)
                    .isStatic(true)
                    .isFinal(true)
                    .addContentLine("row -> {")
                    .addContent("var value = new ")
                    .addContent(plan.mappedType())
                    .addContentLine("();");
            for (String alias : plan.aliases()) {
                if (alias.indexOf('.') >= 0) {
                    throw JdbcMethodPlan.failure(plan.method(),
                                                 "Flat bean mapping does not accept property-path alias: " + alias);
                }
                TypedElementInfo setter = setters.get(alias.toLowerCase(Locale.ROOT));
                if (setter == null) {
                    throw JdbcMethodPlan.failure(plan.method(), "No accessible bean setter for SQL alias: " + alias);
                }
                TypeName propertyType = setter.parameterArguments().getFirst().typeName();
                if (!JdbcMethodPlan.isScalar(propertyType)) {
                    throw JdbcMethodPlan.failure(plan.method(),
                                                 "Bean property is not a supported scalar: " + alias);
                }
                field.addContent("value.")
                        .addContent(setter.elementName())
                        .addContent("(row.")
                        .addContent(propertyType.primitive() ? "required(" : "get(")
                        .addContentLiteral(alias)
                        .addContent(", ")
                        .addContent(propertyType.boxed())
                        .addContentLine(".class));");
            }
            field.addContentLine("return value;")
                    .addContent("}");
        });
    }

    static void validateConstructor(JdbcMethodPlan plan, TypeInfo beanInfo) {
        boolean samePackage = samePackage(plan.method(), beanInfo.typeName());
        if (beanInfo.kind() != ElementKind.CLASS
                || beanInfo.elementModifiers().contains(Modifier.ABSTRACT)) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Bean mapping requires a concrete class: "
                                                 + beanInfo.typeName().resolvedName());
        }
        if (!beanInfo.typeName().enclosingNames().isEmpty()
                && !beanInfo.elementModifiers().contains(Modifier.STATIC)) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Bean mapping does not support a non-static nested class: "
                                                 + beanInfo.typeName().resolvedName());
        }
        if (beanInfo.accessModifier() != AccessModifier.PUBLIC
                && !(samePackage && (beanInfo.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || beanInfo.accessModifier() == AccessModifier.PROTECTED))) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Bean type is not accessible to generated code: "
                                                 + beanInfo.typeName().resolvedName());
        }
        boolean accessible = beanInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.CONSTRUCTOR)
                .filter(element -> element.parameterArguments().isEmpty())
                .anyMatch(element -> element.accessModifier() == AccessModifier.PUBLIC
                        || (samePackage && (element.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                        || element.accessModifier() == AccessModifier.PROTECTED)));
        if (!accessible) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Bean mapping requires an accessible no-argument constructor: "
                                                 + beanInfo.typeName().resolvedName());
        }
    }

    private static Map<String, TypedElementInfo> setters(JdbcMethodPlan plan, TypeInfo beanInfo) {
        Map<String, TypedElementInfo> setters = new HashMap<>();
        collectSetters(plan, beanInfo, setters);
        return setters;
    }

    private static void collectSetters(JdbcMethodPlan plan,
                                       TypeInfo beanInfo,
                                       Map<String, TypedElementInfo> setters) {
        for (TypedElementInfo element : beanInfo.elementInfo()) {
            if (element.kind() != ElementKind.METHOD
                    || !element.elementName().startsWith("set")
                    || element.elementName().length() <= 3
                    || element.parameterArguments().size() != 1
                    || element.elementModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (element.accessModifier() != AccessModifier.PUBLIC
                    && !(samePackage(plan.method(), beanInfo.typeName())
                    && (element.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                    || element.accessModifier() == AccessModifier.PROTECTED))) {
                continue;
            }
            String suffix = element.elementName().substring(3);
            String property = suffix.substring(0, 1).toLowerCase(Locale.ROOT) + suffix.substring(1);
            String key = property.toLowerCase(Locale.ROOT);
            TypedElementInfo existing = setters.putIfAbsent(key, element);
            if (existing != null
                    && !existing.parameterArguments().getFirst().typeName()
                    .equals(element.parameterArguments().getFirst().typeName())) {
                throw JdbcMethodPlan.failure(plan.method(), "Ambiguous overloaded bean setter for property: " + property);
            }
        }
        beanInfo.superTypeInfo().ifPresent(superType -> collectSetters(plan, superType, setters));
        beanInfo.interfaceTypeInfo().forEach(interfaceType -> collectSetters(plan, interfaceType, setters));
    }

    private static boolean samePackage(TypedElementInfo method, TypeName beanType) {
        return method.enclosingType()
                .map(TypeName::packageName)
                .filter(beanType.packageName()::equals)
                .isPresent();
    }
}
