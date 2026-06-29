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
package io.helidon.data.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.helidon.data.DataException;

final class JdbcRunner {

    private final DataSource dataSource;

    JdbcRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    JdbcTranscript execute(JdbcOperation operation) {
        StepRef stepRef = StepRef.create(0);
        List<JdbcEvent> events = new ArrayList<>();
        List<JdbcWarningInfo> warnings = new ArrayList<>();
        ParsedSql parsed = NamedSqlParser.parse(operation.sql());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepareStatement(connection, parsed.sql(), operation.generatedKeys())) {
            if (operation.fetchSize() > 0) {
                statement.setFetchSize(operation.fetchSize());
            }
            bind(statement, parsed, operation.parameters());
            boolean hasResultSet = statement.execute();
            int ordinal = 0;
            boolean generatedKeysCollected = false;
            while (true) {
                if (hasResultSet) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        RowSet rowSet = materialize(resultSet);
                        events.add(new RowsEvent(stepRef,
                                                 ordinal++,
                                                 RowsEvent.RowRole.DIRECT_RESULT_SET,
                                                 java.util.Optional.empty(),
                                                 rowSet));
                    }
                } else {
                    long updateCount = updateCount(statement);
                    if (updateCount == -1) {
                        break;
                    }
                    events.add(new UpdateCountEvent(stepRef, ordinal++, updateCount));
                }
                if (operation.generatedKeys().requested() && !generatedKeysCollected) {
                    ordinal = collectGeneratedKeys(statement, stepRef, events, ordinal);
                    generatedKeysCollected = true;
                }
                hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
            if (operation.generatedKeys().requested() && !generatedKeysCollected) {
                collectGeneratedKeys(statement, stepRef, events, ordinal);
            }
            warnings.addAll(warnings(statement.getWarnings()));
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.empty());
            return new JdbcTranscript(List.of(step));
        } catch (SQLException e) {
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.of(failure(e)));
            JdbcTranscript transcript = new JdbcTranscript(List.of(step));
            throw new JdbcExecutionException("JDBC " + operation.kind() + " execution failed.", e, transcript);
        }
    }

    private static PreparedStatement prepareStatement(Connection connection,
                                                      String sql,
                                                      GeneratedKeysRequest generatedKeys) throws SQLException {
        if (!generatedKeys.requested()) {
            return connection.prepareStatement(sql);
        }
        if (!generatedKeys.columnNames().isEmpty()) {
            return connection.prepareStatement(sql, generatedKeys.columnNames().toArray(String[]::new));
        }
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    private static int collectGeneratedKeys(PreparedStatement statement,
                                            StepRef stepRef,
                                            List<JdbcEvent> events,
                                            int ordinal) throws SQLException {
        ResultSet resultSet = statement.getGeneratedKeys();
        if (resultSet == null) {
            events.add(new GeneratedKeysEvent(stepRef, ordinal, new RowSet(List.of(), List.of())));
            return ordinal + 1;
        }
        try (resultSet) {
            events.add(new GeneratedKeysEvent(stepRef, ordinal, materialize(resultSet)));
            return ordinal + 1;
        }
    }

    private static void bind(PreparedStatement statement, ParsedSql parsed, List<JdbcParameter> parameters) throws SQLException {
        List<Object> values = values(parsed, parameters);
        for (int i = 0; i < values.size(); i++) {
            statement.setObject(i + 1, values.get(i));
        }
    }

    private static List<Object> values(ParsedSql parsed, List<JdbcParameter> parameters) {
        return switch (parsed.parameterMode()) {
        case NONE -> noParameters(parameters);
        case NAMED -> namedValues(parsed, parameters);
        case POSITIONAL -> positionalValues(parsed, parameters);
        case ORDINAL -> ordinalValues(parsed, parameters);
        };
    }

    private static List<Object> noParameters(List<JdbcParameter> parameters) {
        if (!parameters.isEmpty()) {
            throw new DataException("SQL statement declares no parameters, but " + parameters.size()
                                            + " bindings were provided");
        }
        return List.of();
    }

    private static List<Object> namedValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        Map<String, JdbcParameter> byName = parameters.stream()
                .peek(parameter -> {
                    if (parameter.name().isEmpty()) {
                        throw new DataException("Named SQL parameters require named bindings");
                    }
                })
                .collect(Collectors.toMap(JdbcParameter::name, Function.identity(), (first, second) -> first));
        List<Object> values = new ArrayList<>(parsed.parameterNames().size());
        Set<String> used = new HashSet<>();
        for (String parameterName : parsed.parameterNames()) {
            JdbcParameter parameter = byName.get(parameterName);
            if (parameter == null) {
                throw new DataException("SQL parameter :" + parameterName + " has no matching method parameter binding");
            }
            used.add(parameterName);
            values.add(parameter.value());
        }
        if (used.size() != byName.size()) {
            Set<String> unused = new HashSet<>(byName.keySet());
            unused.removeAll(used);
            throw new DataException("SQL statement has unused named parameter bindings: " + unused);
        }
        return values;
    }

    private static List<Object> positionalValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        requirePositionalBindings(parameters);
        if (parameters.size() != parsed.parameterIndexes().size()) {
            throw new DataException("SQL statement declares " + parsed.parameterIndexes().size()
                                            + " positional parameters, but " + parameters.size()
                                            + " bindings were provided");
        }
        return parameters.stream()
                .map(JdbcParameter::value)
                .toList();
    }

    private static List<Object> ordinalValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        requirePositionalBindings(parameters);
        List<Object> values = new ArrayList<>(parsed.parameterIndexes().size());
        for (int parameterIndex : parsed.parameterIndexes()) {
            if (parameterIndex > parameters.size()) {
                throw new DataException("SQL ordinal parameter ?" + parameterIndex
                                                + " has no matching method parameter binding");
            }
            values.add(parameters.get(parameterIndex - 1).value());
        }
        return values;
    }

    private static void requirePositionalBindings(List<JdbcParameter> parameters) {
        parameters.stream()
                .filter(parameter -> !parameter.name().isEmpty())
                .findFirst()
                .ifPresent(parameter -> {
                    throw new DataException("Positional SQL parameters require positional bindings");
                });
    }

    private static RowSet materialize(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<ColumnInfo> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnInfo(metaData.getColumnLabel(i),
                                       metaData.getColumnName(i),
                                       metaData.getColumnType(i),
                                       metaData.getColumnTypeName(i),
                                       metaData.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }
        List<MaterializedRow> rows = new ArrayList<>();
        while (resultSet.next()) {
            Object[] values = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                values[i - 1] = resultSet.getObject(i);
            }
            rows.add(new MaterializedRow(columns, values));
        }
        return new RowSet(columns, rows);
    }

    private static long updateCount(Statement statement) throws SQLException {
        try {
            return statement.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException e) {
            return statement.getUpdateCount();
        }
    }

    private static List<JdbcWarningInfo> warnings(SQLWarning warning) {
        List<JdbcWarningInfo> warnings = new ArrayList<>();
        SQLWarning current = warning;
        while (current != null) {
            warnings.add(new JdbcWarningInfo(current.getSQLState(), current.getErrorCode(), current.getMessage()));
            current = current.getNextWarning();
        }
        return warnings;
    }

    private static JdbcFailure failure(SQLException e) {
        return new JdbcFailure(e.getSQLState(), e.getErrorCode(), e.getMessage());
    }
}
