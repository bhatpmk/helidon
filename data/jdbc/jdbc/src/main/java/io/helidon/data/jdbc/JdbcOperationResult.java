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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.helidon.data.DataException;

/**
 * Detached results produced by one JDBC operation.
 * <p>
 * Direct JDBC results retain their JDBC order in {@link #directResults()}. Generated keys, callable outputs, batch
 * outcomes, warnings, and failures are separate attachments because JDBC exposes them through separate APIs and does
 * not define them as members of the direct result sequence.
 *
 * The explicit fields and accessors make the distinction between the ordered direct-result sequence and separately
 * scoped attachments visible without relying on record syntax.
 */
final class JdbcOperationResult {
    private final StepRef ref;
    private final SqlKind kind;
    private final JdbcResultSequence directResults;
    private final Optional<RowSet> generatedKeys;
    private final JdbcCallableOutputs callableOutputs;
    private final Optional<JdbcBatchResult> batch;
    private final List<JdbcWarningInfo> warnings;
    private final Optional<JdbcFailure> failure;

    JdbcOperationResult(StepRef ref,
                        SqlKind kind,
                        JdbcResultSequence directResults,
                        Optional<RowSet> generatedKeys,
                        JdbcCallableOutputs callableOutputs,
                        Optional<JdbcBatchResult> batch,
                        List<JdbcWarningInfo> warnings,
                        Optional<JdbcFailure> failure) {
        Objects.requireNonNull(ref, "Operation reference must not be null");
        Objects.requireNonNull(kind, "SQL kind must not be null");
        Objects.requireNonNull(directResults, "Direct results must not be null");
        Objects.requireNonNull(callableOutputs, "Callable outputs must not be null");
        Objects.requireNonNull(warnings, "Warnings must not be null");
        this.ref = ref;
        this.kind = kind;
        this.directResults = directResults;
        this.generatedKeys = generatedKeys == null ? Optional.empty() : generatedKeys;
        this.callableOutputs = callableOutputs;
        this.batch = batch == null ? Optional.empty() : batch;
        this.warnings = List.copyOf(warnings);
        this.failure = failure == null ? Optional.empty() : failure;
    }

    StepRef ref() {
        return ref;
    }

    SqlKind kind() {
        return kind;
    }

    JdbcResultSequence directResults() {
        return directResults;
    }

    Optional<RowSet> generatedKeys() {
        return generatedKeys;
    }

    JdbcCallableOutputs callableOutputs() {
        return callableOutputs;
    }

    Optional<JdbcBatchResult> batch() {
        return batch;
    }

    List<JdbcWarningInfo> warnings() {
        return warnings;
    }

    Optional<JdbcFailure> failure() {
        return failure;
    }

    /**
     * Create an execution-time builder for one operation.
     *
     * @param ref operation reference
     * @param kind operation kind
     * @return mutable builder confined to the current JDBC execution
     */
    static Builder builder(StepRef ref, SqlKind kind) {
        return new Builder(ref, kind);
    }

    /**
     * Mutable capture state used only while JDBC resources are open.
     * <p>
     * The builder stores typed detached values directly. It is never returned to application code and is not shared
     * between threads.
     */
    static final class Builder {
        private final StepRef ref;
        private final SqlKind kind;
        private final List<JdbcDirectResult> directResults = new java.util.ArrayList<>();
        private final Map<String, Object> outputValues = new LinkedHashMap<>();
        private final Map<String, RowSet> outputCursors = new LinkedHashMap<>();
        private RowSet generatedKeys;
        private JdbcBatchResult batch;

        private Builder(StepRef ref, SqlKind kind) {
            this.ref = Objects.requireNonNull(ref, "Operation reference must not be null");
            this.kind = Objects.requireNonNull(kind, "SQL kind must not be null");
        }

        void addRows(RowSet rows) {
            directResults.add(new JdbcRowsResult(directResults.size(), rows));
        }

        void addUpdateCount(long count) {
            directResults.add(new JdbcUpdateCountResult(directResults.size(), count));
        }

        int directResultCount() {
            return directResults.size();
        }

        void addGeneratedKeys(RowSet rows) {
            if (generatedKeys != null) {
                throw new DataException("JDBC operation contains multiple generated-key result sets");
            }
            generatedKeys = Objects.requireNonNull(rows, "Generated keys must not be null");
        }

        void addOutput(String name, Object value) {
            Objects.requireNonNull(name, "OUT parameter name must not be null");
            if (outputValues.containsKey(name)) {
                throw new DataException("JDBC operation contains duplicate OUT parameter: " + name);
            }
            outputValues.put(name, value);
        }

        void addCursor(String name, RowSet rows) {
            Objects.requireNonNull(name, "OUT cursor name must not be null");
            RowSet previous = outputCursors.putIfAbsent(name,
                                                         Objects.requireNonNull(rows, "OUT cursor rows must not be null"));
            if (previous != null) {
                throw new DataException("JDBC operation contains duplicate OUT cursor: " + name);
            }
        }

        void addBatch(JdbcBatchResult batch) {
            if (this.batch != null) {
                throw new DataException("JDBC operation contains multiple batch results");
            }
            this.batch = Objects.requireNonNull(batch, "Batch result must not be null");
        }

        JdbcOperationResult build(List<JdbcWarningInfo> warnings, Optional<JdbcFailure> failure) {
            return new JdbcOperationResult(ref,
                                           kind,
                                           new JdbcResultSequence(directResults),
                                           Optional.ofNullable(generatedKeys),
                                           new JdbcCallableOutputs(outputValues, outputCursors),
                                           Optional.ofNullable(batch),
                                           warnings,
                                           failure);
        }
    }
}
