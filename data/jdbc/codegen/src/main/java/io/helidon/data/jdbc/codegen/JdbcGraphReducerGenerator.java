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
import java.util.HashMap;
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
import io.helidon.common.types.Modifier;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

/**
 * Generates identity-defined reducers for joined mutable-object graphs.
 */
final class JdbcGraphReducerGenerator {
    private static final TypeName ARRAY_LIST = TypeName.create(ArrayList.class);
    private static final TypeName DATA_EXCEPTION = TypeName.create("io.helidon.data.DataException");
    private static final TypeName IDENTITY_HASH_MAP = TypeName.create("java.util.IdentityHashMap");
    private static final TypeName LINKED_HASH_MAP = TypeName.create(LinkedHashMap.class);
    private static final TypeName LIST = TypeName.create(List.class);
    private static final TypeName NO_RESULT = TypeName.create("io.helidon.data.NoResultException");
    private static final TypeName NON_UNIQUE = TypeName.create("io.helidon.data.NonUniqueResultException");
    private static final TypeName OBJECTS = TypeName.create(Objects.class);
    private static final TypeName OPTIONAL = TypeName.create("java.util.Optional");
    private static final TypeName ROW = TypeName.create("io.helidon.data.jdbc.JdbcClient.Row");

    private JdbcGraphReducerGenerator() {
    }

    static void generate(JdbcMethodPlan plan,
                         ClassModel.Builder classModel,
                         CodegenContext context) {
        Scope root = model(plan, context);
        classModel.addInnerClass(inner -> generateReducer(plan, root, inner));
    }

    private static Scope model(JdbcMethodPlan plan, CodegenContext context) {
        if (plan.aliases().isEmpty()) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Graph reduction requires explicit SQL projection aliases");
        }
        Set<String> distinctAliases = new HashSet<>();
        Scope root = new Scope("", plan.mappedType(), null, null);
        Map<String, JdbcMethodPlan.BeanMapping> declarations = beanDeclarations(plan);
        for (String alias : plan.aliases()) {
            if (!distinctAliases.add(alias)) {
                throw JdbcMethodPlan.failure(plan.method(), "Duplicate SQL projection alias: " + alias);
            }
            String[] path = alias.split("\\.", -1);
            for (String segment : path) {
                if (!javaIdentifier(segment)) {
                    throw JdbcMethodPlan.failure(plan.method(), "Invalid graph property path: " + alias);
                }
            }
            Scope current = root;
            for (int i = 0; i < path.length - 1; i++) {
                String property = path[i];
                Scope child = current.children.get(property);
                if (child == null) {
                    Accessors collection = collectionAccessors(plan, context, current.type, property);
                    TypeName childType = collection.setter.parameterArguments()
                            .getFirst()
                            .typeName()
                            .typeArguments()
                            .getFirst();
                    String prefix = current.prefix.isEmpty() ? property : current.prefix + "." + property;
                    child = new Scope(prefix, childType, current, collection);
                    current.children.put(property, child);
                }
                current = child;
            }
            String property = path[path.length - 1];
            Accessors accessors = scalarAccessors(plan, context, current.type, property);
            if (current.properties.put(property, new Property(alias, accessors)) != null) {
                throw JdbcMethodPlan.failure(plan.method(), "Duplicate graph property path: " + alias);
            }
        }

        List<Scope> scopes = new ArrayList<>();
        collect(root, scopes);
        for (Scope scope : scopes) {
            TypeInfo beanInfo = typeInfo(plan, context, scope.type);
            if (beanInfo.kind() == ElementKind.RECORD) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Graph reduction requires mutable beans, not records: "
                                                     + scope.type.resolvedName());
            }
            JdbcBeanMapperGenerator.validateConstructor(plan, beanInfo);
            scope.variable = scope.prefix.isEmpty() ? "root" : variable(scope.prefix);
            scope.mapField = scope.prefix.isEmpty() ? "roots" : scope.variable + "ByParent";
        }
        validateDeclarations(plan, declarations, scopes);
        for (Scope scope : scopes) {
            JdbcMethodPlan.BeanMapping declaration = declarations.get(scope.prefix);
            String identityName = declaration.identity();
            String location = scope.prefix.isEmpty() ? "root" : scope.prefix;
            if (identityName.isBlank() || !javaIdentifier(identityName)) {
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Graph @Data.BeanMapper for scope '" + location
                                                     + "' requires a nonblank local identity property");
            }
            Property identity = scope.properties.get(identityName);
            if (identity == null) {
                String alias = scope.prefix.isEmpty() ? identityName : scope.prefix + "." + identityName;
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Graph identity property is not projected with alias '" + alias + "'");
            }
            scope.identity = identity;
        }
        return root;
    }

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

        addRootMap(inner, root);
        for (Scope child : descendants(root)) {
            addChildMap(inner, child);
        }
        inner.addMethod(method -> generateAccept(root, method));
        inner.addMethod(method -> generateFinish(plan, root, method));
    }

    private static void addRootMap(InnerClass.Builder inner, Scope root) {
        TypeName mapType = TypeName.builder(LINKED_HASH_MAP)
                .addTypeArgument(root.identity.type().boxed())
                .addTypeArgument(root.type)
                .build();
        inner.addField(field -> field.name(root.mapField)
                .type(mapType)
                .isFinal(true)
                .addContent("new ")
                .addContent(LINKED_HASH_MAP)
                .addContent("<>()"));
    }

    private static void addChildMap(InnerClass.Builder inner, Scope child) {
        TypeName values = TypeName.builder(LINKED_HASH_MAP)
                .addTypeArgument(child.identity.type().boxed())
                .addTypeArgument(child.type)
                .build();
        TypeName mapType = TypeName.builder(IDENTITY_HASH_MAP)
                .addTypeArgument(child.parent.type)
                .addTypeArgument(values)
                .build();
        inner.addField(field -> field.name(child.mapField)
                .type(mapType)
                .isFinal(true)
                .addContent("new ")
                .addContent(IDENTITY_HASH_MAP)
                .addContent("<>()"));
    }

    private static void generateAccept(Scope root, Method.Builder method) {
        method.name("accept")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(TypeNames.PRIMITIVE_VOID)
                .addAnnotation(Annotation.create(Override.class))
                .addParameter(Parameter.builder().name("row").type(ROW).build());
        addIdentityRead(method, root, true);
        method.addContent(root.type)
                .addContent(" ")
                .addContent(root.variable)
                .addContent(" = ")
                .addContent(root.mapField)
                .addContent(".get(")
                .addContent(identityVariable(root))
                .addContentLine(");")
                .addContent("if (")
                .addContent(root.variable)
                .addContentLine(" == null) {");
        addCreate(method, root);
        method.addContent(root.mapField)
                .addContent(".put(")
                .addContent(identityVariable(root))
                .addContent(", ")
                .addContent(root.variable)
                .addContentLine(");")
                .addContentLine("} else {");
        addValidateExisting(method, root);
        method.addContentLine("}");
        for (Scope child : root.children.values()) {
            addChild(method, child, root.variable);
        }
    }

    private static void addChild(Method.Builder method, Scope child, String parentVariable) {
        addIdentityRead(method, child, false);
        method.addContent("if (")
                .addContent(identityVariable(child))
                .addContentLine(" != null) {")
                .addContent("var ")
                .addContent(valuesVariable(child))
                .addContent(" = ")
                .addContent(child.mapField)
                .addContent(".computeIfAbsent(")
                .addContent(parentVariable)
                .addContent(", ignored -> new ")
                .addContent(LINKED_HASH_MAP)
                .addContentLine("<>());")
                .addContent(child.type)
                .addContent(" ")
                .addContent(child.variable)
                .addContent(" = ")
                .addContent(valuesVariable(child))
                .addContent(".get(")
                .addContent(identityVariable(child))
                .addContentLine(");")
                .addContent("if (")
                .addContent(child.variable)
                .addContentLine(" == null) {");
        addCreate(method, child);
        method.addContent(valuesVariable(child))
                .addContent(".put(")
                .addContent(identityVariable(child))
                .addContent(", ")
                .addContent(child.variable)
                .addContentLine(");")
                .addContent(parentVariable)
                .addContent(".")
                .addContent(child.collection.getter.elementName())
                .addContent("().add(")
                .addContent(child.variable)
                .addContentLine(");")
                .addContentLine("} else {");
        addValidateExisting(method, child);
        method.addContentLine("}");
        for (Scope nested : child.children.values()) {
            addChild(method, nested, child.variable);
        }
        if (child.children.isEmpty()) {
            method.addContentLine("}");
        } else {
            method.addContentLine("} else {");
            addRejectDescendants(method, child);
            method.addContentLine("}");
        }
    }

    private static void addCreate(Method.Builder method, Scope scope) {
        method.addContent(scope.variable)
                .addContent(" = new ")
                .addContent(scope.type)
                .addContentLine("();");
        for (Property property : scope.properties.values()) {
            method.addContent(scope.variable)
                    .addContent(".")
                    .addContent(property.accessors.setter.elementName())
                    .addContent("(");
            if (property == scope.identity) {
                method.addContent(identityVariable(scope));
            } else {
                addRowRead(method, property.alias, property.type(), property.type().primitive());
            }
            method.addContentLine(");");
        }
        for (Scope child : scope.children.values()) {
            method.addContent(scope.variable)
                    .addContent(".")
                    .addContent(child.collection.setter.elementName())
                    .addContent("(new ")
                    .addContent(ARRAY_LIST)
                    .addContentLine("<>());");
        }
    }

    private static void addValidateExisting(Method.Builder method, Scope scope) {
        String location = scope.prefix.isEmpty() ? "root" : scope.prefix;
        for (Map.Entry<String, Property> entry : scope.properties.entrySet()) {
            Property property = entry.getValue();
            if (property == scope.identity) {
                continue;
            }
            method.addContent("if (!")
                    .addContent(OBJECTS)
                    .addContent(".equals(")
                    .addContent(scope.variable)
                    .addContent(".")
                    .addContent(property.accessors.getter.elementName())
                    .addContent("(), ");
            addRowRead(method, property.alias, property.type(), property.type().primitive());
            method.addContentLine(")) {")
                    .addContent("throw new ")
                    .addContent(DATA_EXCEPTION)
                    .addContent("(")
                    .addContentLiteral("Conflicting projected value for graph scope '" + location
                                               + "' property '" + entry.getKey() + "'")
                    .addContentLine(");")
                    .addContentLine("}");
        }
    }

    private static void addRejectDescendants(Method.Builder method, Scope ancestor) {
        String ancestorLocation = ancestor.prefix.isEmpty() ? "root" : ancestor.prefix;
        for (Scope descendant : descendants(ancestor)) {
            method.addContent("if (");
            addRowRead(method, descendant.identity.alias, descendant.identity.type().boxed(), false);
            method.addContentLine(" != null) {")
                    .addContent("throw new ")
                    .addContent(DATA_EXCEPTION)
                    .addContent("(")
                    .addContentLiteral("Graph scope '" + descendant.prefix
                                               + "' has an identity while ancestor scope '"
                                               + ancestorLocation + "' is absent")
                    .addContentLine(");")
                    .addContentLine("}");
        }
    }

    private static void addIdentityRead(Method.Builder method, Scope scope, boolean required) {
        TypeName type = scope.identity.type().boxed();
        method.addContent(type)
                .addContent(" ")
                .addContent(identityVariable(scope))
                .addContent(" = ");
        addRowRead(method, scope.identity.alias, type, required);
        method.addContentLine(";");
    }

    private static void addRowRead(Method.Builder method,
                                   String alias,
                                   TypeName type,
                                   boolean required) {
        method.addContent("row.")
                .addContent(required ? "required(" : "get(")
                .addContentLiteral(alias)
                .addContent(", ")
                .addContent(type.boxed())
                .addContent(".class)");
    }

    private static void generateFinish(JdbcMethodPlan plan, Scope root, Method.Builder method) {
        method.name("finish")
                .accessModifier(AccessModifier.PUBLIC)
                .returnType(plan.method().typeName())
                .addAnnotation(Annotation.create(Override.class));
        switch (plan.returnShape()) {
        case LIST -> method.addContent("return new ")
                .addContent(ARRAY_LIST)
                .addContent("<>(")
                .addContent(root.mapField)
                .addContentLine(".values());");
        case OPTIONAL -> method.addContent("if (")
                .addContent(root.mapField)
                .addContentLine(".size() > 1) {")
                .addContent("throw new ")
                .addContent(NON_UNIQUE)
                .addContentLine("(\"JDBC graph query returned more than one logical root\");")
                .addContentLine("}")
                .addContent("return ")
                .addContent(root.mapField)
                .addContent(".isEmpty() ? ")
                .addContent(OPTIONAL)
                .addContent(".empty() : ")
                .addContent(OPTIONAL)
                .addContent(".of(")
                .addContent(root.mapField)
                .addContentLine(".sequencedValues().getFirst());");
        case ITEM -> method.addContent("if (")
                .addContent(root.mapField)
                .addContentLine(".isEmpty()) {")
                .addContent("throw new ")
                .addContent(NO_RESULT)
                .addContentLine("(\"JDBC graph query returned no logical roots\");")
                .addContentLine("}")
                .addContent("if (")
                .addContent(root.mapField)
                .addContentLine(".size() > 1) {")
                .addContent("throw new ")
                .addContent(NON_UNIQUE)
                .addContentLine("(\"JDBC graph query returned more than one logical root\");")
                .addContentLine("}")
                .addContent("return ")
                .addContent(root.mapField)
                .addContentLine(".sequencedValues().getFirst();");
        default -> throw JdbcMethodPlan.failure(plan.method(),
                                                "Graph reduction supports item, optional, and list results only");
        }
    }

    private static Map<String, JdbcMethodPlan.BeanMapping> beanDeclarations(JdbcMethodPlan plan) {
        Map<String, JdbcMethodPlan.BeanMapping> result = new HashMap<>();
        for (JdbcMethodPlan.BeanMapping declaration : plan.beanMappings()) {
            result.put(declaration.prefix(), declaration);
        }
        return result;
    }

    private static void validateDeclarations(JdbcMethodPlan plan,
                                             Map<String, JdbcMethodPlan.BeanMapping> declarations,
                                             List<Scope> scopes) {
        if (declarations.size() != scopes.size()) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Graph @Data.BeanMapper declarations must cover the root and every collection scope");
        }
        for (Scope scope : scopes) {
            JdbcMethodPlan.BeanMapping declaration = declarations.get(scope.prefix);
            if (declaration == null || !declaration.type().genericTypeName().equals(scope.type.genericTypeName())) {
                String location = scope.prefix.isEmpty() ? "root" : scope.prefix;
                throw JdbcMethodPlan.failure(plan.method(),
                                             "Missing or mismatched @Data.BeanMapper for graph scope '" + location + "'");
            }
        }
    }

    private static Accessors collectionAccessors(JdbcMethodPlan plan,
                                                 CodegenContext context,
                                                 TypeName owner,
                                                 String property) {
        TypeInfo info = typeInfo(plan, context, owner);
        String suffix = beanSuffix(property);
        TypedElementInfo getter = findMethod(info, "get" + suffix, 0, plan.method());
        TypedElementInfo setter = findMethod(info, "set" + suffix, 1, plan.method());
        if (getter == null || setter == null
                || !getter.typeName().genericTypeName().equals(LIST)
                || !setter.parameterArguments().getFirst().typeName().equals(getter.typeName())
                || getter.typeName().typeArguments().size() != 1
                || getter.typeName().typeArguments().getFirst().wildcard()) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Graph collection property must be an accessible readable/writable exact List<E>: "
                                                 + owner.resolvedName() + "." + property);
        }
        return new Accessors(getter, setter);
    }

    private static Accessors scalarAccessors(JdbcMethodPlan plan,
                                             CodegenContext context,
                                             TypeName owner,
                                             String property) {
        TypeInfo info = typeInfo(plan, context, owner);
        String suffix = beanSuffix(property);
        TypedElementInfo setter = findMethod(info, "set" + suffix, 1, plan.method());
        TypedElementInfo getter = findMethod(info, "get" + suffix, 0, plan.method());
        if (getter == null) {
            getter = findMethod(info, "is" + suffix, 0, plan.method());
            if (getter != null
                    && !getter.typeName().boxed().equals(TypeName.create(Boolean.class))) {
                getter = null;
            }
        }
        if (getter == null
                || setter == null
                || !setter.parameterArguments().getFirst().typeName().equals(getter.typeName())
                || !JdbcMethodPlan.isScalar(getter.typeName())) {
            throw JdbcMethodPlan.failure(plan.method(),
                                         "Graph property requires matching accessible scalar getter and setter: "
                                                 + owner.resolvedName() + "." + property);
        }
        return new Accessors(getter, setter);
    }

    private static TypedElementInfo findMethod(TypeInfo info,
                                               String name,
                                               int parameterCount,
                                               TypedElementInfo repositoryMethod) {
        for (TypedElementInfo element : info.elementInfo()) {
            if (element.kind() == ElementKind.METHOD
                    && element.elementName().equals(name)
                    && element.parameterArguments().size() == parameterCount
                    && !element.elementModifiers().contains(Modifier.STATIC)
                    && accessible(element, info.typeName(), repositoryMethod)) {
                return element;
            }
        }
        TypedElementInfo inherited = info.superTypeInfo()
                .map(superInfo -> findMethod(superInfo, name, parameterCount, repositoryMethod))
                .orElse(null);
        if (inherited != null) {
            return inherited;
        }
        for (TypeInfo interfaceInfo : info.interfaceTypeInfo()) {
            inherited = findMethod(interfaceInfo, name, parameterCount, repositoryMethod);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static boolean accessible(TypedElementInfo element,
                                      TypeName owner,
                                      TypedElementInfo repositoryMethod) {
        if (element.accessModifier() == AccessModifier.PUBLIC) {
            return true;
        }
        return (element.accessModifier() == AccessModifier.PACKAGE_PRIVATE
                || element.accessModifier() == AccessModifier.PROTECTED)
                && repositoryMethod.enclosingType()
                .map(TypeName::packageName)
                .filter(owner.packageName()::equals)
                .isPresent();
    }

    private static TypeInfo typeInfo(JdbcMethodPlan plan, CodegenContext context, TypeName type) {
        return context.typeInfo(type.genericTypeName())
                .orElseThrow(() -> JdbcMethodPlan.failure(plan.method(),
                                                          "Graph type information is unavailable: "
                                                                  + type.resolvedName()));
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

    private static boolean javaIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String beanSuffix(String property) {
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    private static String variable(String path) {
        StringBuilder result = new StringBuilder(path.length());
        boolean capitalize = false;
        for (char current : path.toCharArray()) {
            if (current == '.') {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(current));
                capitalize = false;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String identityVariable(Scope scope) {
        return scope.variable + "Id";
    }

    private static String valuesVariable(Scope scope) {
        return scope.variable + "Values";
    }

    private record Accessors(TypedElementInfo getter, TypedElementInfo setter) {
    }

    private record Property(String alias, Accessors accessors) {
        TypeName type() {
            return accessors.setter.parameterArguments().getFirst().typeName();
        }
    }

    private static final class Scope {
        private final String prefix;
        private final TypeName type;
        private final Scope parent;
        private final Accessors collection;
        private final Map<String, Scope> children = new LinkedHashMap<>();
        private final Map<String, Property> properties = new LinkedHashMap<>();
        private Property identity;
        private String variable;
        private String mapField;

        private Scope(String prefix, TypeName type, Scope parent, Accessors collection) {
            this.prefix = prefix;
            this.type = type;
            this.parent = parent;
            this.collection = collection;
        }
    }
}
