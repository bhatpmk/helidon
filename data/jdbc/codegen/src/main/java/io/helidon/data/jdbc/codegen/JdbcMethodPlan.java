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
    private final TypedElementInfo requestParameter;
    private final TypedElementInfo optionsParameter;
    private final RequestKind requestKind;
    private final JdbcSqlParameterPlan parameterPlan;
    private final JdbcCallParameterPlan callParameterPlan;
    private final JdbcCallResultPlan callResultPlan;
    private final List<String> generatedColumns;
    private final List<String> aliases;
    private final List<String> identityPaths;
    private final TypeName explicitMapper;
    private final TypeName explicitReducer;
    private String sqlFieldName;
    private String mapperFieldName;
    private String callFieldName;

    private JdbcMethodPlan(TypedElementInfo method,
                           Operation operation,
                           ReturnShape returnShape,
                           MappingKind mappingKind,
                           TypeName mappedType,
                           TypedElementInfo requestParameter,
                           TypedElementInfo optionsParameter,
                           RequestKind requestKind,
                           JdbcSqlParameterPlan parameterPlan,
                           JdbcCallParameterPlan callParameterPlan,
                           JdbcCallResultPlan callResultPlan,
                           List<String> generatedColumns,
                           List<String> aliases,
                           List<String> identityPaths,
                           TypeName explicitMapper,
                           TypeName explicitReducer) {
        this.method = method;
        this.operation = operation;
        this.returnShape = returnShape;
        this.mappingKind = mappingKind;
        this.mappedType = mappedType;
        this.requestParameter = requestParameter;
        this.optionsParameter = optionsParameter;
        this.requestKind = requestKind;
        this.parameterPlan = parameterPlan;
        this.callParameterPlan = callParameterPlan;
        this.callResultPlan = callResultPlan;
        this.generatedColumns = generatedColumns;
        this.aliases = aliases;
        this.identityPaths = identityPaths;
        this.explicitMapper = explicitMapper;
        this.explicitReducer = explicitReducer;
    }

    // Validates the complete repository contract before any source is emitted. Return cardinality selects one(),
    // optional(), or list(); a leading JdbcResultRequest selects traversal; and update methods return void, int, or long.
    static JdbcMethodPlan create(TypedElementInfo method, CodegenContext context) {
        Annotation statementAnnotation = method.findAnnotation(JdbcCodegenTypes.JDBC_STATEMENT)
                .orElseThrow(() -> failure(method, "An abstract JDBC repository method requires @Jdbc.Statement"));
        String sql = statementAnnotation.stringValue()
                .orElseThrow(() -> failure(method, "SQL annotation value is missing"));
        if (sql.isBlank()) {
            throw failure(method, "@Jdbc.Statement SQL must not be blank");
        }

        List<TypedElementInfo> parameters = method.parameterArguments();
        InvocationControl control = invocationControl(parameters, method);
        Request request = control.request();
        Return returnPlan = returnPlan(method, request);

        int firstBindable = control.present() ? 1 : 0;
        List<TypedElementInfo> bindable = List.copyOf(parameters.subList(firstBindable, parameters.size()));

        List<String> identityPaths = identityPaths(method);
        boolean identityReduction = !identityPaths.isEmpty();
        Annotation rowMapperAnnotation = method.findAnnotation(JdbcCodegenTypes.JDBC_ROW_MAPPER).orElse(null);
        boolean rowMapperRequested = rowMapperAnnotation != null;
        TypeName explicitMapper = rowMapperAnnotation == null
                ? null
                : rowMapperAnnotation.typeValue()
                        .filter(type -> !TypeNames.BOXED_VOID.equals(type))
                        .orElse(null);
        TypeName explicitReducer = method.findAnnotation(JdbcCodegenTypes.JDBC_ROW_REDUCER)
                .flatMap(Annotation::typeValue)
                .orElse(null);
        boolean generatedKeys = method.hasAnnotation(JdbcCodegenTypes.JDBC_GENERATED_KEYS);
        boolean callableAnnotations = JdbcCallParameterPlan.callableAnnotations(method);
        OperationEvidence evidence = new OperationEvidence(generatedKeys,
                                                           identityReduction,
                                                           rowMapperRequested,
                                                           explicitReducer != null,
                                                           callableAnnotations);
        Operation operation = operation(method, returnPlan, request, evidence);
        if (generatedKeys) {
            if (operation != Operation.UPDATE) {
                throw failure(method, "@Jdbc.GeneratedKeys requires UPDATE execution");
            }
            operation = Operation.GENERATED_KEYS;
        }
        validateOperationReturn(method, operation, returnPlan, request);

        if (callableAnnotations && operation != Operation.CALL) {
            throw failure(method, "JDBC callable parameter annotations require CALL execution");
        }

        JdbcCallParameterPlan callParameterPlan = operation == Operation.CALL
                ? JdbcCallParameterPlan.create(sql, bindable, method)
                : null;
        JdbcSqlParameterPlan parameterPlan = operation == Operation.CALL
                ? null
                : JdbcSqlParameterPlan.create(sql, bindable, method);
        JdbcCallResultPlan callResultPlan = operation == Operation.CALL
                ? JdbcCallResultPlan.create(method, context, callParameterPlan, request.call())
                : null;

        ExplicitMapping explicitMapping = new ExplicitMapping(rowMapperRequested, explicitMapper, explicitReducer);
        if (explicitReducer != null && (rowMapperRequested || identityReduction)) {
            throw failure(method, "@Jdbc.RowReducer cannot be combined with @Jdbc.RowMapper "
                    + "or @Jdbc.IdentityReducer");
        }
        if (rowMapperRequested && identityReduction) {
            throw failure(method, "@Jdbc.RowMapper and @Jdbc.IdentityReducer cannot be combined");
        }
        if (operation == Operation.UPDATE
                && (identityReduction || rowMapperRequested || explicitReducer != null)) {
            throw failure(method, "Result mapping annotations are not legal on an update-count method");
        }
        if (operation == Operation.CALL
                && (identityReduction || rowMapperRequested || explicitReducer != null)) {
            throw failure(method, "CALL execution does not support repository-level row mapping annotations; "
                    + "map each scoped result channel inside the call callback");
        }
        if (identityReduction && operation != Operation.QUERY) {
            throw failure(method, "@Jdbc.IdentityReducer is legal only for QUERY execution");
        }
        if (identityReduction && request.traversal()) {
            throw failure(method, "@Jdbc.IdentityReducer cannot use a JDBC query traversal request");
        }
        if (explicitReducer != null && operation != Operation.QUERY) {
            throw failure(method, "@Jdbc.RowReducer is legal only for QUERY execution");
        }
        if (explicitReducer != null && request.traversal()) {
            throw failure(method, "@Jdbc.RowReducer cannot use a JDBC query traversal request");
        }

        List<String> generatedColumns = generatedKeys
                ? method.annotation(JdbcCodegenTypes.JDBC_GENERATED_KEYS).stringValues().orElse(List.of())
                : List.of();
        if (generatedColumns.stream().anyMatch(String::isBlank)) {
            throw failure(method, "@Jdbc.GeneratedKeys column names must not be blank");
        }
        List<String> aliases = operation == Operation.CALL
                ? List.of()
                : generatedKeys
                ? generatedColumns
                : JdbcProjectionAliasLexer.aliases(sql);
        Set<String> normalizedAliases = new HashSet<>();
        if (aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(alias -> !normalizedAliases.add(alias))) {
            throw failure(method, "Duplicate result column alias or generated-key column");
        }
        MappingKind mappingKind = operation == Operation.UPDATE || operation == Operation.CALL
                ? MappingKind.NONE
                : mappingKind(method,
                              context,
                              operation,
                              returnPlan.mappedType(),
                              aliases,
                              identityReduction,
                              explicitMapping);
        return new JdbcMethodPlan(method,
                                  operation,
                                  returnPlan.shape(),
                                  mappingKind,
                                  returnPlan.mappedType(),
                                  request.parameter(),
                                  control.optionsParameter(),
                                  request.kind(),
                                  parameterPlan,
                                  callParameterPlan,
                                  callResultPlan,
                                  List.copyOf(generatedColumns),
                                  aliases,
                                  identityPaths,
                                  explicitMapper,
                                  explicitReducer);
    }

    private static Operation operation(TypedElementInfo method,
                                       Return returnPlan,
                                       Request request,
                                       OperationEvidence evidence) {
        Annotation execution = method.findAnnotation(JdbcCodegenTypes.JDBC_EXECUTION).orElse(null);
        if (execution != null) {
            String value = execution.stringValue()
                    .orElseThrow(() -> failure(method, "@Jdbc.Execution value is missing"));
            return switch (value) {
            case "QUERY" -> Operation.QUERY;
            case "UPDATE" -> Operation.UPDATE;
            case "CALL" -> Operation.CALL;
            default -> throw failure(method, "Unsupported @Jdbc.Execution value: " + value);
            };
        }

        if (evidence.generatedKeys()) {
            return Operation.UPDATE;
        }
        if (request.call() || evidence.callableAnnotations()) {
            return Operation.CALL;
        }
        if (request.traversal()
                || evidence.identityReduction()
                || evidence.rowMapper()
                || evidence.rowReducer()) {
            return Operation.QUERY;
        }
        if (returnPlan.shape() == ReturnShape.OPTIONAL || returnPlan.shape() == ReturnShape.LIST) {
            return Operation.QUERY;
        }

        TypeName returnType = method.typeName();
        if (returnType.equals(TypeNames.PRIMITIVE_VOID)) {
            return Operation.UPDATE;
        }
        if (returnType.equals(TypeNames.PRIMITIVE_INT) || returnType.equals(TypeNames.PRIMITIVE_LONG)) {
            throw failure(method, "Cannot infer JDBC execution from primitive " + returnType.fqName()
                    + " return type; add @Jdbc.Execution(Jdbc.ExecutionType.QUERY) or "
                    + "@Jdbc.Execution(Jdbc.ExecutionType.UPDATE)");
        }
        return Operation.QUERY;
    }

    private static InvocationControl invocationControl(List<TypedElementInfo> parameters, TypedElementInfo method) {
        Request request = new Request(null, RequestKind.NONE, null);
        TypedElementInfo options = null;
        for (int i = 0; i < parameters.size(); i++) {
            TypedElementInfo parameter = parameters.get(i);
            TypeName raw = parameter.typeName().genericTypeName();
            boolean regular = raw.equals(JdbcCodegenTypes.JDBC_RESULT_REQUEST);
            boolean visitAll = raw.equals(JdbcCodegenTypes.JDBC_RESULT_VISIT_ALL);
            boolean visitWhile = raw.equals(JdbcCodegenTypes.JDBC_RESULT_VISIT_WHILE);
            boolean call = raw.equals(JdbcCodegenTypes.JDBC_RESULT_CALL);
            boolean callWith = raw.equals(JdbcCodegenTypes.JDBC_RESULT_CALL_WITH);
            boolean invocationControl = regular || visitAll || visitWhile || call || callWith
                    || raw.equals(JdbcCodegenTypes.JDBC_STATEMENT_OPTIONS);
            boolean callableInput = parameter.hasAnnotation(JdbcCodegenTypes.JDBC_IN_PARAMETER)
                    || parameter.hasAnnotation(JdbcCodegenTypes.JDBC_IN_OUT_PARAMETER);
            if (callableInput && invocationControl) {
                throw failure(method, "JDBC invocation-control parameters cannot be IN or INOUT call parameters");
            }
            if (JdbcBindTypePlan.declared(parameter) && invocationControl) {
                throw failure(method, "JDBC invocation-control parameters cannot declare an input binding type");
            }
            if (regular) {
                throw failure(method, "JdbcResultRequest supports only typed traversal and call requests");
            }
            if (visitAll || visitWhile || call || callWith) {
                if (i != 0 || request.parameter() != null || options != null) {
                    throw failure(method, "JdbcResultRequest is permitted once and only as the leading invocation "
                            + "control parameter");
                }
                int expectedArguments = visitAll || visitWhile || callWith ? 1 : 0;
                if (parameter.typeName().typeArguments().size() != expectedArguments) {
                    throw failure(method,
                                  call
                                          ? "JdbcResultRequest.Call must not declare a type argument"
                                          : callWith
                                                  ? "JdbcResultRequest.CallWith requires one concrete result type"
                                                  : "JDBC traversal request requires one concrete mapped row type");
                }
                TypeName mappedType = expectedArguments == 0
                        ? TypeNames.BOXED_VOID
                        : parameter.typeName().typeArguments().getFirst();
                if (mappedType.wildcard()) {
                    throw failure(method,
                                  callWith
                                          ? "JdbcResultRequest.CallWith wildcard result types are not supported"
                                          : "JDBC traversal request wildcard row types are not supported");
                }
                request = new Request(parameter,
                                      visitAll
                                              ? RequestKind.VISIT_ALL
                                              : visitWhile
                                                      ? RequestKind.VISIT_WHILE
                                                      : call ? RequestKind.CALL : RequestKind.CALL_WITH,
                                      mappedType);
            } else if (raw.equals(JdbcCodegenTypes.JDBC_STATEMENT_OPTIONS)) {
                if (i != 0 || options != null || request.parameter() != null) {
                    throw failure(method, "JdbcStatementOptions is permitted once and only as the leading invocation "
                            + "control parameter and cannot be combined with JdbcResultRequest");
                }
                options = parameter;
            } else if (raw.equals(JdbcCodegenTypes.CONSUMER) || raw.equals(JdbcCodegenTypes.PREDICATE)) {
                throw failure(method, "Traversal callbacks must be supplied through a leading JdbcResultRequest");
            }
        }
        return new InvocationControl(request, options);
    }

    private static Return returnPlan(TypedElementInfo method, Request request) {
        TypeName returnType = method.typeName();
        if (request.kind() == RequestKind.VISIT_ALL) {
            return new Return(ReturnShape.VISIT_ALL, request.mappedType());
        }
        if (request.kind() == RequestKind.VISIT_WHILE) {
            return new Return(ReturnShape.VISIT_WHILE, request.mappedType());
        }
        if (request.kind() == RequestKind.CALL) {
            return new Return(ReturnShape.CALL, TypeNames.BOXED_VOID);
        }
        if (request.kind() == RequestKind.CALL_WITH) {
            return new Return(ReturnShape.CALL_WITH, request.mappedType());
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
                                                Request request) {
        if (request.call() && operation != Operation.CALL) {
            throw failure(method, "JdbcResultRequest call requests are supported only for CALL execution");
        }
        if (request.traversal() && operation != Operation.QUERY) {
            throw failure(method, "JdbcResultRequest traversal requests are supported only for QUERY execution");
        }
        if (operation == Operation.UPDATE) {
            if (request.parameter() != null) {
                throw failure(method, "UPDATE execution does not support JdbcResultRequest");
            }
            if (!method.typeName().equals(TypeNames.PRIMITIVE_LONG)
                    && !method.typeName().equals(TypeNames.PRIMITIVE_INT)
                    && !method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
                throw failure(method, "UPDATE execution must return void, primitive int, or primitive long");
            }
            return;
        }
        if (operation == Operation.CALL) {
            if (request.kind() == RequestKind.CALL) {
                if (!method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
                    throw failure(method, "JdbcResultRequest.Call methods must return void");
                }
                return;
            }
            if (request.kind() == RequestKind.CALL_WITH) {
                if (!method.typeName().equals(request.mappedType())) {
                    throw failure(method, "JdbcResultRequest.CallWith result type must exactly match the repository "
                            + "method return type");
                }
                return;
            }
            if (request.parameter() != null) {
                throw failure(method, "CALL execution requires JdbcResultRequest.Call or CallWith");
            }
            return;
        }
        if (request.kind() == RequestKind.VISIT_WHILE) {
            if (!method.typeName().equals(TypeNames.PRIMITIVE_BOOLEAN)) {
                throw failure(method, "JdbcResultRequest.VisitWhile methods must return primitive boolean");
            }
        } else if (request.kind() == RequestKind.VISIT_ALL && !method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw failure(method, "JdbcResultRequest.VisitAll methods must return void");
        } else if (request.kind() == RequestKind.NONE && method.typeName().equals(TypeNames.PRIMITIVE_VOID)) {
            throw failure(method, "Mapped query and generated-key methods require a non-void result or traversal request");
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
                                           Operation operation,
                                           TypeName mappedType,
                                           List<String> aliases,
                                           boolean identityReduction,
                                           ExplicitMapping explicitMapping) {
        if (explicitMapping.reducer() != null) {
            return MappingKind.REDUCER;
        }
        if (identityReduction) {
            return MappingKind.IDENTITY_REDUCTION;
        }
        if (explicitMapping.mapperRequested()) {
            return explicitMapping.mapper() == null ? MappingKind.SERVICE : MappingKind.EXPLICIT;
        }
        boolean dottedAlias = aliases.stream().anyMatch(alias -> alias.indexOf('.') > 0);
        if (dottedAlias && operation != Operation.QUERY) {
            throw failure(method, "Dotted SQL projection aliases require @Jdbc.IdentityReducer, "
                    + "@Jdbc.RowMapper, or @Jdbc.RowReducer");
        }
        if (isScalar(mappedType)) {
            return MappingKind.SCALAR;
        }
        TypeInfo resultInfo = context.typeInfo(mappedType.genericTypeName()).orElse(null);
        if (operation == Operation.QUERY) {
            if (!dottedAlias
                    && resultInfo != null
                    && resultInfo.kind() == ElementKind.RECORD
                    && JdbcRecordMapperGenerator.canGenerate(method, mappedType, aliases, context)) {
                return MappingKind.RECORD;
            }
            // A query result without a generated record mapping is supplied by a required RowMapper<T> service.
            return MappingKind.SERVICE;
        }
        if (resultInfo == null) {
            throw failure(method, "Mapped result type information is unavailable: " + mappedType.resolvedName());
        }
        if (resultInfo.kind() == ElementKind.RECORD) {
            return MappingKind.RECORD;
        }
        throw failure(method, "Non-record JDBC result requires @Jdbc.RowMapper or @Jdbc.RowReducer: "
                + mappedType.resolvedName());
    }

    private static List<String> identityPaths(TypedElementInfo method) {
        if (!method.hasAnnotation(JdbcCodegenTypes.JDBC_IDENTITY_REDUCER)) {
            return List.of();
        }
        List<String> paths = method.annotation(JdbcCodegenTypes.JDBC_IDENTITY_REDUCER)
                .stringValues("identityPaths")
                .orElse(List.of());
        if (paths.isEmpty()) {
            throw failure(method, "@Jdbc.IdentityReducer requires at least one identity path");
        }
        Set<String> distinct = new HashSet<>();
        for (String path : paths) {
            if (!propertyPath(path)) {
                throw failure(method, "Invalid @Jdbc.IdentityReducer identity path: '" + path + "'");
            }
            if (!distinct.add(path)) {
                throw failure(method, "Duplicate @Jdbc.IdentityReducer identity path: '" + path + "'");
            }
        }
        return List.copyOf(paths);
    }

    private static boolean propertyPath(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (String segment : value.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean isScalar(TypeName type) {
        if (type.array() && type.componentType().map(TypeName::fqName).filter("byte"::equals).isPresent()) {
            return true;
        }
        TypeName boxed = type.boxed().genericTypeName();
        return SCALAR_TYPES.contains(boxed.fqName());
    }

    /**
     * Resolves the scalar value represented by an exact {@code Optional<T>} component.
     *
     * @param type candidate optional component type
     * @return the supported scalar argument, or {@code null} when the type is not an exact supported optional scalar
     */
    static TypeName optionalScalarType(TypeName type) {
        if (!type.genericTypeName().equals(JdbcCodegenTypes.OPTIONAL)
                || type.typeArguments().size() != 1) {
            return null;
        }
        TypeName valueType = type.typeArguments().getFirst();
        return valueType.wildcard() || !isScalar(valueType) ? null : valueType;
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

    TypedElementInfo requestParameter() {
        return requestParameter;
    }

    TypedElementInfo optionsParameter() {
        return optionsParameter;
    }

    RequestKind requestKind() {
        return requestKind;
    }

    JdbcSqlParameterPlan parameterPlan() {
        return parameterPlan;
    }

    JdbcCallParameterPlan callParameterPlan() {
        return callParameterPlan;
    }

    JdbcCallResultPlan callResultPlan() {
        return callResultPlan;
    }

    String jdbcSql() {
        return callParameterPlan == null ? parameterPlan.sql() : callParameterPlan.sql();
    }

    List<String> generatedColumns() {
        return generatedColumns;
    }

    List<String> aliases() {
        return aliases;
    }

    List<String> identityPaths() {
        return identityPaths;
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

    String callFieldName() {
        return callFieldName;
    }

    void callFieldName(String callFieldName) {
        this.callFieldName = callFieldName;
    }

    enum Operation {
        QUERY,
        UPDATE,
        GENERATED_KEYS,
        CALL
    }

    enum ReturnShape {
        ITEM,
        OPTIONAL,
        LIST,
        VISIT_ALL,
        VISIT_WHILE,
        CALL,
        CALL_WITH
    }

    enum MappingKind {
        NONE,
        SCALAR,
        RECORD,
        SERVICE,
        EXPLICIT,
        REDUCER,
        IDENTITY_REDUCTION
    }

    enum RequestKind {
        NONE,
        VISIT_ALL,
        VISIT_WHILE,
        CALL,
        CALL_WITH
    }

    private record ExplicitMapping(boolean mapperRequested, TypeName mapper, TypeName reducer) {
    }

    private record Request(TypedElementInfo parameter, RequestKind kind, TypeName mappedType) {
        private boolean traversal() {
            return kind == RequestKind.VISIT_ALL || kind == RequestKind.VISIT_WHILE;
        }

        private boolean call() {
            return kind == RequestKind.CALL || kind == RequestKind.CALL_WITH;
        }
    }

    private record InvocationControl(Request request, TypedElementInfo optionsParameter) {
        private boolean present() {
            return request.parameter() != null || optionsParameter != null;
        }
    }

    private record OperationEvidence(boolean generatedKeys,
                                     boolean identityReduction,
                                     boolean rowMapper,
                                     boolean rowReducer,
                                     boolean callableAnnotations) {
    }

    private record Return(ReturnShape shape, TypeName mappedType) {
    }
}
