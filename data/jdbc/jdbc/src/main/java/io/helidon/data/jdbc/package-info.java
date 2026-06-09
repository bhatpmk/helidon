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

/**
 * Plain JDBC runtime support for generated Helidon Data repositories.
 * <h2>Runtime flow</h2>
 * <ol>
 *     <li>{@code JdbcRepositoryExecutorFactory} reads {@code data.persistence-units.jdbc}.</li>
 *     <li>The factory resolves the configured {@code javax.sql.DataSource} published by {@code /data/sql}.</li>
 *     <li>The factory publishes a qualified {@code JdbcRepositoryExecutor} service for generated repositories.</li>
 *     <li>Generated {@code __Jdbc} repositories call {@code queryList}, {@code queryOptional}, {@code queryOne},
 *     or {@code update} with SQL from {@code @Data.Query}.</li>
 *     <li>The executor owns connection acquisition, statement preparation, named parameter binding, execution,
 *     result mapping, exception translation, and JDBC resource cleanup.</li>
 * </ol>
 * <h2>Current POC boundaries</h2>
 * <p>
 * This runtime intentionally stays small. It proves the generated repository path without pulling in JPA or DbClient.
 * Production code should add generated mappers, statement plans, stronger compile-time validation, transaction scoped
 * connection reuse, generated-key support, and more complete SQL script handling.
 * </p>
 */
package io.helidon.data.jdbc;
