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

import io.helidon.common.types.TypeName;

/**
 * Type names used by JDBC repository code generation.
 */
final class JdbcCodegenTypes {

    static final TypeName DATA_BIND = TypeName.create("io.helidon.data.Data.Bind");
    static final TypeName DATA_EXCEPTION = TypeName.create("io.helidon.data.DataException");
    static final TypeName DATA_GENERATED_KEYS = TypeName.create("io.helidon.data.Data.GeneratedKeys");
    static final TypeName DATA_GENERIC_REPOSITORY = TypeName.create("io.helidon.data.Data.GenericRepository");
    static final TypeName DATA_KEY = TypeName.create("io.helidon.data.Data.Key");
    static final TypeName DATA_KEYS = TypeName.create("io.helidon.data.Data.Keys");
    static final TypeName DATA_MAP = TypeName.create("io.helidon.data.Data.Map");
    static final TypeName DATA_MAPPER = TypeName.create("io.helidon.data.Data.Mapper");
    static final TypeName DATA_MAPS = TypeName.create("io.helidon.data.Data.Maps");
    static final TypeName DATA_MAP_WITH = TypeName.create("io.helidon.data.Data.MapWith");
    static final TypeName DATA_PAGE = TypeName.create("io.helidon.data.Page");
    static final TypeName DATA_PARAM = TypeName.create("io.helidon.data.Data.Param");
    static final TypeName DATA_PERSISTENCE_UNIT = TypeName.create("io.helidon.data.Data.PersistenceUnit");
    static final TypeName DATA_QUERY = TypeName.create("io.helidon.data.Data.Query");
    static final TypeName DATA_REDUCE_WITH = TypeName.create("io.helidon.data.Data.ReduceWith");
    static final TypeName DATA_SLICE = TypeName.create("io.helidon.data.Slice");
    static final TypeName JDBC_BINDER = TypeName.create("io.helidon.data.jdbc.JdbcBinder");
    static final TypeName JDBC_OPERATIONS = TypeName.create("io.helidon.data.jdbc.JdbcOperations");
    static final TypeName JDBC_RESULT_SET_ROW_VIEW = TypeName.create("io.helidon.data.jdbc.JdbcResultSetRowView");
    static final TypeName JDBC_ROW_MAPPER = TypeName.create("io.helidon.data.jdbc.JdbcRowMapper");
    static final TypeName JDBC_ROW_REDUCER = TypeName.create("io.helidon.data.jdbc.JdbcRowReducer");
    static final TypeName JDBC_STATEMENT_PLAN = TypeName.create("io.helidon.data.jdbc.JdbcStatementPlan");
    static final TypeName SERVICE_NAMED = TypeName.create("io.helidon.service.registry.Service.Named");
    static final TypeName SERVICE_SINGLETON = TypeName.create("io.helidon.service.registry.Service.Singleton");
    static final TypeName STREAM = TypeName.create("java.util.stream.Stream");

    private JdbcCodegenTypes() {
        throw new UnsupportedOperationException("No instances of JdbcCodegenTypes are allowed");
    }
}
