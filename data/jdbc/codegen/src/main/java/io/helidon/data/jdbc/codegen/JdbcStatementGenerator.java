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

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.codegen.CodegenContext;
import io.helidon.codegen.CodegenException;
import io.helidon.codegen.classmodel.Method;
import io.helidon.common.types.ElementKind;
import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;
import io.helidon.common.types.TypedElementInfo;
import io.helidon.data.codegen.common.BaseGenerator;
import io.helidon.data.codegen.common.MethodParams;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;
import io.helidon.data.codegen.query.DataQuery;

final class JdbcStatementGenerator extends BaseGenerator implements PersistenceGenerator.StatementGenerator {

    private static final String JDBC_CLIENT = "jdbcClient";

    private final CodegenContext codegenContext;
    private final RepositoryInfo repositoryInfo;

    JdbcStatementGenerator(CodegenContext codegenContext, RepositoryInfo repositoryInfo) {
        this.codegenContext = codegenContext;
        this.repositoryInfo = repositoryInfo;
    }

    @Override
    public TypeName executorType() {
        return JdbcTypes.CLIENT;
    }

    @Override
    public void addPersist(Method.Builder builder, String identifier) {
        unsupportedLambda(builder, "JDBC repository insert/save requires explicit SQL in this version");
    }

    @Override
    public void addMerge(Method.Builder builder, String identifier) {
        unsupportedLambda(builder, "JDBC repository save requires explicit SQL in this version");
    }

    @Override
    public void addPersistCollection(Method.Builder builder, String identifier) {
        unsupportedLambda(builder, "JDBC repository batch insert requires explicit SQL in this version");
    }

    @Override
    public void addMergeCollection(Method.Builder builder, String identifier, String merged) {
        unsupportedLambda(builder, "JDBC repository saveAll requires explicit SQL in this version");
    }

    @Override
    public void addRemove(Method.Builder builder, String identifier) {
        unsupportedLambda(builder, "JDBC repository delete(entity) requires explicit SQL in this version");
    }

    @Override
    public void addRemoveCollection(Method.Builder builder, String identifier) {
        unsupportedLambda(builder, "JDBC repository deleteAll(entities) requires explicit SQL in this version");
    }

    @Override
    public void addFind(Method.Builder builder, String identifier, TypeName entity) {
        lambda(builder, b -> {
            b.addContent(JDBC_CLIENT)
                    .addContent(".execute(\"SELECT * FROM ")
                    .addContent(entity.className())
                    .addContent(" WHERE ")
                    .addContent(repositoryInfo.idName())
                    .addContent(" = :")
                    .addContent(repositoryInfo.idName())
                    .addContent("\").params(")
                    .addContent(List.class)
                    .addContent(".of(")
                    .addContent(JdbcTypes.PARAMETER)
                    .addContent(".create(\"")
                    .addContent(repositoryInfo.idName())
                    .addContent("\", ")
                    .addContent(identifier)
                    .addContent(")))");
            readColumns(b, entity);
            b.addContent(".single(");
            mapper(b, entity);
            b.addContent(")");
        });
    }

    @Override
    public void addUpdate(Method.Builder builder, String executor, String identifier, TypeName entity) {
        unsupportedLambda(builder, "JDBC repository update requires explicit SQL in this version");
    }

    @Override
    public void addUpdateAll(Method.Builder builder,
                             String executor,
                             String srcEntities,
                             String updatedEntities,
                             TypeName entity) {
        unsupportedLambda(builder, "JDBC repository updateAll requires explicit SQL in this version");
    }

    @Override
    public void addExecuteSimpleQueryItem(Method.Builder builder, String query, TypeName returnType) {
        addQueryItemLambda(builder, query, List.of(), returnType);
    }

    @Override
    public void addExecuteSimpleQueryList(Method.Builder builder, String query, TypeName entity) {
        addQueryListLambda(builder, query, List.of(), entity);
    }

    @Override
    public void addExecuteSimpleQueryStream(Method.Builder builder, String query, TypeName entity) {
        throw new CodegenException("JDBC direct Stream<T> returns are not resource-safe; use callback streaming");
    }

    @Override
    public void addExecuteQueryItem(Method.Builder builder, PersistenceGenerator.Query query, TypeName returnType) {
        if (query.isDml()) {
            addUpdateItemLambda(builder, query, returnType);
        } else {
            addQueryItemLambda(builder, query.query(), query.settings(), returnType);
        }
    }

    @Override
    public void addExecuteDynamicQueryItem(Method.Builder builder,
                                           RepositoryInfo repositoryInfo,
                                           TypedElementInfo methodInfo,
                                           MethodParams methodParams,
                                           DataQuery dataQuery,
                                           TypeName returnType) {
        throw new CodegenException("JDBC dynamic query item methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addExecuteQueryItemOrNull(Method.Builder builder, PersistenceGenerator.Query query, TypeName returnType) {
        if (query.isDml()) {
            addExecuteQueryItem(builder, query, returnType);
        } else {
            addQueryItemOrNullLambda(builder, query.query(), query.settings(), returnType);
        }
    }

    @Override
    public void addExecuteDynamicQueryItemOrNull(Method.Builder builder,
                                                 RepositoryInfo repositoryInfo,
                                                 TypedElementInfo methodInfo,
                                                 MethodParams methodParams,
                                                 DataQuery dataQuery,
                                                 TypeName returnType) {
        throw new CodegenException("JDBC dynamic optional query methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addExecuteQueryList(Method.Builder builder, PersistenceGenerator.Query query, TypeName returnType) {
        if (query.isDml()) {
            throw new CodegenException("JDBC DML SQL cannot return a list without explicit generated-key support");
        }
        addQueryListLambda(builder, query.query(), query.settings(), returnType);
    }

    @Override
    public void addExecuteDynamicQueryList(Method.Builder builder,
                                           RepositoryInfo repositoryInfo,
                                           TypedElementInfo methodInfo,
                                           MethodParams methodParams,
                                           DataQuery dataQuery,
                                           TypeName returnType) {
        throw new CodegenException("JDBC dynamic query list methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addExecuteQueryStream(Method.Builder builder, PersistenceGenerator.Query query, TypeName returnType) {
        throw new CodegenException("JDBC direct Stream<T> returns are not resource-safe; use callback streaming");
    }

    @Override
    public void addExecuteDynamicQueryStream(Method.Builder builder,
                                             RepositoryInfo repositoryInfo,
                                             TypedElementInfo methodInfo,
                                             MethodParams methodParams,
                                             DataQuery dataQuery,
                                             TypeName returnType) {
        throw new CodegenException("JDBC dynamic query stream methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addQueryItem(Method.Builder builder, PersistenceGenerator.Query query, TypeName returnType) {
        if (query.isDml()) {
            addUpdateItemCall(builder, query, returnType);
        } else {
            addQueryItemCall(builder, query.query(), query.settings(), returnType);
        }
    }

    @Override
    public void addQueryPage(Method.Builder builder,
                             PersistenceGenerator.Query query,
                             TypeName returnType,
                             String firstResult,
                             String maxResults) {
        addQueryListCall(builder,
                         query.query() + " OFFSET " + firstResult + " ROWS FETCH NEXT " + maxResults + " ROWS ONLY",
                         query.settings(),
                         returnType);
    }

    @Override
    public void addQueryPage(Method.Builder builder,
                             Consumer<Method.Builder> queryContent,
                             List<PersistenceGenerator.QuerySettings> settings,
                             TypeName returnType,
                             String firstResult,
                             String maxResults) {
        throw new CodegenException("JDBC dynamic page query methods are not supported yet");
    }

    @Override
    public void addQueryCount(Method.Builder builder, PersistenceGenerator.Query query) {
        addQueryItemCall(builder,
                         "SELECT COUNT(*) FROM (" + query.query() + ") helidon_data_count",
                         query.settings(),
                         JdbcTypes.NUMBER);
    }

    @Override
    public void addQueryCount(Method.Builder builder,
                              Consumer<Method.Builder> queryContent,
                              List<PersistenceGenerator.QuerySettings> settings,
                              TypeName returnType) {
        throw new CodegenException("JDBC dynamic count query methods are not supported yet");
    }

    @Override
    public List<PersistenceGenerator.QuerySettings> addDynamicSliceQuery(Method.Builder builder,
                                                                         RepositoryInfo repositoryInfo,
                                                                         TypedElementInfo methodInfo,
                                                                         MethodParams methodParams,
                                                                         DataQuery dataQuery,
                                                                         String dataQueryStatement,
                                                                         TypeName returnType) {
        throw new CodegenException("JDBC dynamic slice query methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public List<PersistenceGenerator.QuerySettings> addDynamicPageQueries(Method.Builder builder,
                                                                          RepositoryInfo repositoryInfo,
                                                                          TypedElementInfo methodInfo,
                                                                          MethodParams methodParams,
                                                                          DataQuery dataQuery,
                                                                          String dataQueryStatement,
                                                                          String countQueryStatement,
                                                                          TypeName returnType) {
        throw new CodegenException("JDBC dynamic page query methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addExecuteSimpleDml(Method.Builder builder, String dml) {
        addUpdateLambda(builder, dml, List.of());
    }

    @Override
    public void addExecuteDml(Method.Builder builder, PersistenceGenerator.Query dml) {
        addUpdateLambda(builder, dml.query(), dml.settings());
    }

    @Override
    public void addDynamicDml(Method.Builder builder,
                              RepositoryInfo repositoryInfo,
                              TypedElementInfo methodInfo,
                              MethodParams methodParams,
                              DataQuery dataQuery,
                              TypeName returnType) {
        throw new CodegenException("JDBC dynamic DML methods are not supported yet", methodInfo.originatingElement());
    }

    @Override
    public void addSessionLambda(Method.Builder builder, Consumer<Method.Builder> content) {
        lambda(builder, content);
    }

    @Override
    public void addSessionLambdaBlock(Method.Builder builder, Consumer<Method.Builder> content) {
        lambdaBlock(builder, content);
    }

    void addDirectQueryItem(Method.Builder builder,
                            String sql,
                            List<PersistenceGenerator.QuerySettings> settings,
                            TypeName returnType) {
        addQueryItemCall(builder, sql, settings, returnType);
    }

    void addDirectQueryScalar(Method.Builder builder,
                              String sql,
                              List<PersistenceGenerator.QuerySettings> settings,
                              TypeName returnType) {
        statement(builder, sql, settings);
        builder.addContent(".readColumns(1)");
        builder.addContent(".resultScalar(")
                .addContent(returnType.className())
                .addContent(".class)");
    }

    void addDirectQueryOptional(Method.Builder builder,
                                String sql,
                                List<PersistenceGenerator.QuerySettings> settings,
                                TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".optional(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectQueryList(Method.Builder builder,
                            String sql,
                            List<PersistenceGenerator.QuerySettings> settings,
                            TypeName returnType) {
        addQueryListCall(builder, sql, settings, returnType);
    }

    void addDirectWithRows(Method.Builder builder,
                           String sql,
                           List<PersistenceGenerator.QuerySettings> settings,
                           String callback,
                           TypeName rowType) {
        statement(builder, sql, settings);
        readColumns(builder, rowType);
        builder.addContent(".withRows(");
        mapper(builder, rowType);
        builder.addContent(", ")
                .addContent(callback)
                .addContent(")");
    }

    void addDirectSlice(Method.Builder builder,
                        String sql,
                        List<PersistenceGenerator.QuerySettings> settings,
                        String pageRequest,
                        TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".slice(")
                .addContent(pageRequest)
                .addContent(", ");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectPage(Method.Builder builder,
                       String sql,
                       String countSql,
                       List<PersistenceGenerator.QuerySettings> settings,
                       String pageRequest,
                       TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".page(")
                .addContent(pageRequest)
                .addContent(", \"")
                .addContent(escape(countSql))
                .addContent("\", ");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectGeneratedKey(Method.Builder builder,
                               String sql,
                               List<PersistenceGenerator.QuerySettings> settings,
                               List<String> columns,
                               TypeName returnType) {
        generatedKeysStatement(builder, sql, settings, columns, returnType);
        builder.addContent(".generatedKey(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectOptionalGeneratedKey(Method.Builder builder,
                                       String sql,
                                       List<PersistenceGenerator.QuerySettings> settings,
                                       List<String> columns,
                                       TypeName returnType) {
        generatedKeysStatement(builder, sql, settings, columns, returnType);
        builder.addContent(".optionalGeneratedKey(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectGeneratedKeys(Method.Builder builder,
                                String sql,
                                List<PersistenceGenerator.QuerySettings> settings,
                                List<String> columns,
                                TypeName returnType) {
        generatedKeysStatement(builder, sql, settings, columns, returnType);
        builder.addContent(".generatedKeys(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    void addDirectDiscard(Method.Builder builder,
                          String sql,
                          List<PersistenceGenerator.QuerySettings> settings) {
        statement(builder, sql, settings);
        builder.addContent(".discard()");
    }

    void addDirectCallVoid(Method.Builder builder,
                           String sql,
                           List<PersistenceGenerator.QuerySettings> settings,
                           List<JdbcCallParameter> outParameters) {
        callableStatement(builder, sql, settings, outParameters);
        builder.addContent(".outParams()");
    }

    void addDirectCallOutParams(Method.Builder builder,
                                String sql,
                                List<PersistenceGenerator.QuerySettings> settings,
                                List<JdbcCallParameter> outParameters) {
        callableStatement(builder, sql, settings, outParameters);
        builder.addContent(".outParams()");
    }

    void addDirectCallOutParam(Method.Builder builder,
                               String sql,
                               List<PersistenceGenerator.QuerySettings> settings,
                               List<JdbcCallParameter> outParameters,
                               String name,
                               TypeName returnType) {
        callableStatement(builder, sql, settings, outParameters);
        builder.addContent(".outParam(\"")
                .addContent(escape(name))
                .addContent("\", ")
                .addContent(JdbcTypes.wrapper(returnType))
                .addContent(".class)");
    }

    void addDirectCallOptionalOutParam(Method.Builder builder,
                                       String sql,
                                       List<PersistenceGenerator.QuerySettings> settings,
                                       List<JdbcCallParameter> outParameters,
                                       String name,
                                       TypeName returnType) {
        builder.addContent(Optional.class)
                .addContent(".ofNullable(");
        addDirectCallOutParam(builder, sql, settings, outParameters, name, returnType);
        builder.addContent(")");
    }

    void addDirectCallOutCursor(Method.Builder builder,
                                String sql,
                                List<PersistenceGenerator.QuerySettings> settings,
                                List<JdbcCallParameter> outParameters,
                                String name,
                                TypeName rowType) {
        callableStatement(builder, sql, settings, outParameters);
        readColumns(builder, rowType);
        builder.addContent(".outCursor(\"")
                .addContent(escape(name))
                .addContent("\", ");
        mapper(builder, rowType);
        builder.addContent(")");
    }

    private void addQueryItemLambda(Method.Builder builder,
                                    String sql,
                                    List<PersistenceGenerator.QuerySettings> settings,
                                    TypeName returnType) {
        lambda(builder, b -> addQueryItemCall(b, sql, settings, returnType));
    }

    private void addQueryItemOrNullLambda(Method.Builder builder,
                                          String sql,
                                          List<PersistenceGenerator.QuerySettings> settings,
                                          TypeName returnType) {
        lambda(builder, b -> addQueryItemOrNullCall(b, sql, settings, returnType));
    }

    private void addQueryListLambda(Method.Builder builder,
                                    String sql,
                                    List<PersistenceGenerator.QuerySettings> settings,
                                    TypeName returnType) {
        lambda(builder, b -> addQueryListCall(b, sql, settings, returnType));
    }

    private void addQueryItemCall(Method.Builder builder,
                                  String sql,
                                  List<PersistenceGenerator.QuerySettings> settings,
                                  TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".single(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    private void addQueryItemOrNullCall(Method.Builder builder,
                                        String sql,
                                        List<PersistenceGenerator.QuerySettings> settings,
                                        TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".singleOrNull(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    private void addQueryListCall(Method.Builder builder,
                                  String sql,
                                  List<PersistenceGenerator.QuerySettings> settings,
                                  TypeName returnType) {
        statement(builder, sql, settings);
        readColumns(builder, returnType);
        builder.addContent(".list(");
        mapper(builder, returnType);
        builder.addContent(")");
    }

    private void addUpdateLambda(Method.Builder builder, String sql, List<PersistenceGenerator.QuerySettings> settings) {
        lambda(builder, b -> addUpdateCall(b, sql, settings));
    }

    private void addUpdateItemLambda(Method.Builder builder,
                                     PersistenceGenerator.Query query,
                                     TypeName returnType) {
        lambda(builder, b -> addUpdateItemCall(b, query, returnType));
    }

    private void addUpdateItemCall(Method.Builder builder,
                                   PersistenceGenerator.Query query,
                                   TypeName returnType) {
        TypeName type = JdbcTypes.wrapper(returnType);
        if (type.equals(TypeNames.BOXED_BOOLEAN)) {
            statement(builder, query.query(), query.settings());
            builder.addContent(".updateCountBoolean()");
        } else if (type.equals(TypeNames.BOXED_INT)) {
            statement(builder, query.query(), query.settings());
            builder.addContent(".updateCountInt()");
        } else if (type.equals(TypeNames.BOXED_LONG)) {
            statement(builder, query.query(), query.settings());
            builder.addContent(".updateCountLong()");
        } else if (type.equals(JdbcTypes.NUMBER)) {
            addUpdateCall(builder, query.query(), query.settings());
        } else if (returnType.equals(TypeNames.PRIMITIVE_VOID) || returnType.equals(TypeNames.BOXED_VOID)) {
            addUpdateCall(builder, query.query(), query.settings());
        } else {
            throw new CodegenException("JDBC DML SQL cannot return " + returnType);
        }
    }

    private void addUpdateCall(Method.Builder builder, String sql, List<PersistenceGenerator.QuerySettings> settings) {
        statement(builder, sql, settings);
        builder.addContent(".updateCount()");
    }

    private static void statement(Method.Builder builder, String sql, List<PersistenceGenerator.QuerySettings> settings) {
        builder.addContent(JDBC_CLIENT)
                .addContent(".execute(\"")
                .addContent(escape(sql))
                .addContent("\")");
        if (!settings.isEmpty()) {
            builder.addContent(".params(");
            parameters(builder, settings);
            builder.addContent(")");
        }
    }

    private static void callableStatement(Method.Builder builder,
                                          String sql,
                                          List<PersistenceGenerator.QuerySettings> settings,
                                          List<JdbcCallParameter> outParameters) {
        statement(builder, sql, settings);
        for (JdbcCallParameter outParameter : outParameters) {
            if (outParameter.cursor()) {
                builder.addContent(".outCursor(");
            } else {
                builder.addContent(".outParam(");
            }
            builder.addContent(String.valueOf(outParameter.index()))
                    .addContent(", \"")
                    .addContent(escape(outParameter.name()))
                    .addContent("\", ")
                    .addContent(String.valueOf(outParameter.sqlType()))
                    .addContent(")");
        }
    }

    private void generatedKeysStatement(Method.Builder builder,
                                        String sql,
                                        List<PersistenceGenerator.QuerySettings> settings,
                                        List<String> columns,
                                        TypeName returnType) {
        statement(builder, sql, settings);
        if (!columns.isEmpty()) {
            builder.addContent(".generatedKeyColumns(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    builder.addContent(", ");
                }
                builder.addContent("\"")
                        .addContent(escape(columns.get(i)))
                        .addContent("\"");
            }
            builder.addContent(")");
        }
        readColumns(builder, returnType);
    }

    private static void parameters(Method.Builder builder, List<PersistenceGenerator.QuerySettings> settings) {
        builder.addContent(List.class)
                .addContent(".of(");
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0) {
                builder.addContent(", ");
            }
            builder.addContent(settings.get(i).code().toString());
        }
        builder.addContent(")");
    }

    private static void lambda(Method.Builder builder, Consumer<Method.Builder> content) {
        builder.addContent(JDBC_CLIENT)
                .addContent(" -> ");
        content.accept(builder);
    }

    private static void lambdaBlock(Method.Builder builder, Consumer<Method.Builder> content) {
        builder.addContent(JDBC_CLIENT)
                .addContentLine(" -> {");
        increasePadding(builder, 2);
        content.accept(builder);
        decreasePadding(builder, 2);
        builder.addContent("}");
    }

    private void mapper(Method.Builder builder, TypeName returnType) {
        TypeName type = JdbcTypes.wrapper(returnType);
        if (JdbcTypes.isScalar(type)) {
            builder.addContent("row -> row.value(1, ")
                    .addContent(type)
                    .addContent(".class)");
            return;
        }
        TypeInfo typeInfo = codegenContext.typeInfo(type)
                .orElseThrow(() -> new CodegenException("JDBC provider cannot map " + type
                                                                 + " because type information is not available"));
        if (typeInfo.kind() == ElementKind.RECORD) {
            recordMapper(builder, type, typeInfo);
            return;
        }
        throw new CodegenException("JDBC provider cannot map " + type
                                           + ". Use a scalar return type or a record projection.");
    }

    private void readColumns(Method.Builder builder, TypeName returnType) {
        TypeName type = JdbcTypes.wrapper(returnType);
        if (JdbcTypes.isScalar(type)) {
            builder.addContent(".readColumns(1)");
            return;
        }
        Optional<TypeInfo> typeInfo = codegenContext.typeInfo(type);
        if (typeInfo.isEmpty() || typeInfo.get().kind() != ElementKind.RECORD) {
            return;
        }
        List<TypedElementInfo> components = recordComponents(type, typeInfo.get());
        builder.addContent(".readColumns(");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                builder.addContent(", ");
            }
            builder.addContent("\"")
                    .addContent(escape(components.get(i).elementName()))
                    .addContent("\"");
        }
        builder.addContent(")");
    }

    private void recordMapper(Method.Builder builder, TypeName type, TypeInfo typeInfo) {
        List<TypedElementInfo> components = recordComponents(type, typeInfo);
        builder.addContent("row -> new ")
                .addContent(type)
                .addContent("(");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                builder.addContent(", ");
            }
            TypedElementInfo component = components.get(i);
            builder.addContent("row.value(\"")
                    .addContent(component.elementName())
                    .addContent("\", ")
                    .addContent(JdbcTypes.wrapper(component.typeName()))
                    .addContent(".class)");
        }
        builder.addContent(")");
    }

    private static List<TypedElementInfo> recordComponents(TypeName type, TypeInfo typeInfo) {
        List<TypedElementInfo> components = typeInfo.elementInfo()
                .stream()
                .filter(it -> it.kind() == ElementKind.RECORD_COMPONENT)
                .toList();
        if (components.isEmpty()) {
            components = typeInfo.elementInfo()
                    .stream()
                    .filter(it -> it.kind() == ElementKind.CONSTRUCTOR)
                    .filter(it -> !it.parameterArguments().isEmpty())
                    .findFirst()
                    .map(TypedElementInfo::parameterArguments)
                    .orElse(List.of());
        }
        if (components.isEmpty()) {
            throw new CodegenException("JDBC provider cannot map record " + type + " because record components are not visible");
        }
        return components;
    }

    private static void unsupportedLambda(Method.Builder builder, String message) {
        throw new CodegenException(message);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
