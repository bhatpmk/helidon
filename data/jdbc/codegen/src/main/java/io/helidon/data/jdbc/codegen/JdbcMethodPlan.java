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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.common.types.Annotation;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;

/**
 * Fully validated compile-time plan for one annotated repository method.
 */
final class JdbcMethodPlan {
    private static final Set<String> SCALAR_TYPES = Set.of(Boolean.class.getName(),
                                                            Byte.class.getName(),
                                                            Short.class.getName(),
                                                            Integer.class.getName(),
                                                            Long.class.getName(),
                                                            Float.class.getName(),
                                                            Double.class.getName(),
                                                            "java.math.BigDecimal",
                                                            "java.math.BigInteger",
                                                            String.class.getName(),
                                                            "byte[]",
                                                            "java.util.UUID",
                                                            "java.time.LocalDate",
                                                            "java.time.LocalTime",
                                                            "java.time.LocalDateTime",
                                                            "java.time.OffsetTime",
                                                            "java.time.OffsetDateTime",
                                                            "java.time.Instant",
                                                            "java.sql.Date",
                                                            "java.sql.Time",
                                                            "java.sql.Timestamp");

    private final TypedElementInfo method;
    private final Operation operation;
    private final ReturnShape returnShape;
    private final MappingKind mappingKind;
    private final TypeName mappedType;
    private final TypedElementInfo optionsParameter;
    private final TypedElementInfo traversalParameter;
    private final JdbcSqlParameterPlan parameterPlan;
    private final List<String> generatedColumns;
    private final List<String> aliases;
    private final List<BeanMapping> beanMappings;
    private final TypeName explicitMapper;
    private final TypeName explicitReducer;
    private String sqlFieldName;
    private String mapperFieldName;

    private JdbcMethodPlan(TypedElementInfo method,
                           Operation operation,
                           ReturnShape returnShape,
                           MappingKind mappingKind,
                           TypeName mappedType,
                           TypedElementInfo optionsParameter,
                           TypedElementInfo traversalParameter,
                           JdbcSqlParameterPlan parameterPlan,
                           List<String> generatedColumns,
                           List<String> aliases,
                           List<BeanMapping> beanMappings,
                           TypeName explicitMapper,
                           TypeName explicitReducer) {
        this.method = method;
        this.operation = operation;
        this.returnShape = returnShape;
        this.mappingKind = mappingKind;
        this.mappedType = mappedType;
        this.optionsParameter = optionsParameter;
        this.traversalParameter = traversalParameter;
        this.parameterPlan = parameterPlan;
        this.generatedColumns = generatedColumns;
        this.aliases = aliases;
        this.beanMappings = beanMappings;
        this.explicitMapper = explicitMapper;
        this.explicitReducer = explicitReducer;
    }

    // This method validates the annotations, parameters, return shape, mapping, generated keys, etc.
    // during the code generation.
    //
    // A note about return type
    // `one()`, `optional()`, and `list()` come from the return type.
    // `Consumer<T>` selects `forEach(...)` and requires `void`.
    // `Predicate<T>` selects `forEachWhile(...)` and requires primitive
    //     `boolean`.
    // Updates require primitive `long` or `void`.
    static JdbcMethodPlan create(TypedElementInfo method, CodegenContext context) {
        boolean query = method.hasAnnotation(JdbcCodegenTypes.DATA_QUERY);
        boolean update = method.hasAnnotation(JdbcCodegenTypes.DATA_UPDATE);
        if (query == update) {
            throw failure(method, query
                    ? "A repository method cannot combine @Data.Query and @Data.Update"
                    : "An abstract JDBC repository method requires @Data.Query or @Data.Update");
        }

        Operation operation = query ? Operation.QUERY : Operation.UPDATE;
        Annotation statementAnnotation = method.annotation(query
                                                                    ? JdbcCodegenTypes.DATA_QUERY
                                                                    : JdbcCodegenTypes.DATA_UPDATE);
        String sql = statementAnnotation.stringValue()
                .orElseThrow(() -> failure(method, "SQL annotation value is missing"));

        boolean generatedKeys = method.hasAnnotation(JdbcCodegenTypes.DATA_GENERATED_KEYS);
        if (generatedKeys && operation != Operation.UPDATE) {
            throw failure(method, "@Data.GeneratedKeys is legal only with @Data.Update");
        }
        if (generatedKeys) {
            operation = Operation.GENERATED_KEYS;
        }

        List<TypedElementInfo> parameters = method.parameterArguments();
        TypedElementInfo options = optionsParameter(parameters, method);
        Traversal traversal = traversal(parameters, method);
        int firstBindable = options == null ? 0 : 1;
        int bindableEnd = traversal.parameter() == null ? parameters.size() : parameters.size() - 1;
        List<TypedElementInfo> bindable = List.copyOf(parameters.subList(firstBindable, bindableEnd));

        // We need compile time correspondence between named markers and method parameters
        JdbcSqlParameterPlan parameterPlan = JdbcSqlParameterPlan.create(sql, bindable, method);

        Return returnPlan = returnPlan(method, traversal);
        validateOperationReturn(method, operation, returnPlan, traversal);

        List<BeanMapping> beanMappings = beanMappings(method);
        TypeName explicitMapper = method.findAnnotation(JdbcCodegenTypes.DATA_ROW_MAPPER)
                .flatMap(Annotation::typeValue)
                .orElse(null);
        TypeName explicitReducer = method.findAnnotation(JdbcCodegenTypes.DATA_ROW_REDUCER)
                .flatMap(Annotation::typeValue)
                .orElse(null);
        ExplicitMapping explicitMapping = new ExplicitMapping(explicitMapper, explicitReducer);
        if (explicitReducer != null && (explicitMapper != null || !beanMappings.isEmpty())) {
            throw failure(method, "@Data.RowReducer cannot be combined with @Data.RowMapper or @Data.BeanMapping");
        }
        if (explicitMapper != null && !beanMappings.isEmpty()) {
            throw failure(method, "@Data.RowMapper and @Data.BeanMapping cannot be combined");
        }
        if (operation == Operation.UPDATE
                && (!beanMappings.isEmpty() || explicitMapper != null || explicitReducer != null)) {
            throw failure(method, "Result mapping annotations are not legal on an update-count method");
        }
        if (explicitReducer != null && operation != Operation.QUERY) {
            throw failure(method, "@Data.RowReducer is legal only on @Data.Query methods");
        }
        if (explicitReducer != null && traversal.parameter() != null) {
            throw failure(method, "@Data.RowReducer cannot use a streaming traversal callback");
        }

        List<String> generatedColumns = generatedKeys
                ? method.annotation(JdbcCodegenTypes.DATA_GENERATED_KEYS).stringValues().orElse(List.of())
                : List.of();
        if (generatedColumns.stream().anyMatch(String::isBlank)) {
            throw failure(method, "@Data.GeneratedKeys column names must not be blank");
        }
        List<String> aliases = generatedKeys
                ? generatedColumns
                : JdbcProjectionAliasLexer.aliases(sql);
        Set<String> normalizedAliases = new HashSet<>();
        if (aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(alias -> !normalizedAliases.add(alias))) {
            throw failure(method, "Duplicate result column alias or generated-key column");
        }
        MappingKind mappingKind = operation == Operation.UPDATE
                ? MappingKind.NONE
                : mappingKind(method,
                              context,
                              returnPlan.mappedType(),
                              aliases,
                              beanMappings,
                              explicitMapping,
                              traversal);
        if (generatedKeys && mappingKind == MappingKind.GRAPH) {
            throw failure(method, "Generated-key rows do not support generated graph reduction");
        }

        return new JdbcMethodPlan(method,
                                  operation,
                                  returnPlan.shape(),
                                  mappingKind,
                                  returnPlan.mappedType(),
                                  options,
                                  traversal.parameter(),
                                  parameterPlan,
                                  List.copyOf(generatedColumns),
                                  aliases,
                                  beanMappings,
                                  explicitMapper,
                                  explicitReducer);
    }

    private static TypedElementInfo optionsParameter(List<TypedElementInfo> parameters, TypedElementInfo method) {
        TypedElementInfo result = null;
        for (int i = 0; i < parameters.size(); i++) {
            TypedElementInfo parameter = parameters.get(i);
            if (parameter.typeName().genericTypeName().equals(JdbcCodegenTypes.JDBC_EXECUTION_OPTIONS)) {
                if (i != 0 || result != null) {
                    throw failure(method, "JdbcExecutionOptions is permitted once and only as the leading parameter");
                }
                if (parameter.hasAnnotation(JdbcCodegenTypes.DATA_JDBC_TYPE)) {
                    throw failure(method, "JdbcExecutionOptions must not carry @Data.JdbcType");
                }
                result = parameter;
            }
        }
        return result;
    }

    private static Traversal traversal(List<TypedElementInfo> parameters, TypedElementInfo method) {
        if (parameters.isEmpty()) {
            return new Traversal(null, ReturnShape.ITEM, null);
        }
        for (int i = 0; i < parameters.size() - 1; i++) {
            TypeName raw = parameters.get(i).typeName().genericTypeName();
            if (raw.equals(JdbcCodegenTypes.CONSUMER) || raw.equals(JdbcCodegenTypes.PREDICATE)) {
                throw failure(method, "A traversal callback is permitted only as the trailing parameter");
            }
        }
        TypedElementInfo last = parameters.getLast();
        TypeName raw = last.typeName().genericTypeName();
        if (!raw.equals(JdbcCodegenTypes.CONSUMER) && !raw.equals(JdbcCodegenTypes.PREDICATE)) {
            return new Traversal(null, ReturnShape.ITEM, null);
        }
        if (last.hasAnnotation(JdbcCodegenTypes.DATA_JDBC_TYPE)) {
            throw failure(method, "A traversal callback must not carry @Data.JdbcType");
        }
        if (last.typeName().typeArguments().size() != 1) {
            throw failure(method, "Traversal callback requires one concrete generic argument");
        }
        TypeName argument = last.typeName().typeArguments().getFirst();
        if (argument.wildcard()) {
            throw failure(method, "Traversal callback wildcard arguments are not supported");
        }
        if (raw.equals(JdbcCodegenTypes.PREDICATE)) {
            return new Traversal(last, ReturnShape.FOR_EACH_WHILE, argument);
        }
        if (argument.genericTypeName().equals(JdbcCodegenTypes.ITERABLE)) {
            if (argument.typeArguments().size() != 1 || argument.typeArguments().getFirst().wildcard()) {
                throw failure(method, "Consumer<Iterable<T>> requires one concrete mapped type");
            }
            return new Traversal(last, ReturnShape.WITH_ROWS, argument.typeArguments().getFirst());
        }
        return new Traversal(last, ReturnShape.FOR_EACH, argument);
    }

    private static Return returnPlan(TypedElementInfo method, Traversal traversal) {
        TypeName returnType = method.typeName();
        if (traversal.parameter() != null) {
            return new Return(traversal.shape(), traversal.mappedType());
        }
        if (returnType.isOptional()) {
            return new Return(ReturnShape.OPTIONAL, singleTypeArgument(method, returnType));
        }
        if (returnType.isList()) {
            return new Return(ReturnShape.LIST, singleTypeArgument(method, returnType));
        }
        return new Return(ReturnShape.ITEM, returnType);
    }

    private static TypeName singleTypeArgument(TypedElementInfo method, TypeName type) {
        if (type.typeArguments().size() != 1 || type.typeArguments().getFirst().wildcard()) {
            throw failure(method, "Repository result requires one concrete generic argument: " + type.resolvedName());
        }
        return type.typeArguments().getFirst();
    }

    private static void validateOperationReturn(TypedElementInfo method,
                                                Operation operation,
                                                Return returnPlan,
                                                Traversal traversal) {
        if (operation == Operation.UPDATE) {
            if (traversal.parameter() != null) {
                throw failure(method, "Update-count methods do not support row traversal callbacks");
            }
            if (!method.typeName().equals(TypeNames.PRIMITIVE_LONG)
                    && !method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
                throw failure(method, "@Data.Update methods must return primitive long or void");
            }
            return;
        }
        if (traversal.shape() == ReturnShape.FOR_EACH_WHILE) {
            if (!method.typeName().equals(TypeNames.PRIMITIVE_BOOLEAN)) {
                throw failure(method, "Predicate traversal methods must return primitive boolean");
            }
        } else if (traversal.parameter() != null && !method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw failure(method, "Consumer traversal methods must return void");
        } else if (traversal.parameter() == null && method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw failure(method, "Mapped query and generated-key methods require a result or traversal callback");
        }

        String rawResult = returnPlan.mappedType().genericTypeName().fqName();
        // We need to support these return types in the future release
        if ("java.util.Set".equals(rawResult)
                || "java.util.Map".equals(rawResult)
                || "java.util.stream.Stream".equals(rawResult)
                || returnPlan.mappedType().array()) {
            throw failure(method, "Unsupported JDBC repository return type: " + returnPlan.mappedType().resolvedName());
        }
    }

    private static MappingKind mappingKind(TypedElementInfo method,
                                           CodegenContext context,
                                           TypeName mappedType,
                                           List<String> aliases,
                                           List<BeanMapping> beanMappings,
                                           ExplicitMapping explicitMapping,
                                           Traversal traversal) {
        if (explicitMapping.reducer() != null) {
            return MappingKind.REDUCER;
        }
        boolean dottedAlias = aliases.stream().anyMatch(alias -> alias.indexOf('.') > 0);
        boolean graphDeclared = beanMappings.size() > 1
                || beanMappings.stream().anyMatch(mapping -> !mapping.propertyPath().isEmpty()
                        || !mapping.identityProperty().isEmpty());
        if (graphDeclared && traversal.parameter() != null) {
            throw failure(method, "Identity-defined graph reduction cannot use a streaming traversal callback");
        }
        if (graphDeclared && explicitMapping.mapper() != null) {
            throw failure(method, "@Data.RowMapper cannot be combined with generated graph reduction");
        }
        if (graphDeclared) {
            return MappingKind.GRAPH;
        }
        if (explicitMapping.mapper() != null) {
            return MappingKind.EXPLICIT;
        }
        if (dottedAlias) {
            throw failure(method, "Dotted SQL projection aliases require a complete identity-bearing "
                    + "@Data.BeanMapping set or an explicit mapper or reducer");
        }
        if (!beanMappings.isEmpty()) {
            BeanMapping mapping = beanMappings.getFirst();
            if (beanMappings.size() != 1
                    || !mapping.propertyPath().isEmpty()
                    || !mapping.identityProperty().isEmpty()) {
                throw failure(method, "A flat bean result requires exactly one root @Data.BeanMapping with empty "
                        + "propertyPath and identityProperty values");
            }
            return MappingKind.BEAN;
        }
        if (isScalar(mappedType)) {
            return MappingKind.SCALAR;
        }
        TypeInfo resultInfo = context.typeInfo(mappedType.genericTypeName())
                .orElseThrow(() -> failure(method, "Mapped result type information is unavailable: "
                        + mappedType.resolvedName()));
        if (resultInfo.kind() == ElementKind.RECORD) {
            return MappingKind.RECORD;
        }
        throw failure(method, "Non-record JDBC result requires @Data.BeanMapping, @Data.RowMapper, or @Data.RowReducer: "
                + mappedType.resolvedName());
    }

    private static List<BeanMapping> beanMappings(TypedElementInfo method) {
        List<Annotation> annotations = new ArrayList<>();
        for (Annotation annotation : method.annotations()) {
            if (annotation.typeName().equals(JdbcCodegenTypes.DATA_BEAN_MAPPING)) {
                annotations.add(annotation);
            } else if (annotation.typeName().equals(JdbcCodegenTypes.DATA_BEAN_MAPPINGS)) {
                annotations.addAll(annotation.annotationValues().orElse(List.of()));
            }
        }
        List<BeanMapping> result = new ArrayList<>(annotations.size());
        Set<String> propertyPaths = new HashSet<>();
        for (Annotation annotation : annotations) {
            TypeName beanType = annotation.typeValue()
                    .orElseThrow(() -> failure(method, "@Data.BeanMapping class value is missing"));
            String propertyPath = annotation.stringValue("propertyPath").orElse("");
            String identityProperty = annotation.stringValue("identityProperty").orElse("");
            if (!propertyPaths.add(propertyPath)) {
                throw failure(method, "Duplicate @Data.BeanMapping propertyPath: '" + propertyPath + "'");
            }
            result.add(new BeanMapping(beanType, propertyPath, identityProperty));
        }
        return List.copyOf(result);
    }

    static boolean isScalar(TypeName type) {
        TypeName boxed = type.boxed().genericTypeName();
        if (boxed.array() && boxed.componentType().map(TypeName::fqName).filter("byte"::equals).isPresent()) {
            return true;
        }
        return SCALAR_TYPES.contains(boxed.fqName());
    }

    static CodegenException failure(TypedElementInfo method, String message) {
        return new CodegenException(message, method.originatingElementValue());
    }

    TypedElementInfo method() {
        return method;
    }

    Operation operation() {
        return operation;
    }

    ReturnShape returnShape() {
        return returnShape;
    }

    MappingKind mappingKind() {
        return mappingKind;
    }

    TypeName mappedType() {
        return mappedType;
    }

    TypedElementInfo optionsParameter() {
        return optionsParameter;
    }

    TypedElementInfo traversalParameter() {
        return traversalParameter;
    }

    JdbcSqlParameterPlan parameterPlan() {
        return parameterPlan;
    }

    List<String> generatedColumns() {
        return generatedColumns;
    }

    List<String> aliases() {
        return aliases;
    }

    List<BeanMapping> beanMappings() {
        return beanMappings;
    }

    TypeName explicitMapper() {
        return explicitMapper;
    }

    TypeName explicitReducer() {
        return explicitReducer;
    }

    String sqlFieldName() {
        return sqlFieldName;
    }

    void sqlFieldName(String sqlFieldName) {
        this.sqlFieldName = sqlFieldName;
    }

    String mapperFieldName() {
        return mapperFieldName;
    }

    void mapperFieldName(String mapperFieldName) {
        this.mapperFieldName = mapperFieldName;
    }

    enum Operation {
        QUERY,
        UPDATE,
        GENERATED_KEYS
    }

    enum ReturnShape {
        ITEM,
        OPTIONAL,
        LIST,
        WITH_ROWS,
        FOR_EACH,
        FOR_EACH_WHILE
    }

    enum MappingKind {
        NONE,
        SCALAR,
        RECORD,
        BEAN,
        EXPLICIT,
        REDUCER,
        GRAPH
    }

    record BeanMapping(TypeName type, String propertyPath, String identityProperty) {
    }

    private record ExplicitMapping(TypeName mapper, TypeName reducer) {
    }

    private record Traversal(TypedElementInfo parameter, ReturnShape shape, TypeName mappedType) {
    }

    private record Return(ReturnShape shape, TypeName mappedType) {
    }
}
