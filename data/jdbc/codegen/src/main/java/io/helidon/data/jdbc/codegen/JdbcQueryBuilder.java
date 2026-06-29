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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.helidon.codegen.CodegenException;
import io.helidon.data.codegen.common.RepositoryInfo;
import io.helidon.data.codegen.common.spi.PersistenceGenerator;
import io.helidon.data.codegen.query.CriteriaCondition;
import io.helidon.data.codegen.query.CriteriaOperator;
import io.helidon.data.codegen.query.DataQuery;
import io.helidon.data.codegen.query.LogicalOperator;
import io.helidon.data.codegen.query.Projection;
import io.helidon.data.codegen.query.ProjectionExpression;
import io.helidon.data.codegen.query.QueryParameters;

final class JdbcQueryBuilder implements PersistenceGenerator.QueryBuilder {

    private final RepositoryInfo repositoryInfo;

    JdbcQueryBuilder(RepositoryInfo repositoryInfo) {
        this.repositoryInfo = repositoryInfo;
    }

    @Override
    public String buildSimpleQuery(DataQuery query) {
        return buildQuery(query).query();
    }

    @Override
    public PersistenceGenerator.Query buildQuery(DataQuery query) {
        return buildQuery(query, List.of());
    }

    @Override
    public PersistenceGenerator.Query buildQuery(DataQuery query, List<CharSequence> params) {
        QueryParts parts = buildDataQuery(query, params);
        return new JdbcQuery(parts.sql(),
                             parts.settings(),
                             parts.returnType(),
                             parts.isDml());
    }

    @Override
    public PersistenceGenerator.Query buildCountQuery(DataQuery query, List<CharSequence> params) {
        QueryParts parts = buildDataQuery(query, params);
        return new JdbcQuery("SELECT COUNT(*) FROM (" + parts.sql() + ") helidon_data_count",
                             parts.settings(),
                             PersistenceGenerator.QueryReturnType.NUMBER,
                             false);
    }

    @Override
    public PersistenceGenerator.Query buildQuery(String query,
                                                 QueryParameters queryParameters,
                                                 List<MethodParameter> methodParameters) {
        return new JdbcQuery(query,
                             settings(queryParameters, methodParameters),
                             PersistenceGenerator.QueryReturnType.ENTITY,
                             false);
    }

    @Override
    public PersistenceGenerator.QueryReturnType queryReturntype(DataQuery query) {
        if (query.projection().action().isDml()) {
            return PersistenceGenerator.QueryReturnType.DML;
        }
        if (query.projection().expression().isPresent()) {
            ProjectionExpression expression = query.projection().expression().get();
            return switch (expression.operator()) {
            case Exists -> PersistenceGenerator.QueryReturnType.BOOLEAN;
            case Count, Min, Max, Sum, Avg -> PersistenceGenerator.QueryReturnType.NUMBER;
            default -> PersistenceGenerator.QueryReturnType.ENTITY;
            };
        }
        return PersistenceGenerator.QueryReturnType.ENTITY;
    }

    private QueryParts buildDataQuery(DataQuery query, Collection<CharSequence> params) {
        StringBuilder sql = new StringBuilder();
        List<PersistenceGenerator.QuerySettings> settings = new ArrayList<>();
        Iterator<CharSequence> parameters = params.iterator();
        Projection projection = query.projection();
        PersistenceGenerator.QueryReturnType returnType = queryReturntype(query);

        switch (projection.action()) {
        case Select:
            appendSelect(sql, projection);
            break;
        case Delete:
            sql.append("DELETE FROM ")
                    .append(tableName());
            break;
        case Update:
            throw new CodegenException("JDBC query-by-name update methods are not supported yet");
        default:
            throw new CodegenException("Unsupported JDBC projection action " + projection.action());
        }

        query.criteria().ifPresent(criteria -> {
            sql.append(" WHERE ");
            appendCondition(sql, criteria.first(), parameters, settings);
            criteria.next().forEach(next -> {
                sql.append(next.operator() == LogicalOperator.AND ? " AND " : " OR ");
                appendCondition(sql, next.criteria(), parameters, settings);
            });
        });

        query.order().ifPresent(order -> {
            if (!order.expressions().isEmpty()) {
                sql.append(" ORDER BY ");
                for (int i = 0; i < order.expressions().size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    var expression = order.expressions().get(i);
                    sql.append(columnName(expression.property().name()));
                    switch (expression.operator()) {
                    case ASC:
                        sql.append(" ASC");
                        break;
                    case DESC:
                        sql.append(" DESC");
                        break;
                    default:
                        throw new CodegenException("Unsupported JDBC order operator " + expression.operator());
                    }
                }
            }
        });

        return new QueryParts(sql.toString(), settings, returnType, projection.action().isDml());
    }

    private void appendSelect(StringBuilder sql, Projection projection) {
        sql.append("SELECT ");
        if (projection.expression().isPresent()) {
            ProjectionExpression expression = projection.expression().get();
            switch (expression.operator()) {
            case Count, Exists:
                sql.append("COUNT(*)");
                break;
            case Min:
                sql.append("MIN(").append(projectionProperty(projection)).append(')');
                break;
            case Max:
                sql.append("MAX(").append(projectionProperty(projection)).append(')');
                break;
            case Sum:
                sql.append("SUM(").append(projectionProperty(projection)).append(')');
                break;
            case Avg:
                sql.append("AVG(").append(projectionProperty(projection)).append(')');
                break;
            case First:
                sql.append('*');
                break;
            default:
                throw new CodegenException("Unsupported JDBC projection expression " + expression.operator());
            }
        } else {
            projection.property()
                    .ifPresentOrElse(property -> sql.append(columnName(property.name())),
                                     () -> sql.append('*'));
        }
        sql.append(" FROM ")
                .append(tableName());
    }

    private String projectionProperty(Projection projection) {
        return projection.property()
                .map(property -> columnName(property.name()))
                .orElse("*");
    }

    private void appendCondition(StringBuilder sql,
                                 CriteriaCondition condition,
                                 Iterator<CharSequence> parameters,
                                 List<PersistenceGenerator.QuerySettings> settings) {
        CriteriaOperator operator = condition.operator();
        String column = columnName(condition.property().name());
        if (condition.not()) {
            sql.append("NOT (");
        }
        switch (operator) {
        case Equal:
            binary(sql, settings, parameters, column, " = ");
            break;
        case LessThan:
        case Before:
            binary(sql, settings, parameters, column, " < ");
            break;
        case LessThanEqual:
            binary(sql, settings, parameters, column, " <= ");
            break;
        case GreaterThan:
        case After:
            binary(sql, settings, parameters, column, " > ");
            break;
        case GreaterThanEqual:
            binary(sql, settings, parameters, column, " >= ");
            break;
        case Like:
        case Contains:
        case StartsWith:
        case EndsWith:
            binary(sql, settings, parameters, column, " LIKE ");
            break;
        case In:
            binary(sql, settings, parameters, column, " IN ");
            break;
        case Null:
            sql.append(column).append(" IS NULL");
            break;
        case True:
            sql.append(column).append(" = TRUE");
            break;
        case False:
            sql.append(column).append(" = FALSE");
            break;
        case Between:
            sql.append(column)
                    .append(" BETWEEN ");
            parameter(sql, settings, parameters);
            sql.append(" AND ");
            parameter(sql, settings, parameters);
            break;
        default:
            throw new CodegenException("Unsupported JDBC criteria operator " + operator);
        }
        if (condition.not()) {
            sql.append(')');
        }
    }

    private void binary(StringBuilder sql,
                        List<PersistenceGenerator.QuerySettings> settings,
                        Iterator<CharSequence> parameters,
                        String column,
                        String operator) {
        sql.append(column)
                .append(operator);
        parameter(sql, settings, parameters);
    }

    private void parameter(StringBuilder sql,
                           List<PersistenceGenerator.QuerySettings> settings,
                           Iterator<CharSequence> parameters) {
        if (!parameters.hasNext()) {
            throw new CodegenException("Missing method parameter for JDBC query criteria");
        }
        CharSequence parameter = parameters.next();
        sql.append(':')
                .append(parameter);
        settings.add(new JdbcParameterSetting(parameter.toString(), parameter));
    }

    private List<PersistenceGenerator.QuerySettings> settings(QueryParameters queryParameters,
                                                              List<MethodParameter> methodParameters) {
        if (queryParameters.isEmpty()) {
            return List.of();
        }
        Map<String, CharSequence> aliases = methodParameters.stream()
                .collect(Collectors.toMap(it -> it.alias().toString(), MethodParameter::name));
        Map<String, PersistenceGenerator.QuerySettings> settings = new LinkedHashMap<>();
        switch (queryParameters.type()) {
        case NAMED:
            queryParameters.parameters()
                    .forEach(parameter -> {
                        CharSequence methodParameter = aliases.get(parameter.name());
                        if (methodParameter == null) {
                            throw new CodegenException("Method parameter " + parameter.name() + " is missing");
                        }
                        settings.putIfAbsent(parameter.name(), new JdbcParameterSetting(parameter.name(), methodParameter));
                    });
            break;
        case ORDINAL:
            queryParameters.parameters()
                    .forEach(parameter -> {
                        int index = parameter.index() - 1;
                        if (index >= methodParameters.size()) {
                            throw new CodegenException("Method parameter with index " + parameter.index() + " is missing");
                        }
                        settings.putIfAbsent(parameter.name(),
                                             new JdbcParameterSetting("", methodParameters.get(index).name()));
                    });
            break;
        default:
            throw new CodegenException("Unknown JDBC query parameter type " + queryParameters.type());
        }
        return List.copyOf(settings.values());
    }

    private String tableName() {
        return repositoryInfo.entity().className();
    }

    private static String columnName(CharSequence propertyName) {
        return propertyName.toString();
    }

    private record QueryParts(String sql,
                              List<PersistenceGenerator.QuerySettings> settings,
                              PersistenceGenerator.QueryReturnType returnType,
                              boolean isDml) {
    }
}
