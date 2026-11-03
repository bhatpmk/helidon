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
package io.helidon.tests.integration.data.jdbc.declarative.pgsql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.helidon.data.Data;
import io.helidon.data.jdbc.Jdbc;

@Data.Repository
@Data.Provider("jdbc")
interface UuidRepository {
    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID (ID, RELATED) VALUES (?, ?)")
    void insert(UUID id, UUID related);

    @Jdbc.Statement("UPDATE DECLARATIVE_UUID SET RELATED = :related WHERE ID = :id")
    @Jdbc.Execution(Jdbc.ExecutionType.UPDATE)
    long update(UUID id, UUID related);

    @Jdbc.Statement("SELECT ID FROM DECLARATIVE_UUID WHERE ID = :id OR RELATED = :id ORDER BY ID")
    List<UUID> repeated(UUID id);

    @Jdbc.Statement("SELECT ID FROM DECLARATIVE_UUID ORDER BY ID")
    List<UUID> ids();

    @Jdbc.Statement("SELECT ID FROM DECLARATIVE_UUID WHERE ID = ?")
    UUID one(UUID id);

    @Jdbc.Statement("SELECT ID FROM DECLARATIVE_UUID WHERE ID = ?")
    Optional<UUID> optional(UUID id);

    @Jdbc.Statement("SELECT ID, RELATED FROM DECLARATIVE_UUID WHERE ID = ?")
    Projection projection(UUID id);

    @Jdbc.Statement("SELECT :id IS NULL")
    boolean ambiguous(UUID id);

    @Jdbc.Statement("SELECT CAST(:id AS UUID) IS NULL")
    boolean castNull(UUID id);

    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID DEFAULT VALUES")
    @Jdbc.GeneratedKeys("id")
    UUID key();

    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID DEFAULT VALUES")
    @Jdbc.GeneratedKeys
    Optional<UUID> optionalKey();

    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID DEFAULT VALUES")
    @Jdbc.GeneratedKeys("id")
    List<UUID> keys();

    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID DEFAULT VALUES")
    @Jdbc.GeneratedKeys({"id", "related"})
    Projection keyProjection();

    @Jdbc.Statement("INSERT INTO DECLARATIVE_UUID (ID, TITLE) VALUES (?, 'invalid UUID')")
    @Jdbc.GeneratedKeys("title")
    UUID invalidKey(UUID id);

    record Projection(UUID id, Optional<UUID> related) {
    }
}
