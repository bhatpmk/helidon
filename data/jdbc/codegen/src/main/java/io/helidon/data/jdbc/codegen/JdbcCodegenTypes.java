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

import io.helidon.common.types.TypeName;

/**
 * Shared compile-time names for the JDBC provider without a runtime module dependency.
 */
final class JdbcCodegenTypes {
    static final String DEFAULT_NAME = "@default";
    static final TypeName JDBC_CLIENT = TypeName.create("io.helidon.data.jdbc.JdbcClient");
    static final TypeName JDBC_CALL_OUTPUT_VALUES =
            TypeName.create("io.helidon.data.jdbc.JdbcClient.CallOutputValues");
    static final TypeName JDBC_RESULT_REQUEST = TypeName.create("io.helidon.data.jdbc.JdbcResultRequest");
    static final TypeName JDBC_RESULT_VISIT_ALL = TypeName.create("io.helidon.data.jdbc.JdbcResultRequest.VisitAll");
    static final TypeName JDBC_RESULT_VISIT_WHILE = TypeName.create("io.helidon.data.jdbc.JdbcResultRequest.VisitWhile");
    static final TypeName JDBC_RESULT_CALL = TypeName.create("io.helidon.data.jdbc.JdbcResultRequest.Call");
    static final TypeName JDBC_RESULT_CALL_WITH = TypeName.create("io.helidon.data.jdbc.JdbcResultRequest.CallWith");
    static final TypeName JDBC_STATEMENT_OPTIONS = TypeName.create("io.helidon.data.jdbc.JdbcStatementOptions");
    static final TypeName JDBC_CALL = TypeName.create("io.helidon.data.jdbc.JdbcCall");
    static final TypeName ROW_MAPPER = TypeName.create("io.helidon.data.jdbc.JdbcClient.RowMapper");
    static final TypeName ROW_REDUCER = TypeName.create("io.helidon.data.jdbc.JdbcClient.RowReducer");
    static final TypeName JDBC_STATEMENT = TypeName.create("io.helidon.data.jdbc.Jdbc.Statement");
    static final TypeName JDBC_EXECUTION = TypeName.create("io.helidon.data.jdbc.Jdbc.Execution");
    static final TypeName JDBC_GENERATED_KEYS = TypeName.create("io.helidon.data.jdbc.Jdbc.GeneratedKeys");
    static final TypeName JDBC_IDENTITY_REDUCER = TypeName.create("io.helidon.data.jdbc.Jdbc.IdentityReducer");
    static final TypeName JDBC_ROW_MAPPER = TypeName.create("io.helidon.data.jdbc.Jdbc.RowMapper");
    static final TypeName JDBC_ROW_REDUCER = TypeName.create("io.helidon.data.jdbc.Jdbc.RowReducer");
    static final TypeName JDBC_BIND_TYPE = TypeName.create("io.helidon.data.jdbc.Jdbc.BindType");
    static final TypeName JDBC_IN_PARAMETER = TypeName.create("io.helidon.data.jdbc.Jdbc.InParameter");
    static final TypeName JDBC_IN_OUT_PARAMETER = TypeName.create("io.helidon.data.jdbc.Jdbc.InOutParameter");
    static final TypeName JDBC_OUT_PARAMETER = TypeName.create("io.helidon.data.jdbc.Jdbc.OutParameter");
    static final TypeName JDBC_OUT_PARAMETERS = TypeName.create("io.helidon.data.jdbc.Jdbc.OutParameters");
    static final TypeName JDBC_RETURN_PARAMETER = TypeName.create("io.helidon.data.jdbc.Jdbc.ReturnParameter");
    static final TypeName DATA_PERSISTENCE_UNIT = TypeName.create("io.helidon.data.Data.PersistenceUnit");
    static final TypeName DATA_PROVIDER_TYPE = TypeName.create("io.helidon.data.Data.ProviderType");
    static final TypeName SERVICE_SINGLETON = TypeName.create("io.helidon.service.registry.Service.Singleton");
    static final TypeName SERVICE_NAMED = TypeName.create("io.helidon.service.registry.Service.Named");
    static final TypeName OPTIONAL = TypeName.create("java.util.Optional");
    static final TypeName JDBC_TYPE = TypeName.create("java.sql.JDBCType");
    static final TypeName SUPPLIER = TypeName.create("java.util.function.Supplier");
    static final TypeName CONSUMER = TypeName.create("java.util.function.Consumer");
    static final TypeName PREDICATE = TypeName.create("java.util.function.Predicate");

    static final List<TypeName> TX_ANNOTATIONS = List.of(
            TypeName.create("io.helidon.transaction.Tx.Mandatory"),
            TypeName.create("io.helidon.transaction.Tx.New"),
            TypeName.create("io.helidon.transaction.Tx.Never"),
            TypeName.create("io.helidon.transaction.Tx.Required"),
            TypeName.create("io.helidon.transaction.Tx.Supported"),
            TypeName.create("io.helidon.transaction.Tx.Unsupported"));

    private JdbcCodegenTypes() {
    }
}
