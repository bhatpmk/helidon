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
import java.util.TreeMap;

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

/**
 * Emits JDBC row mapper code for generated repository methods.
 * <p>
 * The generator supports scalar reads, records, constructor DTOs, simple beans,
 * explicit {@code JdbcRowMapper} implementations, and generated mapper contracts
 * declared with {@code @Data.Mapper}. All mapping choices are resolved at build
 * time so repository execution does not need reflection.
 */
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

    void appendMapper(Method.Builder builder, TypeName resultType, TypedElementInfo methodInfo, String statement) {
        JdbcResultLabels labels = JdbcResultLabels.parse(statement);
        Optional<Annotation> mapWith = methodInfo.findAnnotation(MAP_WITH_ANNOTATION);
        if (mapWith.isPresent()) {
            // @Data.MapWith can name either an explicit JdbcRowMapper or a declarative mapper contract.
            TypeName mapperType = mapWith.get()
                    .typeValue()
                    .orElseThrow(() -> new CodegenException("@Data.MapWith mapper type is missing",
                                                            methodInfo.originatingElement()));
            TypeInfo mapperInfo = typeInfo(mapperType)
                    .orElseThrow(() -> new CodegenException("@Data.MapWith mapper type cannot be resolved: "
                                                                    + mapperType.fqName(),
                                                            methodInfo.originatingElement()));
            Optional<TypeName> explicitMapper = explicitMapper(mapperInfo, resultType);
            if (explicitMapper.isPresent()) {
                validateExplicitMapper(mapperInfo, methodInfo);
                builder.addContent("new ")
                        .addContent(explicitMapper.get())
                        .addContent("()");
            } else {
                TypeName generatedMapper = generateMapper(mapperType, mapperInfo, resultType, methodInfo, labels);
                builder.addContent(generatedMapper)
                        .addContent(".INSTANCE");
            }
            return;
        }

        Optional<TypeInfo> typeInfo = typeInfo(resultType);
        if (!isSupportedScalar(resultType) && typeInfo.isPresent() && beanMappingRequired(typeInfo.get())) {
            // Bean targets need a block lambda because values are assigned through setters after construction.
            appendBeanLambda(builder,
                             resultType,
                             typeInfo.get(),
                             mapperMappings(typeInfo.get(), methodInfo),
                             methodInfo,
                             labels);
        } else {
            builder.addContent("row -> ");
            appendGeneratedExpression(builder, resultType, methodInfo, methodInfo, labels);
        }
    }

    private TypeName generateMapper(TypeName mapperType,
                                    TypeInfo mapperInfo,
                                    TypeName resultType,
                                    TypedElementInfo methodInfo,
                                    JdbcResultLabels labels) {
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

        // Declarative mapper contracts become package-private singleton mapper implementations.
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
                    .addThrows(SQL_EXCEPTION);
            if (!isSupportedScalar(resultType) && beanMappingRequired(resultType)) {
                appendBeanMethodBody(method, resultType, mapperMappings(typeInfo(resultType).orElseThrow(), mapperInfo),
                                     methodInfo, labels);
            } else {
                method.addContent("return ");
                appendGeneratedExpression(method, resultType, mapperInfo, methodInfo, labels);
                method.addContentLine(";");
            }
        });

        roundContext.addGeneratedType(generatedMapper,
                                      classModel,
                                      mapperType,
                                      mapperInfo.originatingElementValue());
        return generatedMapper;
    }

    private Optional<TypeName> explicitMapper(TypeInfo mapperInfo, TypeName resultType) {
        TypeName mapperType = TypeName.builder(JDBC_ROW_MAPPER)
                .addTypeArgument(resultType)
                .build();
        return mapperInfo.findInHierarchy(mapperType)
                .map(it -> mapperInfo.typeName());
    }

    private void validateExplicitMapper(TypeInfo mapperInfo, TypedElementInfo methodInfo) {
        if (mapperInfo.accessModifier() == AccessModifier.PRIVATE) {
            throw new CodegenException("@Data.MapWith explicit mapper must be accessible: "
                                               + mapperInfo.typeName().fqName(),
                                       methodInfo.originatingElement());
        }
        boolean defaultConstructor = mapperInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                .findFirst()
                .isEmpty();
        boolean accessibleNoArgConstructor = mapperInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                .filter(it -> it.parameterArguments().isEmpty())
                .anyMatch(it -> it.accessModifier() != AccessModifier.PRIVATE);
        if (!defaultConstructor && !accessibleNoArgConstructor) {
            throw new CodegenException("@Data.MapWith explicit mapper requires an accessible no-argument constructor: "
                                               + mapperInfo.typeName().fqName(),
                                       methodInfo.originatingElement());
        }
    }

    private void appendGeneratedExpression(ContentBuilder<?> builder,
                                           TypeName resultType,
                                           Annotated mappingSource,
                                           TypedElementInfo methodInfo,
                                           JdbcResultLabels labels) {
        Optional<TypeInfo> typeInfo = typeInfo(resultType);
        if (isSupportedScalar(resultType)) {
            appendRowRead(builder, resultType, "1");
            return;
        }
        // Records and constructor DTOs are created directly, keeping generated code easy to debug.
        if (typeInfo.isPresent() && typeInfo.get().kind() == ElementKind.RECORD) {
            appendRecordExpression(builder,
                                   resultType,
                                   typeInfo.get(),
                                   mapperMappings(typeInfo.get(), mappingSource),
                                   methodInfo,
                                   labels);
            return;
        }
        if (typeInfo.isPresent() && typeInfo.get().kind() == ElementKind.CLASS) {
            appendConstructorExpression(builder,
                                        resultType,
                                        typeInfo.get(),
                                        mapperMappings(typeInfo.get(), mappingSource),
                                        methodInfo,
                                        labels);
            return;
        }
        throw new CodegenException("JDBC mapper generation currently supports scalar values, records, "
                                           + "constructor DTOs, and simple beans only: "
                                           + resultType.fqName(),
                                   methodInfo.originatingElement());
    }

    private boolean beanMappingRequired(TypeName resultType) {
        return typeInfo(resultType)
                .filter(this::beanMappingRequired)
                .isPresent();
    }

    private boolean beanMappingRequired(TypeInfo typeInfo) {
        return typeInfo.kind() == ElementKind.CLASS
                && constructor(typeInfo).isEmpty()
                && !beanProperties(typeInfo).isEmpty();
    }

    private void appendRecordExpression(ContentBuilder<?> builder,
                                        TypeName resultType,
                                        TypeInfo typeInfo,
                                        Map<String, String> mappings,
                                        TypedElementInfo methodInfo,
                                        JdbcResultLabels labels) {
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
            appendComponentExpression(builder, components.get(i), mappings, methodInfo, labels);
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
                                           TypedElementInfo methodInfo,
                                           JdbcResultLabels labels) {
        String componentName = component.elementName();
        String directSource = mappings.get(componentName);
        if (directSource != null) {
            appendRowRead(builder, component.typeName(), directSource, labels, methodInfo);
            return;
        }

        Map<String, String> nestedMappings = nestedMappings(componentName, mappings);
        if (!nestedMappings.isEmpty()) {
            // Nested record components allow @Data.Map(target = "address.city") style mappings.
            TypeInfo nestedTypeInfo = typeInfo(component.typeName())
                    .filter(it -> it.kind() == ElementKind.RECORD)
                    .orElseThrow(() -> new CodegenException("@Data.Map target uses nested property "
                                                                    + componentName
                                                                    + ", but "
                                                                    + component.typeName().fqName()
                                                                    + " is not a record",
                                                            methodInfo.originatingElement()));
            appendRecordExpression(builder, component.typeName(), nestedTypeInfo, nestedMappings, methodInfo, labels);
            return;
        }

        appendRowRead(builder, component.typeName(), componentName, labels, methodInfo);
    }

    private void appendConstructorExpression(ContentBuilder<?> builder,
                                             TypeName resultType,
                                             TypeInfo typeInfo,
                                             Map<String, String> mappings,
                                             TypedElementInfo methodInfo,
                                             JdbcResultLabels labels) {
        Optional<TypedElementInfo> maybeConstructor = constructor(typeInfo);
        if (maybeConstructor.isEmpty()) {
            throw new CodegenException("JDBC class mapper requires one accessible constructor with parameters "
                                               + "or a no-argument constructor with setters: "
                                               + typeInfo.typeName().fqName(),
                                       methodInfo.originatingElement());
        }
        TypedElementInfo constructor = maybeConstructor.get();
        List<TypedElementInfo> parameters = constructor.parameterArguments();
        validateConstructorMappingTargets(typeInfo, parameters, mappings.keySet(), methodInfo);

        builder.addContent("new ")
                .addContent(resultType)
                .addContentLine("(");
        builder.increaseContentPadding();
        for (int i = 0; i < parameters.size(); i++) {
            TypedElementInfo parameter = parameters.get(i);
            String target = parameter.elementName();
            String source = mappings.getOrDefault(target, target);
            appendRowRead(builder, parameter.typeName(), source, labels, methodInfo);
            if (i + 1 < parameters.size()) {
                builder.addContentLine(",");
            } else {
                builder.addContentLine();
            }
        }
        builder.decreaseContentPadding();
        builder.addContent(")");
    }

    private Optional<TypedElementInfo> constructor(TypeInfo typeInfo) {
        List<TypedElementInfo> constructors = typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                .filter(it -> !it.parameterArguments().isEmpty())
                .filter(it -> it.accessModifier() != AccessModifier.PRIVATE)
                .toList();
        if (constructors.size() == 1) {
            return Optional.of(constructors.getFirst());
        }
        if (constructors.isEmpty()) {
            return Optional.empty();
        }
        throw new CodegenException("JDBC constructor DTO mapper found multiple accessible constructors: "
                                           + typeInfo.typeName().fqName());
    }

    private void validateConstructorMappingTargets(TypeInfo typeInfo,
                                                   List<TypedElementInfo> parameters,
                                                   Set<String> targets,
                                                   TypedElementInfo methodInfo) {
        for (String target : targets) {
            boolean matches = parameters.stream()
                    .anyMatch(parameter -> parameter.elementName().equals(target));
            if (!matches || target.contains(".")) {
                throw new CodegenException("@Data.Map target does not match constructor parameters for "
                                                   + typeInfo.typeName().fqName()
                                                   + ": "
                                                   + target,
                                           methodInfo.originatingElement());
            }
        }
    }

    private void appendBeanLambda(ContentBuilder<?> builder,
                                  TypeName resultType,
                                  TypeInfo typeInfo,
                                  Map<String, String> mappings,
                                  TypedElementInfo methodInfo,
                                  JdbcResultLabels labels) {
        builder.addContentLine("row -> {");
        builder.increaseContentPadding();
        appendBeanBody(builder, resultType, typeInfo, mappings, methodInfo, labels);
        builder.decreaseContentPadding();
        builder.addContent("}");
    }

    private void appendBeanMethodBody(Method.Builder method,
                                      TypeName resultType,
                                      Map<String, String> mappings,
                                      TypedElementInfo methodInfo,
                                      JdbcResultLabels labels) {
        TypeInfo typeInfo = typeInfo(resultType)
                .orElseThrow(() -> new CodegenException("JDBC bean mapper target cannot be resolved: "
                                                                + resultType.fqName(),
                                                        methodInfo.originatingElement()));
        appendBeanBody(method, resultType, typeInfo, mappings, methodInfo, labels);
    }

    private void appendBeanBody(ContentBuilder<?> builder,
                                TypeName resultType,
                                TypeInfo typeInfo,
                                Map<String, String> mappings,
                                TypedElementInfo methodInfo,
                                JdbcResultLabels labels) {
        Map<String, TypedElementInfo> properties = beanProperties(typeInfo);
        validateBeanMappingTargets(typeInfo, properties.keySet(), mappings.keySet(), methodInfo);

        builder.addContent(resultType)
                .addContentLine(" mapped = new " + resultType.className() + "();");
        properties.forEach((property, setter) -> {
            String source = mappings.getOrDefault(property, property);
            builder.addContent("mapped.")
                    .addContent(setter.elementName())
                    .addContent("(");
            appendRowRead(builder, setter.parameterArguments().getFirst().typeName(), source, labels, methodInfo);
            builder.addContentLine(");");
        });
        builder.addContentLine("return mapped;");
    }

    private Map<String, TypedElementInfo> beanProperties(TypeInfo typeInfo) {
        if (!hasAccessibleNoArgConstructor(typeInfo)) {
            return Map.of();
        }
        Map<String, TypedElementInfo> properties = new TreeMap<>();
        typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.accessModifier() != AccessModifier.PRIVATE)
                .filter(it -> it.typeName().equals(TypeNames.PRIMITIVE_VOID)
                        || it.typeName().equals(TypeNames.BOXED_VOID))
                .filter(it -> it.parameterArguments().size() == 1)
                .forEach(setter -> setterProperty(setter.elementName())
                        .ifPresent(property -> properties.put(property, setter)));
        return Map.copyOf(properties);
    }

    private boolean hasAccessibleNoArgConstructor(TypeInfo typeInfo) {
        List<TypedElementInfo> constructors = typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                .toList();
        return constructors.isEmpty()
                || constructors.stream()
                        .filter(it -> it.parameterArguments().isEmpty())
                        .anyMatch(it -> it.accessModifier() != AccessModifier.PRIVATE);
    }

    private Optional<String> setterProperty(String setterName) {
        if (setterName.length() <= 3 || !setterName.startsWith("set")) {
            return Optional.empty();
        }
        char first = setterName.charAt(3);
        if (!Character.isUpperCase(first)) {
            return Optional.empty();
        }
        return Optional.of(Character.toLowerCase(first) + setterName.substring(4));
    }

    private void validateBeanMappingTargets(TypeInfo typeInfo,
                                            Set<String> properties,
                                            Set<String> targets,
                                            TypedElementInfo methodInfo) {
        for (String target : targets) {
            if (!properties.contains(target) || target.contains(".")) {
                throw new CodegenException("@Data.Map target does not match bean properties for "
                                                   + typeInfo.typeName().fqName()
                                                   + ": "
                                                   + target,
                                           methodInfo.originatingElement());
            }
        }
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
        // Type-level mappings provide defaults; method or mapper-contract mappings can override them.
        addMappings(result, targetType);
        addMappings(result, mappingSource);
        return Map.copyOf(result);
    }

    private boolean hasMappings(Annotated annotated) {
        return annotated.hasAnnotation(MAP_ANNOTATION) || annotated.hasAnnotation(MAPS_ANNOTATION);
    }

    private void addMappings(Map<String, String> mappings, Annotated annotated) {
        Map<String, String> localTargets = new HashMap<>();
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(MAP_ANNOTATION))
                .forEach(annotation -> addMapping(mappings, localTargets, annotation));
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(MAPS_ANNOTATION))
                .flatMap(annotation -> annotation.annotationValues().orElseGet(List::of).stream())
                .forEach(annotation -> addMapping(mappings, localTargets, annotation));
    }

    private void addMapping(Map<String, String> mappings, Map<String, String> localTargets, Annotation annotation) {
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
        if (localTargets.put(target, resolvedSource) != null) {
            throw new CodegenException("@Data.Map declares multiple mappings for target: " + target);
        }
        mappings.put(target, resolvedSource);
    }

    private void appendRowRead(ContentBuilder<?> builder,
                               TypeName typeName,
                               String source,
                               JdbcResultLabels labels,
                               TypedElementInfo methodInfo) {
        validateSourceLabel(source, labels, methodInfo);
        appendRowRead(builder, typeName, literal(source));
    }

    private void appendRowRead(ContentBuilder<?> builder, TypeName typeName, String column) {
        if (typeName.equals(TypeNames.STRING)) {
            builder.addContent("row.getString(" + column + ")");
        } else if (typeName.equals(TypeNames.PRIMITIVE_INT)) {
            builder.addContent("row.getInt(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_INT)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_INT)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_LONG)) {
            builder.addContent("row.getLong(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_LONG)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_LONG)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_BOOLEAN)) {
            builder.addContent("row.getBoolean(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_BOOLEAN)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_BOOLEAN)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_DOUBLE)) {
            builder.addContent("row.getDouble(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_DOUBLE)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_DOUBLE)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_FLOAT)) {
            builder.addContent("row.getFloat(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_FLOAT)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_FLOAT)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_SHORT)) {
            builder.addContent("row.getShort(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_SHORT)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_SHORT)
                    .addContent(".class)");
        } else if (typeName.equals(TypeNames.PRIMITIVE_BYTE)) {
            builder.addContent("row.getByte(" + column + ")");
        } else if (typeName.equals(TypeNames.BOXED_BYTE)) {
            builder.addContent("row.getObject(" + column + ", ")
                    .addContent(TypeNames.BOXED_BYTE)
                    .addContent(".class)");
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

    private void validateSourceLabel(String source, JdbcResultLabels labels, TypedElementInfo methodInfo) {
        if (!labels.complete() || labels.contains(source)) {
            return;
        }
        throw new CodegenException("JDBC mapper source label \"" + source
                                           + "\" is not produced by SQL query. Available labels: "
                                           + labels.labels(),
                                   methodInfo.originatingElement());
    }

    private Optional<TypeInfo> typeInfo(TypeName typeName) {
        return roundContext.typeInfo(typeName)
                .or(() -> codegenContext.typeInfo(typeName));
    }

    private static TypeName mapperClassName(TypeName mapperType) {
        return TypeName.builder(mapperType)
                .className(mapperType.classNameWithEnclosingNames().replace('.', '_') + "__JdbcMapper")
                .enclosingNames(List.of())
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
