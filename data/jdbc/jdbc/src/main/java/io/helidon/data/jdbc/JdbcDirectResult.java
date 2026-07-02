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

/**
 * One result in the ordered JDBC direct-result sequence.
 * <p>
 * Only result sets and update counts belong to this sequence. Generated keys, callable outputs, batch results, warnings,
 * and failures are obtained through separate JDBC APIs and are represented as attachments on the operation result.
 */
sealed interface JdbcDirectResult
        permits JdbcRowsResult, JdbcUpdateCountResult {

    /**
     * Zero-based position in the direct JDBC result sequence.
     *
     * @return direct-result ordinal
     */
    int ordinal();
}
