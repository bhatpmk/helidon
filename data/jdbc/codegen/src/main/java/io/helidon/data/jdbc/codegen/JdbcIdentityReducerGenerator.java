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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.classmodel.Annotation;
import io.helidon.codegen.classmodel.ClassModel;
import io.helidon.codegen.classmodel.InnerClass;
import io.helidon.codegen.classmodel.Method;
import io.helidon.codegen.classmodel.Parameter;
import io.helidon.common.types.AccessModifier;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

/**
 * Generates identity reducers that assemble immutable record graphs from joined rows.
 */
final class JdbcIdentityReducerGenerator {
    private static final TypeName ARRAY_LIST = TypeName.create(ArrayList.class);
    private static final TypeName DATA_EXCEPTION = TypeName.create("io.helidon.data.DataException");
    private static final TypeName LINKED_HASH_MAP = TypeName.create(LinkedHashMap.class);
    private static final TypeName LIST = TypeName.create(List.class);
    private static final TypeName NO_RESULT = TypeName.create("io.helidon.data.NoResultException");
    private static final TypeName NON_UNIQUE = TypeName.create("io.helidon.data.NonUniqueResultException");
    private static final TypeName OBJECTS = TypeName.create(Objects.class);
    private static final TypeName OPTIONAL = TypeName.create("java.util.Optional");
    private static final TypeName ROW = TypeName.create("io.helidon.data.jdbc.JdbcClient.Row");

    private JdbcIdentityReducerGenerator() {
    }

    /**
     * Generates one fresh reducer type for the repository method.
     *
     * @param plan       validated method plan
     * @param classModel generated repository model
     * @param context    code generation context
     */
    static void generate(JdbcMethodPlan plan,
                         ClassModel.Builder classModel,
                         CodegenContext context) {
        Scope root = model(plan, context);
        classModel.addInnerClass(inner -> generateReducer(plan, root, inner));
    }

    /**
     * Resolves the complete record graph, projection labels, and identity paths before emitting source.
     */
    private static Scope model(JdbcMethodPlan plan, CodegenContext context) {
        if (plan.aliases().isEmpty()) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "@Jdbc.IdentityReducer requires explicit SQL projection aliases");
        }

        Map<String, Scope> scopes = new LinkedHashMap<>();
        Scope root = new Scope("", plan.mappedType(), "");
        buildScope(plan, context, root, scopes, new HashSet<>());
        validateAliases(plan, scopes.values());
        resolveIdentities(plan, scopes);

        for (Scope scope : scopes.values()) {
            String typePrefix = scope.path.isEmpty() ? "Root" : classPrefix(scope.path);
            scope.variable = scope.path.isEmpty() ? "root" : variable(scope.path);
            scope.accumulatorType = localType(typePrefix + "Accumulator");
            if (scope.identities.size() == 1) {
                scope.keyType = scope.identities.getFirst().type.boxed();
            } else {
                scope.keyType = localType(typePrefix + "Identity");
                scope.keyClassName = typePrefix + "Identity";
            }
        }
        return root;
    }

    /**
     * Walks canonical record components so generated constructor arguments retain declaration order.
     */
    private static void buildScope(JdbcMethodPlan plan,
                                   CodegenContext context,
                                   Scope scope,
                                   Map<String, Scope> scopes,
                                   Set<TypeName> ancestors) {
        TypeName rawType = scope.type.genericTypeName();
        if (!ancestors.add(rawType)) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Recursive record graph is not supported at path '" + location(scope) + "'");
        }
        TypeInfo recordInfo = recordInfo(plan, context, scope.type);
        scopes.put(scope.path, scope);
        List<TypedElementInfo> components = recordInfo.elementInfo()
                .stream()
                .filter(element -> element.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
        for (TypedElementInfo component : components) {
            String name = component.elementName();
            TypeName type = component.typeName();
            scope.componentOrder.add(name);
            if (JdbcMethodPlan.isScalar(type)) {
                String path = childPath(scope.path, name);
                scope.properties.put(name, new Property(name, path, type, type, false));
                continue;
            }
            TypeName optionalScalarType = JdbcMethodPlan.optionalScalarType(type);
            if (optionalScalarType != null) {
                String path = childPath(scope.path, name);
                scope.properties.put(name, new Property(name, path, type, optionalScalarType, true));
                continue;
            }
            if (!type.genericTypeName().equals(LIST)
                    || type.typeArguments().size() != 1
                    || type.typeArguments().getFirst().wildcard()) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Identity-reduced record component must be a supported scalar, exact "
                                                     + "Optional<scalar>, or exact List<Record>: "
                                                     + childPath(scope.path, name));
            }
            TypeName childType = type.typeArguments().getFirst();
            Scope child = new Scope(childPath(scope.path, name), childType, name);
            scope.children.put(name, child);
            buildScope(plan, context, child, scopes, ancestors);
        }
        ancestors.remove(rawType);
    }

    /**
     * Requires a complete, exact property-path projection for canonical record construction.
     */
    private static void validateAliases(JdbcMethodPlan plan, Iterable<Scope> scopes) {
        Set<String> projected = new HashSet<>(plan.aliases());
        Set<String> expected = new HashSet<>();
        for (Scope scope : scopes) {
            for (Property property : scope.properties.values()) {
                expected.add(property.path);
                if (!projected.contains(property.path)) {
                    throw JdbcMethodPlan.failure(plan.method(),
                                                 "SQL projection is missing record component alias: " + property.path);
                }
            }
        }
        for (String alias : projected) {
            if (!expected.contains(alias)) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "SQL projection alias does not match an identity-reduced record component: "
                                                     + alias);
            }
        }
    }

    /**
     * Groups identity components by their containing object scope while retaining annotation order.
     */
    private static void resolveIdentities(JdbcMethodPlan plan, Map<String, Scope> scopes) {
        for (String identityPath : plan.identityPaths()) {
            int separator = identityPath.lastIndexOf('.');
            String scopePath = separator < 0 ? "" : identityPath.substring(0, separator);
            String propertyName = separator < 0 ? identityPath : identityPath.substring(separator + 1);
            Scope scope = scopes.get(scopePath);
            if (scope == null) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Identity path does not resolve to a record scope: " + identityPath);
            }
            Property property = scope.properties.get(propertyName);
            if (property == null) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Identity path does not resolve to a scalar record component: "
                                                     + identityPath);
            }
            if (property.optional) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Optional record components cannot be identity values: " + identityPath);
            }
            if (property.type.array()) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Array record components cannot be identity values: " + identityPath);
            }
            scope.identities.add(property);
        }
        for (Scope scope : scopes.values()) {
            if (scope.identities.isEmpty()) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "@Jdbc.IdentityReducer requires an identity for record scope '"
                                                     + location(scope) + "'");
            }
        }
    }

    /**
     * Emits the reducer, its strongly typed identity keys, and its immutable graph accumulators.
     */
    private static void generateReducer(JdbcMethodPlan plan,
                                        Scope root,
                                        InnerClass.Builder inner) {
        TypeName reducerType = TypeName.builder(JdbcCodegenTypes.ROW_REDUCER)
                .addTypeArgument(plan.method().typeName())
                .build();
        inner.name(plan.mapperFieldName())
                .classType(ElementKind.CLASS)
                .accessModifier(AccessModifier.PRIVATE)
                .isStatic(true)
                .isFinal(true)
                .addInterface(reducerType);

        for (Scope scope : scopes(root)) {
            if (scope.keyClassName != null) {
                addIdentityRecord(inner, scope);
            }
        }
        for (Scope scope : scopes(root)) {
            addAccumulator(inner, scope);
        }
        addRootMap(inner, root);
        inner.addMethod(method -> generateAccept(root, method));
        inner.addMethod(method -> generateFinish(plan, root, method));
    }

    /**
     * Emits a private record key when a scope declares a composite identity.
     */
    private static void addIdentityRecord(InnerClass.Builder owner, Scope scope) {
        owner.addInnerClass(key -> {
            key.name(scope.keyClassName)
                    .classType(ElementKind.RECORD)
                    .accessModifier(AccessModifier.PRIVATE)
                    .isStatic(true);
            for (Property identity : keyProperties(scope)) {
                key.addField(field -> field.name(identity.name)
                        .type(identity.type.boxed()));
            }
        });
    }

    /**
     * Emits one mutable implementation-only accumulator for each immutable record scope.
     */
    private static void addAccumulator(InnerClass.Builder owner, Scope scope) {
        owner.addInnerClass(accumulator -> {
            accumulator.name(scope.accumulatorType.className())
                    .classType(ElementKind.CLASS)
                    .accessModifier(AccessModifier.PRIVATE)
                    .isStatic(true)
                    .isFinal(true);
            for (Property property : scope.properties.values()) {
                accumulator.addField(field -> field.name(property.name)
                        .type(property.type)
                        .isFinal(true));
            }
            for (Scope child : scope.children.values()) {
                TypeName mapType = TypeName.builder(LINKED_HASH_MAP)
                        .addTypeArgument(child.keyType)
                        .addTypeArgument(child.accumulatorType)
                        .build();
                accumulator.addField(field -> field.name(childMapField(child))
                        .type(mapType)
                        .isFinal(true)
                        .addContent("new ")
                        .addContent(LINKED_HASH_MAP)
                        .addContent("<>()"));
            }
            accumulator.addConstructor(constructor -> {
                constructor.accessModifier(AccessModifier.PRIVATE);
                for (Property property : scope.properties.values()) {
                    constructor.addParameter(parameter -> parameter.name(property.name).type(property.type));
                    constructor.addContent("this.")
                            .addContent(property.name)
                            .addContent(" = ")
                            .addContent(property.name)
                            .addContentLine(";");
                }
            });
            accumulator.addMethod(method -> generateRecordConstruction(scope, method));
        });
    }

    /**
     * Converts a completed accumulator subtree to immutable records and immutable child lists.
     */
    private static void generateRecordConstruction(Scope scope, Method.Builder method) {
        method.name("toValue")
                .accessModifier(AccessModifier.PRIVATE)
                .returnType(scope.type);
        for (Scope child : scope.children.values()) {
            String values = child.componentName + "Values";
            method.addContent("var ")
                    .addContent(values)
                    .addContent(" = new ")
                    .addContent(ARRAY_LIST)
                    .addContent("<")
                    .addContent(child.type)
                    .addContent(">(")
                    .addContent(childMapField(child))
                    .addContentLine(".size());")
                    .addContent("for (var value : ")
                    .addContent(childMapField(child))
                    .addContentLine(".values()) {")
                    .addContent(values)
                    .addContentLine(".add(value.toValue());")
                    .addContentLine("}");
        }
        method.addContent("return new ")
                .addContent(scope.type)
                .addContent("(");
        for (int i = 0; i < scope.componentOrder.size(); i++) {
            if (i > 0) {
                method.addContent(", ");
            }
            String component = scope.componentOrder.get(i);
            if (scope.properties.containsKey(component)) {
                method.addContent(component);
            } else {
                method.addContent(LIST)
                        .addContent(".copyOf(")
                        .addContent(component)
                        .addContent("Values)");
            }
        }
        method.addContentLine(");");
    }

    /**
     * Stores logical roots in SQL encounter order.
     */
    private static void addRootMap(InnerClass.Builder inner, Scope root) {
        TypeName mapType = TypeName.builder(LINKED_HASH_MAP)
                .addTypeArgument(root.keyType)
                .addTypeArgument(root.accumulatorType)
                .build();
        inner.addField(field -> field.name("rootsByIdentity")
                .type(mapType)
                .isFinal(true)
                .addContent("new ")
                .addContent(LINKED_HASH_MAP)
                .addContent("<>()"));
    }

    /**
     * Emits row accumulation with parent-scoped identity lookup and conflict validation.
     */
    private static void generateAccept(Scope root, Method.Builder method) {
        method.name("accept")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.PRIMITIVE_VOID)
                .addAnnotation(Annotation.create(Override.class))
                .addParameter(Parameter.builder().name("row").type(ROW).build());
        addIdentityReads(method, root);
        addRequiredIdentityCheck(method, root);
        addNonIdentityReads(method, root);
        addKey(method, root);
        String accumulator = accumulatorVariable(root);
        method.addContent(root.accumulatorType)
                .addContent(" ")
                .addContent(accumulator)
                .addContent(" = rootsByIdentity.get(")
                .addContent(keyVariable(root))
                .addContentLine(");")
                .addContent("if (")
                .addContent(accumulator)
                .addContentLine(" == null) {");
        addCreateAccumulator(method, root, "rootsByIdentity");
        if (hasProjectedValues(root)) {
            method.addContentLine("} else {");
            addValidateExisting(method, root);
        }
        method.addContentLine("}");
        for (Scope child : root.children.values()) {
            addChild(method, child, accumulator);
        }
    }

    /**
     * Emits one child scope. An all-null identity means an absent outer-join child.
     */
    private static void addChild(Method.Builder method, Scope child, String parentAccumulator) {
        addIdentityReads(method, child);
        method.addContent("if (");
        addAllNull(method, child);
        method.addContentLine(") {");
        addRejectDescendants(method, child);
        method.addContentLine("} else {");
        addPartialIdentityCheck(method, child);
        addNonIdentityReads(method, child);
        addKey(method, child);
        String childMap = parentAccumulator + "." + childMapField(child);
        String accumulator = accumulatorVariable(child);
        method.addContent(child.accumulatorType)
                .addContent(" ")
                .addContent(accumulator)
                .addContent(" = ")
                .addContent(childMap)
                .addContent(".get(")
                .addContent(keyVariable(child))
                .addContentLine(");")
                .addContent("if (")
                .addContent(accumulator)
                .addContentLine(" == null) {");
        addCreateAccumulator(method, child, childMap);
        if (hasProjectedValues(child)) {
            method.addContentLine("} else {");
            addValidateExisting(method, child);
        }
        method.addContentLine("}");
        for (Scope nested : child.children.values()) {
            addChild(method, nested, accumulator);
        }
        method.addContentLine("}");
    }

    /**
     * Reads identity components as nullable boxed values so composite null state can be validated explicitly.
     */
    private static void addIdentityReads(Method.Builder method, Scope scope) {
        for (Property identity : scope.identities) {
            method.addContent(identity.type.boxed())
                    .addContent(" ")
                    .addContent(valueVariable(scope, identity))
                    .addContent(" = ");
            addRowRead(method, identity, false);
            method.addContentLine(";");
        }
    }

    /**
     * Rejects null root identities because a root row cannot represent an absent object.
     */
    private static void addRequiredIdentityCheck(Method.Builder method, Scope scope) {
        method.addContent("if (");
        addAnyNull(method, scope);
        method.addContentLine(") {")
                .addContent("throw new ")
                .addContent(DATA_EXCEPTION)
                .addContent("(")
                .addContentLiteral("Identity for record scope '" + location(scope) + "' must not be null")
                .addContentLine(");")
                .addContentLine("}");
    }

    /**
     * Rejects a composite child identity when only some components are null.
     */
    private static void addPartialIdentityCheck(Method.Builder method, Scope scope) {
        if (scope.identities.size() == 1) {
            return;
        }
        method.addContent("if (");
        addAnyNull(method, scope);
        method.addContentLine(") {")
                .addContent("throw new ")
                .addContent(DATA_EXCEPTION)
                .addContent("(")
                .addContentLiteral("Identity for record scope '" + location(scope) + "' is partially null")
                .addContentLine(");")
                .addContentLine("}");
    }

    /**
     * Reads non-identity scalar values once for both construction and repeated-row validation.
     */
    private static void addNonIdentityReads(Method.Builder method, Scope scope) {
        for (Property property : scope.properties.values()) {
            if (scope.identities.contains(property)) {
                continue;
            }
            method.addContent(property.type)
                    .addContent(" ")
                    .addContent(valueVariable(scope, property))
                    .addContent(" = ");
            addRowRead(method, property, !property.optional);
            method.addContentLine(";");
        }
    }

    /**
     * Creates a scalar key directly or a generated record key for a composite identity.
     */
    private static void addKey(Method.Builder method, Scope scope) {
        method.addContent(scope.keyType)
                .addContent(" ")
                .addContent(keyVariable(scope))
                .addContent(" = ");
        if (scope.identities.size() == 1) {
            method.addContent(valueVariable(scope, scope.identities.getFirst()));
        } else {
            method.addContent("new ")
                    .addContent(scope.keyType)
                    .addContent("(");
            List<Property> keyProperties = keyProperties(scope);
            for (int i = 0; i < keyProperties.size(); i++) {
                if (i > 0) {
                    method.addContent(", ");
                }
                method.addContent(valueVariable(scope, keyProperties.get(i)));
            }
            method.addContent(")");
        }
        method.addContentLine(";");
    }

    /**
     * Creates one accumulator and inserts it into the applicable parent-scoped identity map.
     */
    private static void addCreateAccumulator(Method.Builder method, Scope scope, String map) {
        String accumulator = accumulatorVariable(scope);
        method.addContent(accumulator)
                .addContent(" = new ")
                .addContent(scope.accumulatorType)
                .addContent("(");
        int index = 0;
        for (String component : scope.componentOrder) {
            Property property = scope.properties.get(component);
            if (property == null) {
                continue;
            }
            if (index++ > 0) {
                method.addContent(", ");
            }
            method.addContent(valueVariable(scope, property));
        }
        method.addContentLine(");")
                .addContent(map)
                .addContent(".put(")
                .addContent(keyVariable(scope))
                .addContent(", ")
                .addContent(accumulator)
                .addContentLine(");");
    }

    /**
     * Ensures repeated physical rows do not assign different scalar values to one logical identity.
     */
    private static void addValidateExisting(Method.Builder method, Scope scope) {
        String accumulator = accumulatorVariable(scope);
        for (Property property : scope.properties.values()) {
            if (scope.identities.contains(property)) {
                continue;
            }
            method.addContent("if (!")
                    .addContent(OBJECTS)
                    .addContent(".deepEquals(")
                    .addContent(accumulator)
                    .addContent(".")
                    .addContent(property.name)
                    .addContent(", ")
                    .addContent(valueVariable(scope, property))
                    .addContentLine(")) {")
                    .addContent("throw new ")
                    .addContent(DATA_EXCEPTION)
                    .addContent("(")
                    .addContentLiteral("Conflicting projected value for record scope '" + location(scope)
                                               + "' property '" + property.name + "'")
                    .addContentLine(");")
                    .addContentLine("}");
        }
    }

    /**
     * Reports whether repeated rows have projected values to compare beyond the identity itself.
     */
    private static boolean hasProjectedValues(Scope scope) {
        return scope.properties.values().stream().anyMatch(property -> !scope.identities.contains(property));
    }

    /**
     * Rejects a projected descendant identity when an outer join omitted its ancestor.
     */
    private static void addRejectDescendants(Method.Builder method, Scope ancestor) {
        for (Scope descendant : descendants(ancestor)) {
            method.addContent("if (");
            for (int i = 0; i < descendant.identities.size(); i++) {
                if (i > 0) {
                    method.addContent(" || ");
                }
                addRowRead(method, descendant.identities.get(i), false);
                method.addContent(" != null");
            }
            method.addContentLine(") {")
                    .addContent("throw new ")
                    .addContent(DATA_EXCEPTION)
                    .addContent("(")
                    .addContentLiteral("Record scope '" + descendant.path
                                               + "' has an identity while ancestor scope '"
                                               + location(ancestor) + "' is absent")
                    .addContentLine(");")
                    .addContentLine("}");
        }
    }

    /**
     * Emits a null conjunction for one scope identity.
     */
    private static void addAllNull(Method.Builder method, Scope scope) {
        for (int i = 0; i < scope.identities.size(); i++) {
            if (i > 0) {
                method.addContent(" && ");
            }
            method.addContent(valueVariable(scope, scope.identities.get(i)))
                    .addContent(" == null");
        }
    }

    /**
     * Emits a null disjunction for one scope identity.
     */
    private static void addAnyNull(Method.Builder method, Scope scope) {
        for (int i = 0; i < scope.identities.size(); i++) {
            if (i > 0) {
                method.addContent(" || ");
            }
            method.addContent(valueVariable(scope, scope.identities.get(i)))
                    .addContent(" == null");
        }
    }

    /**
     * Emits a callback-scoped row read without exposing JDBC resources.
     */
    private static void addRowRead(Method.Builder method, Property property, boolean required) {
        method.addContent("row.")
                .addContent(required ? "required(" : "optional(")
                .addContentLiteral(property.path)
                .addContent(", ")
                .addContent(property.scalarType.boxed())
                .addContent(".class)");
        if (!required && !property.optional) {
            // Identity reads need a nullable local value to distinguish an absent outer-join scope.
            method.addContent(".orElse(null)");
        }
    }

    /**
     * Applies repository cardinality to completed logical roots rather than physical rows.
     */
    private static void generateFinish(JdbcMethodPlan plan, Scope root, Method.Builder method) {
        method.name("finish")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(plan.method().typeName())
                .addAnnotation(Annotation.create(Override.class));
        switch (plan.returnShape()) {
        case LIST -> method.addContent("var values = new ")
                .addContent(ARRAY_LIST)
                .addContent("<")
                .addContent(root.type)
                .addContentLine(">(rootsByIdentity.size());")
                .addContentLine("for (var root : rootsByIdentity.values()) {")
                .addContentLine("values.add(root.toValue());")
                .addContentLine("}")
                .addContent("return ")
                .addContent(LIST)
                .addContentLine(".copyOf(values);");
        case OPTIONAL -> addMaximumOneCheck(method)
                .addContent("return rootsByIdentity.isEmpty() ? ")
                .addContent(OPTIONAL)
                .addContent(".empty() : ")
                .addContent(OPTIONAL)
                .addContentLine(".of(rootsByIdentity.sequencedValues().getFirst().toValue());");
        case ITEM -> method.addContentLine("if (rootsByIdentity.isEmpty()) {")
                .addContent("throw new ")
                .addContent(NO_RESULT)
                .addContentLine("(\"JDBC identity-reduced query returned no logical roots\");")
                .addContentLine("}");
        default -> throw JdbcMethodPlan.failure(plan.method(),
                                                "@Jdbc.IdentityReducer supports item, optional, and list results only");
        }
        if (plan.returnShape() == JdbcMethodPlan.ReturnShape.ITEM) {
            addMaximumOneCheck(method)
                    .addContentLine("return rootsByIdentity.sequencedValues().getFirst().toValue();");
        }
    }

    /**
     * Rejects more than one logical root for direct and optional repository results.
     */
    private static Method.Builder addMaximumOneCheck(Method.Builder method) {
        return method.addContentLine("if (rootsByIdentity.size() > 1) {")
                .addContent("throw new ")
                .addContent(NON_UNIQUE)
                .addContentLine("(\"JDBC identity-reduced query returned more than one logical root\");")
                .addContentLine("}");
    }

    /**
     * Resolves and validates one record type for direct construction by generated code.
     */
    private static TypeInfo recordInfo(JdbcMethodPlan plan, CodegenContext context, TypeName type) {
        TypeInfo info = context.typeInfo(type.genericTypeName())
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Record type information is unavailable: "
                                                                  + type.resolvedName()));
        if (info.kind() != ElementKind.RECORD) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "@Jdbc.IdentityReducer requires records at every record scope: "
                                                 + type.resolvedName());
        }
        boolean samePackage = plan.method().enclosingType()
                .map(TypeName::packageName)
                .filter(info.typeName().packageName()::equals)
                .isPresent();
        if (info.accessModifier() != AccessModifier.PUBLIC
                && !(samePackage && (info.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || info.accessModifier() == AccessModifier.PROTECTED))) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Record type is not accessible to generated code: "
                                                 + info.typeName().resolvedName());
        }
        return info;
    }

    private static List<Scope> scopes(Scope root) {
        List<Scope> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static List<Scope> descendants(Scope root) {
        List<Scope> result = new ArrayList<>();
        for (Scope child : root.children.values()) {
            collect(child, result);
        }
        return result;
    }

    private static void collect(Scope scope, List<Scope> result) {
        result.add(scope);
        for (Scope child : scope.children.values()) {
            collect(child, result);
        }
    }

    private static String childPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private static String location(Scope scope) {
        return scope.path.isEmpty() ? "root" : scope.path;
    }

    private static String variable(String path) {
        return path.replace('.', '_');
    }

    private static String classPrefix(String path) {
        StringBuilder result = new StringBuilder(path.length());
        for (String segment : path.split("\\.")) {
            if (!result.isEmpty()) {
                result.append('_');
            }
            result.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
        }
        return result.toString();
    }

    private static TypeName localType(String name) {
        return TypeName.builder().className(name).build();
    }

    /**
     * Returns composite-key components in the order used by the class model for record components.
     *
     * @param scope record scope
     * @return consistently ordered identity components
     */
    private static List<Property> keyProperties(Scope scope) {
        return scope.identities.stream()
                .sorted(Comparator.comparing(Property::name))
                .toList();
    }

    private static String childMapField(Scope child) {
        return child.componentName + "ByIdentity";
    }

    private static String accumulatorVariable(Scope scope) {
        return scope.variable + "Accumulator";
    }

    private static String keyVariable(Scope scope) {
        return scope.variable + "Identity";
    }

    private static String valueVariable(Scope scope, Property property) {
        return scope.variable + Character.toUpperCase(property.name.charAt(0)) + property.name.substring(1) + "Value";
    }

    private record Property(String name,
                            String path,
                            TypeName type,
                            TypeName scalarType,
                            boolean optional) {
    }

    private static final class Scope {
        private final String path;
        private final TypeName type;
        private final String componentName;
        private final List<String> componentOrder = new ArrayList<>();
        private final Map<String, Property> properties = new LinkedHashMap<>();
        private final Map<String, Scope> children = new LinkedHashMap<>();
        private final List<Property> identities = new ArrayList<>();
        private TypeName keyType;
        private TypeName accumulatorType;
        private String keyClassName;
        private String variable;

        private Scope(String path, TypeName type, String componentName) {
            this.path = path;
            this.type = type;
            this.componentName = componentName;
        }
    }
}
