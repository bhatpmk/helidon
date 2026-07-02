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
import java.util.OptionalLong;

/**
 * Detached outcomes for one prepared JDBC batch.
 *
 * The explicit fields and nested class make the difference between a batch result and a direct update-count result
 * visible without depending on record syntax.
 */
final class JdbcBatchResult {
    private final List<JdbcBatchItem> items;

    JdbcBatchResult(List<JdbcBatchItem> items) {
        Objects.requireNonNull(items, "Batch items must not be null");
        this.items = List.copyOf(items);
    }

    List<JdbcBatchItem> items() {
        return items;
    }

    /**
     * One batch item outcome.
     *
     * The count is optional because JDBC can report success without a count or can return only a partial array after
     * a batch failure.
     */
    static final class JdbcBatchItem {
        private final BatchStatus status;
        private final OptionalLong updateCount;

        JdbcBatchItem(BatchStatus status, OptionalLong updateCount) {
            Objects.requireNonNull(status, "Batch item status must not be null");
            Objects.requireNonNull(updateCount, "Batch item update count must not be null");
            this.status = status;
            this.updateCount = updateCount;
        }

        BatchStatus status() {
            return status;
        }

        OptionalLong updateCount() {
            return updateCount;
        }
    }

    /**
     * Normalized JDBC batch status.
     */
    enum BatchStatus {
        UPDATED,
        SUCCESS_NO_INFO,
        EXECUTE_FAILED,
        NOT_REPORTED
    }
}
