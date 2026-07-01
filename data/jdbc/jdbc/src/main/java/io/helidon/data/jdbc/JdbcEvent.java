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
 * One ordered outcome captured from a JDBC statement execution.
 * <p>
 * JDBC exposes statement results as a sequence: a result set, an update count, another result set, generated keys, OUT
 * parameters, or batch item outcomes depending on the operation and driver. Implementations of this sealed interface
 * represent those outcomes after they have been detached from JDBC resources.
 * <p>
 * Every event belongs to exactly one {@link StepTranscript} and has an ordinal that preserves the order in which the
 * outcome was observed within that step. Reducers should use the event type and ordinal instead of reinterpreting the
 * original SQL string.
 */
sealed interface JdbcEvent
        permits RowsEvent, UpdateCountEvent, GeneratedKeysEvent, OutParamsEvent, BatchEvent, WarningEvent,
        NoResultEvent {

    /**
     * Step that produced this event.
     *
     * @return transcript step reference
     */
    StepRef step();

    /**
     * Zero-based event order inside the owning step.
     *
     * @return event ordinal
     */
    int ordinal();
}
