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
    static final TypeName JDBC_EXECUTION_OPTIONS = TypeName.create("io.helidon.data.jdbc.JdbcExecutionOptions");
    static final TypeName ROW_MAPPER = TypeName.create("io.helidon.data.jdbc.JdbcClient.RowMapper");
    static final TypeName ROW_REDUCER = TypeName.create("io.helidon.data.jdbc.JdbcClient.RowReducer");
    static final TypeName DATA_QUERY = TypeName.create("io.helidon.data.Data.Query");
    static final TypeName DATA_UPDATE = TypeName.create("io.helidon.data.Data.Update");
    static final TypeName DATA_GENERATED_KEYS = TypeName.create("io.helidon.data.Data.GeneratedKeys");
    static final TypeName DATA_BEAN_MAPPING = TypeName.create("io.helidon.data.Data.BeanMapping");
    static final TypeName DATA_BEAN_MAPPINGS = TypeName.create("io.helidon.data.Data.BeanMappings");
    static final TypeName DATA_ROW_MAPPER = TypeName.create("io.helidon.data.Data.RowMapper");
    static final TypeName DATA_ROW_REDUCER = TypeName.create("io.helidon.data.Data.RowReducer");
    static final TypeName DATA_JDBC_TYPE = TypeName.create("io.helidon.data.Data.JdbcType");
    static final TypeName DATA_PERSISTENCE_UNIT = TypeName.create("io.helidon.data.Data.PersistenceUnit");
    static final TypeName DATA_PROVIDER_TYPE = TypeName.create("io.helidon.data.Data.ProviderType");
    static final TypeName SERVICE_SINGLETON = TypeName.create("io.helidon.service.registry.Service.Singleton");
    static final TypeName SERVICE_NAMED = TypeName.create("io.helidon.service.registry.Service.Named");
    static final TypeName OPTIONAL = TypeName.create("java.util.Optional");
    static final TypeName SUPPLIER = TypeName.create("java.util.function.Supplier");
    static final TypeName CONSUMER = TypeName.create("java.util.function.Consumer");
    static final TypeName PREDICATE = TypeName.create("java.util.function.Predicate");
    static final TypeName ITERABLE = TypeName.create("java.lang.Iterable");

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
