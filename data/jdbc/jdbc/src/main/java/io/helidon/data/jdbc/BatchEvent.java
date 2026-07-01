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
import java.util.OptionalLong;

/**
 * Event containing per-item outcomes from a JDBC batch execution.
 * <p>
 * JDBC batch results are not a single update count. Drivers return one count or sentinel per attempted batch item, and
 * a {@link java.sql.BatchUpdateException} may contain partial counts when execution fails partway through the batch.
 * This event preserves those per-item outcomes so reducers can return count arrays and exceptions can carry partial
 * progress.
 *
 * @param step owning transcript step
 * @param ordinal event order within the step
 * @param items ordered batch item outcomes
 */
record BatchEvent(StepRef step, int ordinal, List<BatchItem> items) implements JdbcEvent {

    BatchEvent {
        items = List.copyOf(items);
    }

    /**
     * Outcome for one batch item.
     *
     * @param status normalized JDBC batch item status
     * @param updateCount update count when the driver reported one
     */
    record BatchItem(BatchStatus status, OptionalLong updateCount) {
    }

    /**
     * Normalized JDBC batch status for one batch item.
     */
    enum BatchStatus {
        /**
         * The item succeeded and JDBC reported an update count.
         */
        UPDATED,

        /**
         * The item succeeded, but the driver did not report a count.
         */
        SUCCESS_NO_INFO,

        /**
         * The driver reported that this item failed.
         */
        EXECUTE_FAILED,

        /**
         * Batch execution failed before this item was attempted.
         */
        NOT_ATTEMPTED
    }
}
