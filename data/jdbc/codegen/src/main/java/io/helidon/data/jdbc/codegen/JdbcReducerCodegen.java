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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.CodegenUtil;
import io.helidon.codegen.RoundContext;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.ContentBuilder;
import io.helidon.codegen.classmodel.InnerClass;
import io.helidon.codegen.classmodel.Method;
import io.helidon.common.Api;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.Annotated;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.Annotations;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

import static io.helidon.data.jdbc.codegen.JdbcPersistenceGenerator.GENERATOR;

final class JdbcReducerCodegen {

    private static final TypeName ARRAYS = TypeName.create("java.util.Arrays");
    private static final TypeName ARRAY_LIST = TypeName.create(ArrayList.class);
    private static final TypeName BIG_DECIMAL = TypeName.create(BigDecimal.class);
    private static final TypeName HASH_SET = TypeName.create(HashSet.class);
    private static final TypeName JDBC_ROW_REDUCER = TypeName.create("io.helidon.data.jdbc.JdbcRowReducer");
    private static final TypeName JDBC_ROW_VIEW = TypeName.create("io.helidon.data.jdbc.JdbcResultSetRowView");
    private static final TypeName KEY_ANNOTATION = TypeName.create("io.helidon.data.Data.Key");
    private static final TypeName KEYS_ANNOTATION = TypeName.create("io.helidon.data.Data.Keys");
    private static final TypeName LINKED_HASH_MAP = TypeName.create(LinkedHashMap.class);
    private static final TypeName LINKED_HASH_SET = TypeName.create("java.util.LinkedHashSet");
    private static final TypeName MAP_ANNOTATION = TypeName.create("io.helidon.data.Data.Map");
    private static final TypeName MAPPER_ANNOTATION = TypeName.create("io.helidon.data.Data.Mapper");
    private static final TypeName MAPS_ANNOTATION = TypeName.create("io.helidon.data.Data.Maps");
    private static final TypeName OBJECTS = TypeName.create("java.util.Objects");
    private static final TypeName REDUCE_WITH_ANNOTATION = TypeName.create("io.helidon.data.Data.ReduceWith");
    private static final TypeName SQL_EXCEPTION = TypeName.create(SQLException.class);

    private final CodegenContext codegenContext;
    private final RoundContext roundContext;
    private final TypeName repositoryClassName;

    JdbcReducerCodegen(CodegenContext codegenContext,
                       RoundContext roundContext,
                       TypeName repositoryClassName) {
        this.codegenContext = codegenContext;
        this.roundContext = roundContext;
        this.repositoryClassName = repositoryClassName;
    }

    boolean reducerRequired(TypeName resultType, TypedElementInfo methodInfo, String statement) {
        return reducerModel(resultType, methodInfo, statement)
                .map(ReducerModel::hasCollections)
                .orElse(false);
    }

    void appendReducer(Method.Builder builder, TypeName resultType, TypedElementInfo methodInfo, String statement) {
        ReducerModel model = reducerModel(resultType, methodInfo, statement)
                .filter(ReducerModel::hasCollections)
                .orElseThrow(() -> new CodegenException("JDBC reducer generation requires collection result paths",
                                                        methodInfo.originatingElement()));
        TypeName reducerType = generateReducer(model, methodInfo);
        builder.addContent("new ")
                .addContent(reducerType)
                .addContent("()");
    }

    private Optional<ReducerModel> reducerModel(TypeName resultType, TypedElementInfo methodInfo, String statement) {
        MappingMetadata metadata = mappingMetadata(resultType, methodInfo, statement);
        if (metadata.mappings().isEmpty()) {
            return Optional.empty();
        }

        TypeInfo rootInfo = typeInfo(resultType)
                .orElseThrow(() -> new CodegenException("JDBC reducer target type cannot be resolved: "
                                                                + resultType.fqName(),
                                                        methodInfo.originatingElement()));
        if (rootInfo.kind() != ElementKind.RECORD && rootInfo.kind() != ElementKind.CLASS) {
            return Optional.empty();
        }

        NodeSpec root = new NodeSpec("", "Root", resultType, rootInfo, null, null, false, null);
        boolean hasCollection = false;
        for (Mapping mapping : metadata.mappings().values()) {
            hasCollection |= addMapping(root, mapping, methodInfo);
        }
        if (!hasCollection && metadata.reduceWith().isEmpty()) {
            return Optional.empty();
        }
        if (!hasCollection) {
            return Optional.empty();
        }

        applyKeys(root, metadata.keys(), methodInfo);
        validateKeys(root, methodInfo);
        validateConstruction(root, methodInfo);
        TypeName reducerType = reducerClassName(methodInfo);
        assignAccumulatorNames(root, reducerType);
        return Optional.of(new ReducerModel(root, reducerType, true));
    }

    private MappingMetadata mappingMetadata(TypeName resultType, TypedElementInfo methodInfo, String statement) {
        Map<String, Mapping> mappings = new LinkedHashMap<>();
        TypeInfo resultInfo = typeInfo(resultType).orElse(null);
        resultLabels(statement)
                .stream()
                .filter(label -> resultInfo != null && validResultPath(resultInfo, label))
                .forEach(label -> mappings.put(label, new Mapping(label, label)));

        Map<String, List<String>> keys = new LinkedHashMap<>();
        Optional<TypeInfo> reduceWith = reduceWithType(resultType, methodInfo);
        if (reduceWith.isPresent() && hasMappings(methodInfo)) {
            throw new CodegenException("@Data.Map cannot be combined with @Data.ReduceWith. "
                                               + "Move mapping declarations to the reducer contract.",
                                       methodInfo.originatingElement());
        }

        typeInfo(resultType).ifPresent(it -> {
            addMappings(mappings, it);
            addKeys(keys, it);
        });
        if (reduceWith.isPresent()) {
            addMappings(mappings, reduceWith.get());
            addKeys(keys, reduceWith.get());
        } else {
            addMappings(mappings, methodInfo);
            addKeys(keys, methodInfo);
        }
        return new MappingMetadata(new LinkedHashMap<>(mappings), new LinkedHashMap<>(keys), reduceWith);
    }

    private Optional<TypeInfo> reduceWithType(TypeName resultType, TypedElementInfo methodInfo) {
        Optional<Annotation> reduceWith = methodInfo.findAnnotation(REDUCE_WITH_ANNOTATION);
        if (reduceWith.isEmpty()) {
            return Optional.empty();
        }
        TypeName reducerType = reduceWith.get()
                .typeValue()
                .orElseThrow(() -> new CodegenException("@Data.ReduceWith reducer type is missing",
                                                        methodInfo.originatingElement()));
        TypeInfo reducerInfo = typeInfo(reducerType)
                .orElseThrow(() -> new CodegenException("@Data.ReduceWith reducer type cannot be resolved: "
                                                                + reducerType.fqName(),
                                                        methodInfo.originatingElement()));
        Annotation mapperAnnotation = reducerInfo.findAnnotation(MAPPER_ANNOTATION)
                .orElseThrow(() -> new CodegenException("@Data.ReduceWith currently requires a @Data.Mapper contract: "
                                                                + reducerType.fqName(),
                                                        methodInfo.originatingElement()));
        TypeName targetType = mapperAnnotation.typeValue("target")
                .orElseThrow(() -> new CodegenException("@Data.Mapper target type is missing: "
                                                                + reducerType.fqName(),
                                                        reducerInfo.originatingElement()));
        if (!targetType.equals(resultType)) {
            throw new CodegenException("@Data.ReduceWith reducer target "
                                               + targetType.fqName()
                                               + " does not match repository method result type "
                                               + resultType.fqName(),
                                       methodInfo.originatingElement());
        }
        return Optional.of(reducerInfo);
    }

    private boolean addMapping(NodeSpec root, Mapping mapping, TypedElementInfo methodInfo) {
        String[] parts = mapping.target().split("\\.");
        if (parts.length == 0 || mapping.target().isBlank()) {
            throw new CodegenException("JDBC reducer mapping target is empty",
                                       methodInfo.originatingElement());
        }

        NodeSpec current = root;
        boolean crossedCollection = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            NodeSpec currentNode = current;
            PropertySpec property = property(currentNode.typeInfo(), part, methodInfo)
                    .orElseThrow(() -> new CodegenException("JDBC reducer mapping target does not match "
                                                                    + currentNode.type().fqName()
                                                                    + ": "
                                                                    + mapping.target(),
                                                            methodInfo.originatingElement()));
            boolean last = i + 1 == parts.length;
            if (last) {
                current.scalars().put(part, new ScalarSpec(property, mapping.source()));
                return crossedCollection;
            }
            if (!property.collection()) {
                throw new CodegenException("JDBC reducer relationship path must cross a collection property: "
                                                   + mapping.target(),
                                           methodInfo.originatingElement());
            }
            crossedCollection = true;
            NodeSpec child = current.children().get(part);
            if (child == null) {
                TypeInfo elementInfo = typeInfo(property.elementType())
                        .orElseThrow(() -> new CodegenException("JDBC reducer collection element type "
                                                                        + "cannot be resolved: "
                                                                        + property.elementType().fqName(),
                                                                methodInfo.originatingElement()));
                child = new NodeSpec(childPath(current.path(), part),
                                     part,
                                     property.elementType(),
                                     elementInfo,
                                     current,
                                     property,
                                     true,
                                     property.type());
                current.children().put(part, child);
            }
            current = child;
        }
        return crossedCollection;
    }

    private void applyKeys(NodeSpec root, Map<String, List<String>> keys, TypedElementInfo methodInfo) {
        visit(root, node -> {
            List<String> explicit = keys.get(node.path());
            if (explicit != null) {
                node.keySources(explicit);
                return;
            }
            String keyTarget = node.path().isEmpty() ? "id" : node.path() + ".id";
            Mapping idMapping = findMapping(root, keyTarget);
            if (idMapping != null) {
                node.keySources(List.of(idMapping.source()));
            }
        });
    }

    private Mapping findMapping(NodeSpec root, String target) {
        NodeSpec current = root;
        String[] parts = target.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.children().get(parts[i]);
            if (current == null) {
                return null;
            }
        }
        ScalarSpec scalar = current.scalars().get(parts[parts.length - 1]);
        if (scalar == null) {
            return null;
        }
        return new Mapping(target, scalar.source());
    }

    private void validateKeys(NodeSpec root, TypedElementInfo methodInfo) {
        visit(root, node -> {
            if (node.keySources().isEmpty()) {
                String target = node.path().isEmpty() ? "root" : node.path();
                throw new CodegenException("JDBC reducer cannot infer key for " + target
                                                   + ". Use @Data.Key to declare the key columns.",
                                           methodInfo.originatingElement());
            }
        });
    }

    private void validateConstruction(NodeSpec root, TypedElementInfo methodInfo) {
        visit(root, node -> {
            if (node.typeInfo().kind() == ElementKind.CLASS) {
                validateNoArgConstructor(node.typeInfo(), methodInfo);
                node.scalars().values().forEach(scalar -> writer(node.typeInfo(), scalar.property(), methodInfo));
                node.children().values().forEach(child -> collectionWriter(node.typeInfo(), child.property(), methodInfo));
            } else if (node.typeInfo().kind() == ElementKind.RECORD) {
                List<String> available = new ArrayList<>(node.scalars().keySet());
                available.addAll(node.children().keySet());
                recordComponents(node.typeInfo())
                        .stream()
                        .filter(component -> !available.contains(component.elementName()))
                        .findFirst()
                        .ifPresent(component -> {
                            throw new CodegenException("JDBC reducer mapping for record "
                                                               + node.type().fqName()
                                                               + " is missing component "
                                                               + component.elementName(),
                                                       methodInfo.originatingElement());
                        });
            }
        });
    }

    private TypeName generateReducer(ReducerModel model, TypedElementInfo methodInfo) {
        TypeName reducerType = model.reducerType();
        if (roundContext.generatedType(reducerType).isPresent()) {
            return reducerType;
        }

        ClassModel.Builder classModel = ClassModel.builder()
                .type(reducerType)
                .copyright(CodegenUtil.copyright(GENERATOR,
                                                 repositoryClassName,
                                                 reducerType))
                .addAnnotation(Annotation.create(SuppressWarnings.class, Api.SUPPRESS_INTERNAL))
                .addAnnotation(CodegenUtil.generatedAnnotation(GENERATOR,
                                                               repositoryClassName,
                                                               reducerType,
                                                               "1",
                                                               ""))
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PACKAGE_PRIVATE)
                .addInterface(TypeName.builder(JDBC_ROW_REDUCER)
                                      .addTypeArgument(model.root().type())
                                      .build());

        NodeSpec root = model.root();
        classModel.addField(field -> field.name("roots")
                .isFinal(true)
                .type(mapType(root.accumulatorType()))
                .addContent("new ")
                .addContent(LINKED_HASH_MAP)
                .addContent("<>()"));

        classModel.addMethod(method -> generateAddMethod(method, root));
        classModel.addMethod(method -> generateResultMethod(method, root));
        visit(root, node -> classModel.addInnerClass(inner -> generateAccumulator(inner, node, methodInfo)));

        roundContext.addGeneratedType(reducerType,
                                      classModel,
                                      repositoryClassName,
                                      methodInfo.originatingElementValue());
        return reducerType;
    }

    private void generateAddMethod(Method.Builder method, NodeSpec root) {
        method.name("add")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.PRIMITIVE_VOID)
                .addAnnotation(Annotations.OVERRIDE)
                .addParameter(JDBC_ROW_VIEW, "row")
                .addThrows(SQL_EXCEPTION);
        appendKey(method, root);
        method.addContent("if (key.stream().allMatch(")
                .addContent(OBJECTS)
                .addContentLine("::isNull)) {");
        method.increaseContentPadding()
                .addContentLine("return;")
                .decreaseContentPadding()
                .addContentLine("}");
        method.addContent(root.accumulatorType())
                .addContentLine(" accumulator = this.roots.get(key);")
                .addContentLine("if (accumulator == null) {");
        method.increaseContentPadding()
                .addContent("accumulator = new ")
                .addContent(root.accumulatorType())
                .addContentLine("(row);")
                .addContentLine("this.roots.put(key, accumulator);")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("accumulator.add(row);");
    }

    private void generateResultMethod(Method.Builder method, NodeSpec root) {
        method.name("result")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeName.builder(TypeNames.LIST)
                                    .addTypeArgument(root.type())
                                    .build())
                .addAnnotation(Annotations.OVERRIDE);
        method.addContent(TypeName.builder(TypeNames.LIST)
                                  .addTypeArgument(root.type())
                                  .build())
                .addContent(" result = new ")
                .addContent(ARRAY_LIST)
                .addContentLine("<>(this.roots.size());")
                .addContent("for (")
                .addContent(root.accumulatorType())
                .addContentLine(" accumulator : this.roots.values()) {");
        method.increaseContentPadding()
                .addContentLine("result.add(accumulator.finish());")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("return result;");
    }

    private void generateAccumulator(InnerClass.Builder inner, NodeSpec node, TypedElementInfo methodInfo) {
        inner.name(node.accumulatorName())
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PRIVATE);
        node.scalars().values().forEach(scalar -> inner.addField(field -> field.name(scalar.property().name())
                .isFinal(true)
                .type(scalar.property().valueType())));
        node.children().values().forEach(child -> inner.addField(field -> field.name(child.mapName())
                .isFinal(true)
                .type(mapType(child.accumulatorType()))
                .addContent("new ")
                .addContent(LINKED_HASH_MAP)
                .addContent("<>()")));

        inner.addConstructor(ctr -> {
            ctr.accessModifier(AccessModifier.PRIVATE)
                    .addParameter(JDBC_ROW_VIEW, "row")
                    .addThrows(SQL_EXCEPTION);
            node.scalars().values().forEach(scalar -> {
                ctr.addContent("this.")
                        .addContent(scalar.property().name())
                        .addContent(" = ");
                appendRowRead(ctr, scalar.property().valueType(), literal(scalar.source()));
                ctr.addContentLine(";");
            });
        });

        inner.addMethod(method -> generateAccumulatorAddMethod(method, node));
        node.children().values().forEach(child -> inner.addMethod(method -> generateChildMethod(method, child)));
        node.children().values().forEach(child -> inner.addMethod(method -> generateFinishCollectionMethod(method, child)));
        inner.addMethod(method -> generateFinishMethod(method, node, methodInfo));
    }

    private void generateAccumulatorAddMethod(Method.Builder method, NodeSpec node) {
        method.name("add")
                .accessModifier(AccessModifier.PRIVATE)
                .returnType(TypeNames.PRIMITIVE_VOID)
                .addParameter(JDBC_ROW_VIEW, "row")
                .addThrows(SQL_EXCEPTION);
        node.children().values().forEach(child -> method.addContentLine("add" + child.methodSuffix() + "(row);"));
    }

    private void generateChildMethod(Method.Builder method, NodeSpec child) {
        method.name("add" + child.methodSuffix())
                .accessModifier(AccessModifier.PRIVATE)
                .returnType(TypeNames.PRIMITIVE_VOID)
                .addParameter(JDBC_ROW_VIEW, "row")
                .addThrows(SQL_EXCEPTION);
        appendKey(method, child);
        method.addContent("if (key.stream().allMatch(")
                .addContent(OBJECTS)
                .addContentLine("::isNull)) {");
        method.increaseContentPadding()
                .addContentLine("return;")
                .decreaseContentPadding()
                .addContentLine("}");
        method.addContent(child.accumulatorType())
                .addContent(" accumulator = this.")
                .addContent(child.mapName())
                .addContentLine(".get(key);")
                .addContentLine("if (accumulator == null) {");
        method.increaseContentPadding()
                .addContent("accumulator = new ")
                .addContent(child.accumulatorType())
                .addContentLine("(row);")
                .addContent("this.")
                .addContent(child.mapName())
                .addContentLine(".put(key, accumulator);")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("accumulator.add(row);");
    }

    private void generateFinishCollectionMethod(Method.Builder method, NodeSpec child) {
        TypeName collectionType = child.collectionType();
        method.name("finish" + child.methodSuffix())
                .accessModifier(AccessModifier.PRIVATE)
                .returnType(collectionType);
        TypeName implementation = child.collectionType().isSet() ? LINKED_HASH_SET : ARRAY_LIST;
        method.addContent(collectionType)
                .addContent(" result = new ")
                .addContent(implementation)
                .addContentLine("<>(this." + child.mapName() + ".size());")
                .addContent("for (")
                .addContent(child.accumulatorType())
                .addContent(" accumulator : this.")
                .addContent(child.mapName())
                .addContentLine(".values()) {");
        method.increaseContentPadding()
                .addContentLine("result.add(accumulator.finish());")
                .decreaseContentPadding()
                .addContentLine("}")
                .addContentLine("return result;");
    }

    private void generateFinishMethod(Method.Builder method, NodeSpec node, TypedElementInfo methodInfo) {
        method.name("finish")
                .accessModifier(AccessModifier.PRIVATE)
                .returnType(node.type());
        if (node.typeInfo().kind() == ElementKind.RECORD) {
            generateRecordFinish(method, node);
        } else {
            generateClassFinish(method, node, methodInfo);
        }
    }

    private void generateRecordFinish(Method.Builder method, NodeSpec node) {
        List<TypedElementInfo> components = recordComponents(node.typeInfo());
        method.addContent("return new ")
                .addContent(node.type())
                .addContentLine("(");
        method.increaseContentPadding();
        for (int i = 0; i < components.size(); i++) {
            TypedElementInfo component = components.get(i);
            NodeSpec child = node.children().get(component.elementName());
            if (child != null) {
                method.addContent("finish")
                        .addContent(child.methodSuffix())
                        .addContent("()");
            } else {
                method.addContent("this.")
                        .addContent(component.elementName());
            }
            if (i + 1 < components.size()) {
                method.addContentLine(",");
            } else {
                method.addContentLine();
            }
        }
        method.decreaseContentPadding()
                .addContentLine(");");
    }

    private void generateClassFinish(Method.Builder method, NodeSpec node, TypedElementInfo methodInfo) {
        method.addContent(node.type())
                .addContent(" result = new ")
                .addContent(node.type())
                .addContentLine("();");
        node.scalars().values().forEach(scalar -> {
            PropertyWriter writer = writer(node.typeInfo(), scalar.property(), methodInfo);
            appendWrite(method, writer, "result", "this." + scalar.property().name());
        });
        node.children().values().forEach(child -> {
            PropertyWriter writer = collectionWriter(node.typeInfo(), child.property(), methodInfo);
            appendCollectionWrite(method, writer, "result", "finish" + child.methodSuffix() + "()");
        });
        method.addContentLine("return result;");
    }

    private void appendCollectionWrite(ContentBuilder<?> builder,
                                       PropertyWriter writer,
                                       String target,
                                       String valueExpression) {
        switch (writer.kind()) {
        case FIELD -> {
            if (!writer.field().elementModifiers().contains(Modifier.FINAL)) {
                builder.addContent("if (")
                        .addContent(target)
                        .addContent(".")
                        .addContent(writer.property().name())
                        .addContentLine(" == null) {");
                builder.increaseContentPadding()
                        .addContent(target)
                        .addContent(".")
                        .addContent(writer.property().name())
                        .addContent(" = new ");
                appendCollectionImplementation(builder, writer.property().type());
                builder.addContentLine("();")
                        .decreaseContentPadding()
                        .addContentLine("}");
            }
            builder.addContent(target)
                    .addContent(".")
                    .addContent(writer.property().name())
                    .addContent(".addAll(")
                    .addContent(valueExpression)
                    .addContentLine(");");
        }
        case GETTER -> builder.addContent(target)
                .addContent(".")
                .addContent(writer.method().elementName())
                .addContent("().addAll(")
                .addContent(valueExpression)
                .addContentLine(");");
        case SETTER -> builder.addContent(target)
                .addContent(".")
                .addContent(writer.method().elementName())
                .addContent("(")
                .addContent(valueExpression)
                .addContentLine(");");
        default -> throw new CodegenException("Unsupported collection writer: " + writer.kind());
        }
    }

    private void appendWrite(ContentBuilder<?> builder,
                             PropertyWriter writer,
                             String target,
                             String valueExpression) {
        switch (writer.kind()) {
        case FIELD -> builder.addContent(target)
                .addContent(".")
                .addContent(writer.property().name())
                .addContent(" = ")
                .addContent(valueExpression)
                .addContentLine(";");
        case SETTER -> builder.addContent(target)
                .addContent(".")
                .addContent(writer.method().elementName())
                .addContent("(")
                .addContent(valueExpression)
                .addContentLine(");");
        case GETTER -> throw new CodegenException("Getter cannot be used as a scalar writer: "
                                                          + writer.property().name());
        default -> throw new CodegenException("Unsupported scalar writer: " + writer.kind());
        }
    }

    private void appendCollectionImplementation(ContentBuilder<?> builder, TypeName collectionType) {
        if (collectionType.isSet()) {
            builder.addContent(LINKED_HASH_SET);
        } else if (collectionType.equals(TypeNames.COLLECTION) || collectionType.isList()) {
            builder.addContent(ARRAY_LIST);
        } else {
            builder.addContent(collectionType);
        }
    }

    private void appendKey(ContentBuilder<?> builder, NodeSpec node) {
        builder.addContent(TypeName.builder(TypeNames.LIST)
                                  .addTypeArgument(TypeNames.OBJECT)
                                  .build())
                .addContent(" key = ")
                .addContent(ARRAYS)
                .addContent(".asList(");
        for (int i = 0; i < node.keySources().size(); i++) {
            if (i > 0) {
                builder.addContent(", ");
            }
            builder.addContent("row.getObject(")
                    .addContent(literal(node.keySources().get(i)))
                    .addContent(")");
        }
        builder.addContentLine(");");
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

    private PropertyWriter writer(TypeInfo owner, PropertySpec property, TypedElementInfo methodInfo) {
        return directWritableField(owner, property)
                .map(field -> new PropertyWriter(WriterKind.FIELD, property, field, null))
                .or(() -> setter(owner, property)
                        .map(method -> new PropertyWriter(WriterKind.SETTER, property, null, method)))
                .orElseThrow(() -> new CodegenException("JDBC reducer cannot write property "
                                                                + property.name()
                                                                + " on "
                                                                + owner.typeName().fqName()
                                                                + " without reflection",
                                                        methodInfo.originatingElement()));
    }

    private PropertyWriter collectionWriter(TypeInfo owner, PropertySpec property, TypedElementInfo methodInfo) {
        return directReadableField(owner, property)
                .map(field -> new PropertyWriter(WriterKind.FIELD, property, field, null))
                .or(() -> getter(owner, property)
                        .map(method -> new PropertyWriter(WriterKind.GETTER, property, null, method)))
                .or(() -> setter(owner, property)
                        .map(method -> new PropertyWriter(WriterKind.SETTER, property, null, method)))
                .orElseThrow(() -> new CodegenException("JDBC reducer cannot populate collection property "
                                                                + property.name()
                                                                + " on "
                                                                + owner.typeName().fqName()
                                                                + " without reflection",
                                                        methodInfo.originatingElement()));
    }

    private Optional<TypedElementInfo> directWritableField(TypeInfo owner, PropertySpec property) {
        return owner.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.FIELD)
                .filter(it -> it.elementName().equals(property.name()))
                .filter(it -> !it.elementModifiers().contains(Modifier.STATIC))
                .filter(it -> !it.elementModifiers().contains(Modifier.FINAL))
                .filter(it -> accessible(owner, it.accessModifier()))
                .findFirst();
    }

    private Optional<TypedElementInfo> directReadableField(TypeInfo owner, PropertySpec property) {
        return owner.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.FIELD)
                .filter(it -> it.elementName().equals(property.name()))
                .filter(it -> !it.elementModifiers().contains(Modifier.STATIC))
                .filter(it -> accessible(owner, it.accessModifier()))
                .findFirst();
    }

    private Optional<TypedElementInfo> setter(TypeInfo owner, PropertySpec property) {
        String setterName = "set" + upperFirst(property.name());
        return owner.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.elementName().equals(setterName))
                .filter(it -> it.parameterArguments().size() == 1)
                .filter(it -> it.parameterArguments().getFirst().typeName().equals(property.type()))
                .filter(it -> accessible(owner, it.accessModifier()))
                .findFirst();
    }

    private Optional<TypedElementInfo> getter(TypeInfo owner, PropertySpec property) {
        String getterName = "get" + upperFirst(property.name());
        return owner.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.parameterArguments().isEmpty())
                .filter(it -> it.elementName().equals(property.name()) || it.elementName().equals(getterName))
                .filter(it -> it.typeName().equals(property.type()))
                .filter(it -> accessible(owner, it.accessModifier()))
                .findFirst();
    }

    private Optional<PropertySpec> property(TypeInfo owner, String name, TypedElementInfo methodInfo) {
        return recordComponents(owner)
                .stream()
                .filter(it -> it.elementName().equals(name))
                .findFirst()
                .or(() -> owner.elementInfo()
                        .stream()
                        .filter(it -> it.kind() == ElementKind.FIELD)
                        .filter(it -> it.elementName().equals(name))
                        .findFirst())
                .or(() -> getterElement(owner, name))
                .map(it -> property(owner, it, methodInfo));
    }

    private Optional<TypedElementInfo> getterElement(TypeInfo owner, String name) {
        String getterName = "get" + upperFirst(name);
        return owner.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.METHOD)
                .filter(it -> it.parameterArguments().isEmpty())
                .filter(it -> it.elementName().equals(name) || it.elementName().equals(getterName))
                .findFirst();
    }

    private PropertySpec property(TypeInfo owner, TypedElementInfo element, TypedElementInfo methodInfo) {
        TypeName propertyType = element.typeName();
        boolean collection = isCollection(propertyType);
        TypeName elementType = null;
        if (collection) {
            if (propertyType.typeArguments().isEmpty()) {
                throw new CodegenException("JDBC reducer collection property requires a generic element type: "
                                                   + owner.typeName().fqName()
                                                   + "."
                                                   + element.elementName(),
                                           methodInfo.originatingElement());
            }
            elementType = propertyType.typeArguments().getFirst();
        }
        return new PropertySpec(element.elementName(),
                                propertyType,
                                collection ? elementType : propertyType,
                                collection,
                                element);
    }

    private void validateNoArgConstructor(TypeInfo typeInfo, TypedElementInfo methodInfo) {
        List<TypedElementInfo> constructors = typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                .toList();
        if (constructors.isEmpty()) {
            return;
        }
        constructors.stream()
                .filter(it -> it.parameterArguments().isEmpty())
                .filter(it -> accessible(typeInfo, it.accessModifier()))
                .findFirst()
                .orElseThrow(() -> new CodegenException("JDBC reducer target class requires an accessible "
                                                                + "no-argument constructor: "
                                                                + typeInfo.typeName().fqName(),
                                                        methodInfo.originatingElement()));
    }

    private boolean accessible(TypeInfo owner, AccessModifier accessModifier) {
        if (accessModifier == AccessModifier.PUBLIC) {
            return true;
        }
        if (accessModifier == AccessModifier.PRIVATE) {
            return false;
        }
        return owner.typeName().packageName().equals(repositoryClassName.packageName());
    }

    private boolean validResultPath(TypeInfo root, String target) {
        if (target.isBlank()) {
            return false;
        }
        TypeInfo current = root;
        String[] parts = target.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Optional<PropertySpec> property = property(current,
                                                       parts[i],
                                                       TypedElementInfo.builder()
                                                               .kind(ElementKind.METHOD)
                                                               .elementName("<synthetic>")
                                                               .typeName(root.typeName())
                                                               .build());
            if (property.isEmpty()) {
                return false;
            }
            if (i + 1 == parts.length) {
                return true;
            }
            if (!property.get().collection()) {
                return false;
            }
            Optional<TypeInfo> next = typeInfo(property.get().elementType());
            if (next.isEmpty()) {
                return false;
            }
            current = next.get();
        }
        return false;
    }

    private void addMappings(Map<String, Mapping> mappings, Annotated annotated) {
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

    private void addMapping(Map<String, Mapping> mappings, Annotation annotation) {
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
        mappings.put(target, new Mapping(target, resolvedSource));
    }

    private boolean hasMappings(Annotated annotated) {
        return annotated.hasAnnotation(MAP_ANNOTATION) || annotated.hasAnnotation(MAPS_ANNOTATION);
    }

    private void addKeys(Map<String, List<String>> keys, Annotated annotated) {
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(KEY_ANNOTATION))
                .forEach(annotation -> addKey(keys, annotation));
        annotated.annotations()
                .stream()
                .filter(annotation -> annotation.typeName().equals(KEYS_ANNOTATION))
                .flatMap(annotation -> annotation.annotationValues().orElseGet(List::of).stream())
                .forEach(annotation -> addKey(keys, annotation));
    }

    private void addKey(Map<String, List<String>> keys, Annotation annotation) {
        List<String> value = annotation.stringValues().orElseGet(List::of);
        List<String> source = annotation.stringValues("source").orElseGet(List::of);
        if (!value.isEmpty() && !source.isEmpty()) {
            throw new CodegenException("@Data.Key must declare either value or source, not both");
        }
        List<String> resolved = !source.isEmpty() ? source : value;
        if (resolved.isEmpty()) {
            throw new CodegenException("@Data.Key source value is missing");
        }
        String target = annotation.stringValue("target").orElse("");
        if (keys.containsKey(target)) {
            throw new CodegenException("@Data.Key declares multiple reducer keys for target: " + target);
        }
        keys.put(target, List.copyOf(resolved));
    }

    private List<String> resultLabels(String statement) {
        List<String> result = new ArrayList<>();
        boolean singleQuoted = false;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '\'') {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (singleQuoted || !startsWithAs(statement, i)) {
                continue;
            }
            int index = i + 2;
            while (index < statement.length() && Character.isWhitespace(statement.charAt(index))) {
                index++;
            }
            if (index >= statement.length()) {
                continue;
            }
            if (statement.charAt(index) == '"') {
                Quoted quoted = quotedIdentifier(statement, index);
                if (quoted != null) {
                    result.add(quoted.value());
                    i = quoted.end();
                }
            } else {
                int start = index;
                while (index < statement.length() && isAliasChar(statement.charAt(index))) {
                    index++;
                }
                if (index > start) {
                    result.add(statement.substring(start, index));
                    i = index;
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean startsWithAs(String statement, int index) {
        if (index + 2 > statement.length()) {
            return false;
        }
        if (!statement.regionMatches(true, index, "as", 0, 2)) {
            return false;
        }
        boolean before = index == 0 || !isIdentifierChar(statement.charAt(index - 1));
        boolean after = index + 2 == statement.length() || !isIdentifierChar(statement.charAt(index + 2));
        return before && after;
    }

    private Quoted quotedIdentifier(String statement, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start + 1; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '"') {
                if (i + 1 < statement.length() && statement.charAt(i + 1) == '"') {
                    builder.append('"');
                    i++;
                } else {
                    return new Quoted(builder.toString(), i);
                }
            } else {
                builder.append(c);
            }
        }
        return null;
    }

    private boolean isAliasChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void assignAccumulatorNames(NodeSpec root, TypeName reducerType) {
        visit(root, node -> {
            String base = node.path().isEmpty()
                    ? "Root"
                    : node.path().replace(".", " ");
            node.accumulatorName(toClassName(base) + "Accumulator");
            node.methodSuffix(toClassName(node.propertyName()));
            node.mapName(node.propertyName() + "ByKey");
            node.accumulatorType(TypeName.builder(reducerType)
                                         .className(node.accumulatorName())
                                         .addEnclosingName(reducerType.className())
                                         .build());
        });
    }

    private TypeName reducerClassName(TypedElementInfo methodInfo) {
        return TypeName.builder(repositoryClassName)
                .className(reducerClassNamePrefix() + toClassName(methodInfo.elementName()) + "Reducer")
                .build();
    }

    private String reducerClassNamePrefix() {
        return repositoryClassName.className();
    }

    private String toClassName(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
                continue;
            }
            result.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        if (result.isEmpty()) {
            return "Value";
        }
        return result.toString();
    }

    private TypeName mapType(TypeName valueType) {
        return TypeName.builder(TypeNames.MAP)
                .addTypeArgument(TypeName.builder(TypeNames.LIST)
                                         .addTypeArgument(TypeNames.OBJECT)
                                         .build())
                .addTypeArgument(valueType)
                .build();
    }

    private boolean isCollection(TypeName typeName) {
        return typeName.isList() || typeName.isSet() || typeName.equals(TypeNames.COLLECTION);
    }

    private List<TypedElementInfo> recordComponents(TypeInfo typeInfo) {
        return typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
    }

    private Optional<TypeInfo> typeInfo(TypeName typeName) {
        return roundContext.typeInfo(typeName)
                .or(() -> codegenContext.typeInfo(typeName));
    }

    private void visit(NodeSpec node, java.util.function.Consumer<NodeSpec> consumer) {
        consumer.accept(node);
        node.children().values().forEach(child -> visit(child, consumer));
    }

    private String childPath(String path, String child) {
        if (path.isEmpty()) {
            return child;
        }
        return path + "." + child;
    }

    private String upperFirst(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
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

    private enum WriterKind {
        FIELD,
        GETTER,
        SETTER
    }

    private record PropertyWriter(WriterKind kind,
                                  PropertySpec property,
                                  TypedElementInfo field,
                                  TypedElementInfo method) {
    }

    private record PropertySpec(String name,
                                TypeName type,
                                TypeName valueType,
                                boolean collection,
                                TypedElementInfo element) {

        TypeName elementType() {
            return valueType;
        }
    }

    private record ScalarSpec(PropertySpec property, String source) {
    }

    private record Mapping(String target, String source) {
    }

    private record MappingMetadata(Map<String, Mapping> mappings,
                                   Map<String, List<String>> keys,
                                   Optional<TypeInfo> reduceWith) {
    }

    private record ReducerModel(NodeSpec root, TypeName reducerType, boolean hasCollections) {
    }

    private record Quoted(String value, int end) {
    }

    private static final class NodeSpec {
        private final String path;
        private final String propertyName;
        private final TypeName type;
        private final TypeInfo typeInfo;
        private final NodeSpec parent;
        private final PropertySpec property;
        private final boolean collection;
        private final TypeName collectionType;
        private final Map<String, ScalarSpec> scalars = new LinkedHashMap<>();
        private final Map<String, NodeSpec> children = new LinkedHashMap<>();
        private List<String> keySources = List.of();
        private String accumulatorName;
        private String methodSuffix;
        private String mapName;
        private TypeName accumulatorType;

        private NodeSpec(String path,
                         String propertyName,
                         TypeName type,
                         TypeInfo typeInfo,
                         NodeSpec parent,
                         PropertySpec property,
                         boolean collection,
                         TypeName collectionType) {
            this.path = path;
            this.propertyName = propertyName;
            this.type = type;
            this.typeInfo = typeInfo;
            this.parent = parent;
            this.property = property;
            this.collection = collection;
            this.collectionType = collectionType;
        }

        String path() {
            return path;
        }

        String propertyName() {
            return propertyName;
        }

        TypeName type() {
            return type;
        }

        TypeInfo typeInfo() {
            return typeInfo;
        }

        PropertySpec property() {
            return property;
        }

        TypeName collectionType() {
            return collectionType;
        }

        Map<String, ScalarSpec> scalars() {
            return scalars;
        }

        Map<String, NodeSpec> children() {
            return children;
        }

        List<String> keySources() {
            return keySources;
        }

        void keySources(List<String> keySources) {
            this.keySources = List.copyOf(keySources);
        }

        String accumulatorName() {
            return accumulatorName;
        }

        void accumulatorName(String accumulatorName) {
            this.accumulatorName = accumulatorName;
        }

        String methodSuffix() {
            return methodSuffix;
        }

        void methodSuffix(String methodSuffix) {
            this.methodSuffix = methodSuffix;
        }

        String mapName() {
            return mapName;
        }

        void mapName(String mapName) {
            this.mapName = mapName;
        }

        TypeName accumulatorType() {
            return accumulatorType;
        }

        void accumulatorType(TypeName accumulatorType) {
            this.accumulatorType = accumulatorType;
        }
    }
}
