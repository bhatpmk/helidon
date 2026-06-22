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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.ContentBuilder;
import io.helidon.codegen.classmodel.Method;
import io.helidon.common.Api;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotated;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.GENERATOR;

final class JdbcMapperCodegen {

    private static final TypeName BIG_DECIMAL = TypeName.create(BigDecimal.class);
    private static final TypeName JDBC_ROW_MAPPER = TypeName.create("io.helidon.data.jdbc.JdbcRowMapper");
    private static final TypeName JDBC_ROW_VIEW = TypeName.create("io.helidon.data.jdbc.JdbcResultSetRowView");
    private static final TypeName MAP_ANNOTATION = TypeName.create("io.helidon.data.Data.Map");
    private static final TypeName MAP_WITH_ANNOTATION = TypeName.create("io.helidon.data.Data.MapWith");
    private static final TypeName MAPPER_ANNOTATION = TypeName.create("io.helidon.data.Data.Mapper");
    private static final TypeName MAPS_ANNOTATION = TypeName.create("io.helidon.data.Data.Maps");
    private static final TypeName SQL_EXCEPTION = TypeName.create(SQLException.class);

    private final CodegenContext codegenContext;
    private final RoundContext roundContext;

    JdbcMapperCodegen(CodegenContext codegenContext, RoundContext roundContext) {
        this.codegenContext = codegenContext;
        this.roundContext = roundContext;
    }

    void appendMapper(Method.Builder builder, TypeName resultType, TypedElementInfo methodInfo) {
        Optional<Annotation> mapWith = methodInfo.findAnnotation(MAP_WITH_ANNOTATION);
        if (mapWith.isPresent()) {
            TypeName mapperType = mapWith.get()
                    .typeValue()
                    .orElseThrow(() -> new CodegenException("@Data.MapWith mapper type is missing",
                                                            methodInfo.originatingElement()));
            TypeName generatedMapper = generateMapper(mapperType, resultType, methodInfo);
            builder.addContent(generatedMapper)
                    .addContent(".INSTANCE");
            return;
        }

        builder.addContent("row -> ");
        appendGeneratedExpression(builder, resultType, methodInfo, methodInfo);
    }

    private TypeName generateMapper(TypeName mapperType, TypeName resultType, TypedElementInfo methodInfo) {
        TypeInfo mapperInfo = typeInfo(mapperType)
                .orElseThrow(() -> new CodegenException("@Data.MapWith mapper type cannot be resolved: "
                                                                + mapperType.fqName(),
                                                        methodInfo.originatingElement()));
        Annotation mapperAnnotation = mapperInfo.findAnnotation(MAPPER_ANNOTATION)
                .orElseThrow(() -> new CodegenException("@Data.MapWith currently requires a @Data.Mapper contract: "
                                                                + mapperType.fqName(),
                                                        methodInfo.originatingElement()));
        TypeName targetType = mapperAnnotation.typeValue("target")
                .orElseThrow(() -> new CodegenException("@Data.Mapper target type is missing: "
                                                                + mapperType.fqName(),
                                                        mapperInfo.originatingElement()));
        if (!targetType.equals(resultType)) {
            throw new CodegenException("@Data.MapWith mapper target "
                                               + targetType.fqName()
                                               + " does not match repository method result type "
                                               + resultType.fqName(),
                                       methodInfo.originatingElement());
        }
        if (hasMappings(methodInfo)) {
            throw new CodegenException("@Data.Map cannot be combined with @Data.MapWith. "
                                               + "Move mapping declarations to the @Data.Mapper contract.",
                                       methodInfo.originatingElement());
        }

        TypeName generatedMapper = mapperClassName(mapperType);
        if (roundContext.generatedType(generatedMapper).isPresent()) {
            return generatedMapper;
        }

        ClassModel.Builder classModel = ClassModel.builder()
                .type(generatedMapper)
                .copyright(CodegenUtil.copyright(GENERATOR, mapperType, generatedMapper))
                .addAnnotation(Annotation.create(SuppressWarnings.class, Api.SUPPRESS_INTERNAL))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR, mapperType, generatedMapper, "1", ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(TypeName.builder(JDBC_ROW_MAPPER)
                                      .addTypeArgument(resultType)
                                      .build());

        classModel.addField(field -> field.accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .isStatic(true)
                .isFinal(true)
                .type(generatedMapper)
                .name("INSTANCE")
                .addContent("new ")
                .addContent(generatedMapper)
                .addContent("()"));

        classModel.addConstructor(ctr -> ctr.accessModifier(AccessModifier.PRIVATE));

        classModel.addMethod(method -> {
            method.name("map")
                    .accessModifier(AccessModifier.PUBLIC)
                    .returnType(resultType)
                    .addAnnotation(Annotations.OVERRIDE)
                    .addParameter(JDBC_ROW_VIEW, "row")
                    .addThrows(SQL_EXCEPTION)
                    .addContent("return ");
            appendGeneratedExpression(method, resultType, mapperInfo, methodInfo);
            method.addContentLine(";");
        });

        roundContext.addGeneratedType(generatedMapper,
                                      classModel,
                                      mapperType,
                                      mapperInfo.originatingElementValue());
        return generatedMapper;
    }

    private void appendGeneratedExpression(ContentBuilder<?> builder,
                                           TypeName resultType,
                                           Annotated mappingSource,
                                           TypedElementInfo methodInfo) {
        Optional<TypeInfo> typeInfo = typeInfo(resultType);
        if (typeInfo.isPresent() && typeInfo.get().kind() == ElementKind.RECORD) {
            appendRecordExpression(builder,
                                   resultType,
                                   typeInfo.get(),
                                   mapperMappings(typeInfo.get(), mappingSource),
                                   methodInfo);
            return;
        }
        if (isSupportedScalar(resultType)) {
            appendRowRead(builder, resultType, "1");
            return;
        }
        throw new CodegenException("JDBC mapper generation currently supports scalar values and records only: "
                                           + resultType.fqName(),
                                   methodInfo.originatingElement());
    }

    private void appendRecordExpression(ContentBuilder<?> builder,
                                        TypeName resultType,
                                        TypeInfo typeInfo,
                                        Map<String, String> mappings,
                                        TypedElementInfo methodInfo) {
        List<TypedElementInfo> components = recordComponents(typeInfo);
        if (components.isEmpty()) {
            throw new CodegenException("Record mapper generation requires record components: " + resultType.fqName(),
                                       typeInfo.originatingElement());
        }
        validateMappingTargets(typeInfo, mappings.keySet(), methodInfo);

        builder.addContent("new ")
                .addContent(resultType)
                .addContentLine("(");
        builder.increaseContentPadding();
        for (int i = 0; i < components.size(); i++) {
            appendComponentExpression(builder, components.get(i), mappings, methodInfo);
            if (i + 1 < components.size()) {
                builder.addContentLine(",");
            } else {
                builder.addContentLine();
            }
        }
        builder.decreaseContentPadding();
        builder.addContent(")");
    }

    private void appendComponentExpression(ContentBuilder<?> builder,
                                           TypedElementInfo component,
                                           Map<String, String> mappings,
                                           TypedElementInfo methodInfo) {
        String componentName = component.elementName();
        String directSource = mappings.get(componentName);
        if (directSource != null) {
            appendRowRead(builder, component.typeName(), literal(directSource));
            return;
        }

        Map<String, String> nestedMappings = nestedMappings(componentName, mappings);
        if (!nestedMappings.isEmpty()) {
            TypeInfo nestedTypeInfo = typeInfo(component.typeName())
                    .filter(it -> it.kind() == ElementKind.RECORD)
                    .orElseThrow(() -> new CodegenException("@Data.Map target uses nested property "
                                                                    + componentName
                                                                    + ", but "
                                                                    + component.typeName().fqName()
                                                                    + " is not a record",
                                                            methodInfo.originatingElement()));
            appendRecordExpression(builder, component.typeName(), nestedTypeInfo, nestedMappings, methodInfo);
            return;
        }

        appendRowRead(builder, component.typeName(), literal(componentName));
    }

    private void validateMappingTargets(TypeInfo typeInfo, Set<String> targets, TypedElementInfo methodInfo) {
        for (String target : targets) {
            if (!validTarget(typeInfo, target)) {
                throw new CodegenException("@Data.Map target does not match record components for "
                                                   + typeInfo.typeName().fqName()
                                                   + ": "
                                                   + target,
                                           methodInfo.originatingElement());
            }
        }

        for (String target : targets) {
            String prefix = target + ".";
            for (String other : targets) {
                if (!target.equals(other) && other.startsWith(prefix)) {
                    throw new CodegenException("@Data.Map target cannot be both a value and a nested object: "
                                                       + target,
                                               methodInfo.originatingElement());
                }
            }
        }
    }

    private boolean validTarget(TypeInfo typeInfo, String target) {
        if (target.isBlank()) {
            return false;
        }

        TypeInfo current = typeInfo;
        String[] path = target.split("\\.");
        for (int i = 0; i < path.length; i++) {
            Optional<TypedElementInfo> component = recordComponent(current, path[i]);
            if (component.isEmpty()) {
                return false;
            }
            if (i + 1 == path.length) {
                return true;
            }
            Optional<TypeInfo> nested = typeInfo(component.get().typeName())
                    .filter(it -> it.kind() == ElementKind.RECORD);
            if (nested.isEmpty()) {
                return false;
            }
            current = nested.get();
        }
        return false;
    }

    private Optional<TypedElementInfo> recordComponent(TypeInfo typeInfo, String name) {
        return recordComponents(typeInfo)
                .stream()
                .filter(it -> it.elementName().equals(name))
                .findFirst();
    }

    private List<TypedElementInfo> recordComponents(TypeInfo typeInfo) {
        return typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
    }

    private Map<String, String> nestedMappings(String componentName, Map<String, String> mappings) {
        String prefix = componentName + ".";
        Map<String, String> result = new HashMap<>();
        mappings.forEach((target, source) -> {
            if (target.startsWith(prefix)) {
                result.put(target.substring(prefix.length()), source);
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, String> mapperMappings(Annotated targetType, Annotated mappingSource) {
        Map<String, String> result = new HashMap<>();
        addMappings(result, targetType);
        addMappings(result, mappingSource);
        return Map.copyOf(result);
    }

    private boolean hasMappings(Annotated annotated) {
        return annotated.hasAnnotation(MAP_ANNOTATION) || annotated.hasAnnotation(MAPS_ANNOTATION);
    }

    private void addMappings(Map<String, String> mappings, Annotated annotated) {
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(MAP_ANNOTATION))
                .forEach(annotation -> addMapping(mappings, annotation));
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(MAPS_ANNOTATION))
                .flatMap(annotation -> annotation.annotationValues().orElseGet(List::of).stream())
                .forEach(annotation -> addMapping(mappings, annotation));
    }

    private void addMapping(Map<String, String> mappings, Annotation annotation) {
        String value = annotation.value().orElse("");
        String source = annotation.stringValue("source").orElse("");
        if (!value.isBlank() && !source.isBlank()) {
            throw new CodegenException("@Data.Map must declare either value or source, not both");
        }
        String resolvedSource = !source.isBlank() ? source : value;
        if (resolvedSource.isBlank()) {
            throw new CodegenException("@Data.Map source value is missing");
        }
        String target = annotation.stringValue("target")
                .orElseThrow(() -> new CodegenException("@Data.Map target value is missing"));
        mappings.put(target, resolvedSource);
    }

    private void appendRowRead(ContentBuilder<?> builder, TypeName typeName, String column) {
        if (typeName.equals(TypeNames.STRING)) {
            builder.addContent("row.getString(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_INT) || typeName.equals(TypeNames.BOXED_INT)) {
            builder.addContent("row.getInt(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_LONG) || typeName.equals(TypeNames.BOXED_LONG)) {
            builder.addContent("row.getLong(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_BOOLEAN) || typeName.equals(TypeNames.BOXED_BOOLEAN)) {
            builder.addContent("row.getBoolean(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_DOUBLE) || typeName.equals(TypeNames.BOXED_DOUBLE)) {
            builder.addContent("row.getDouble(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_FLOAT) || typeName.equals(TypeNames.BOXED_FLOAT)) {
            builder.addContent("row.getFloat(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_SHORT) || typeName.equals(TypeNames.BOXED_SHORT)) {
            builder.addContent("row.getShort(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_BYTE) || typeName.equals(TypeNames.BOXED_BYTE)) {
            builder.addContent("row.getByte(" + column + ")");
        } else if (typeName.equals(BIG_DECIMAL)) {
            builder.addContent("row.getBigDecimal(" + column + ")");
        } else if (typeName.equals(TypeNames.OBJECT)) {
            builder.addContent("row.getObject(" + column + ")");
        } else {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(typeName)
                    .addContent(".class)");
        }
    }

    private Optional<TypeInfo> typeInfo(TypeName typeName) {
        return roundContext.typeInfo(typeName)
                .or(() -> codegenContext.typeInfo(typeName));
    }

    private static TypeName mapperClassName(TypeName mapperType) {
        return TypeName.builder(mapperType)
                .className(mapperType.className() + "__JdbcMapper")
                .build();
    }

    private static boolean isSupportedScalar(TypeName typeName) {
        return typeName.equals(TypeNames.STRING)
                || typeName.equals(TypeNames.PRIMITIVE_INT)
                || typeName.equals(TypeNames.BOXED_INT)
                || typeName.equals(TypeNames.PRIMITIVE_LONG)
                || typeName.equals(TypeNames.BOXED_LONG)
                || typeName.equals(TypeNames.PRIMITIVE_BOOLEAN)
                || typeName.equals(TypeNames.BOXED_BOOLEAN)
                || typeName.equals(TypeNames.PRIMITIVE_DOUBLE)
                || typeName.equals(TypeNames.BOXED_DOUBLE)
                || typeName.equals(TypeNames.PRIMITIVE_FLOAT)
                || typeName.equals(TypeNames.BOXED_FLOAT)
                || typeName.equals(TypeNames.PRIMITIVE_SHORT)
                || typeName.equals(TypeNames.BOXED_SHORT)
                || typeName.equals(TypeNames.PRIMITIVE_BYTE)
                || typeName.equals(TypeNames.BOXED_BYTE)
                || typeName.equals(BIG_DECIMAL)
                || typeName.equals(TypeNames.OBJECT);
    }

    private static String literal(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '\\' -> builder.append("\\\\");
            case '"' -> builder.append("\\\"");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
