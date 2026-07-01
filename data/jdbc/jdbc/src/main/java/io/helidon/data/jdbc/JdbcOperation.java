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

import java.util.List;
import java.util.Objects;

record JdbcOperation(SqlKind kind,
                     String sql,
                     List<JdbcParameter> parameters,
                     List<List<JdbcParameter>> batchParameters,
                     List<JdbcOutParameter> outParameters,
                     JdbcStatementOptions options,
                     GeneratedKeysRequest generatedKeys,
                     JdbcColumnSelection columnSelection,
                     long resultLimit) {

    JdbcOperation {
        Objects.requireNonNull(kind, "SQL kind must not be null");
        Objects.requireNonNull(sql, "SQL must not be null");
        Objects.requireNonNull(parameters, "Parameters must not be null");
        Objects.requireNonNull(batchParameters, "Batch parameters must not be null");
        Objects.requireNonNull(outParameters, "OUT parameters must not be null");
        Objects.requireNonNull(options, "Statement options must not be null");
        Objects.requireNonNull(generatedKeys, "Generated keys request must not be null");
        Objects.requireNonNull(columnSelection, "Column selection must not be null");
        if (resultLimit < 0) {
            throw new IllegalArgumentException("Result limit must not be negative");
        }
        parameters = List.copyOf(parameters);
        batchParameters = batchParameters.stream()
                .map(List::copyOf)
                .toList();
        outParameters = List.copyOf(outParameters);
    }

    static JdbcOperation query(String sql, List<JdbcParameter> parameters) {
        return query(sql, parameters, JdbcStatementOptions.DEFAULT);
    }

    static JdbcOperation query(String sql, List<JdbcParameter> parameters, JdbcStatementOptions options) {
        return query(sql, parameters, options, JdbcColumnSelection.ALL, 0);
    }

    static JdbcOperation query(String sql,
                               List<JdbcParameter> parameters,
                               JdbcStatementOptions options,
                               JdbcColumnSelection columnSelection,
                               long resultLimit) {
        return new JdbcOperation(SqlKind.QUERY,
                                 sql,
                                 parameters,
                                 List.of(),
                                 List.of(),
                                 options,
                                 GeneratedKeysRequest.none(),
                                 columnSelection,
                                 resultLimit);
    }

    static JdbcOperation update(String sql, List<JdbcParameter> parameters) {
        return update(sql, parameters, JdbcStatementOptions.DEFAULT);
    }

    static JdbcOperation update(String sql, List<JdbcParameter> parameters, JdbcStatementOptions options) {
        return update(sql, parameters, options, GeneratedKeysRequest.none());
    }

    static JdbcOperation update(String sql,
                                List<JdbcParameter> parameters,
                                JdbcStatementOptions options,
                                GeneratedKeysRequest generatedKeys) {
        return update(sql, parameters, options, generatedKeys, JdbcColumnSelection.ALL, 0);
    }

    static JdbcOperation update(String sql,
                                List<JdbcParameter> parameters,
                                JdbcStatementOptions options,
                                GeneratedKeysRequest generatedKeys,
                                JdbcColumnSelection columnSelection,
                                long resultLimit) {
        return new JdbcOperation(SqlKind.UPDATE,
                                 sql,
                                 parameters,
                                 List.of(),
                                 List.of(),
                                 options,
                                 generatedKeys,
                                 columnSelection,
                                 resultLimit);
    }

    static JdbcOperation batch(String sql, List<List<JdbcParameter>> batchParameters, JdbcStatementOptions options) {
        if (batchParameters.isEmpty()) {
            throw new IllegalArgumentException("JDBC batch operation must contain at least one batch item");
        }
        return new JdbcOperation(SqlKind.BATCH,
                                 sql,
                                 List.of(),
                                 batchParameters,
                                 List.of(),
                                 options,
                                 GeneratedKeysRequest.none(),
                                 JdbcColumnSelection.ALL,
                                 0);
    }

    static JdbcOperation call(String sql,
                              List<JdbcParameter> parameters,
                              List<JdbcOutParameter> outParameters,
                              JdbcStatementOptions options) {
        return call(sql, parameters, outParameters, options, JdbcColumnSelection.ALL, 0);
    }

    static JdbcOperation call(String sql,
                              List<JdbcParameter> parameters,
                              List<JdbcOutParameter> outParameters,
                              JdbcStatementOptions options,
                              JdbcColumnSelection columnSelection,
                              long resultLimit) {
        return new JdbcOperation(SqlKind.CALL,
                                 sql,
                                 parameters,
                                 List.of(),
                                 outParameters,
                                 options,
                                 GeneratedKeysRequest.none(),
                                 columnSelection,
                                 resultLimit);
    }
}
