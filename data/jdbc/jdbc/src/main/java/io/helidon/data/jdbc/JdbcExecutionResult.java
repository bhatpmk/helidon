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

import io.helidon.data.DataException;

/**
 * Detached result of one logical JDBC execution.
 * <p>
 * An execution may contain one operation or an ordered plan of operations. Each operation keeps its direct JDBC
 * result sequence and its separately scoped attachments. The object contains detached values only; JDBC connections,
 * statements, and result sets are closed before this result is returned to a reducer.
 *
 * The class is deliberately written as an ordinary immutable class. The explicit fields and accessors make the
 * execution boundary easier to inspect while the result model is still evolving.
 */
final class JdbcExecutionResult {
    private final List<JdbcOperationResult> operations;

    JdbcExecutionResult(List<JdbcOperationResult> operations) {
        Objects.requireNonNull(operations, "Operations must not be null");
        this.operations = List.copyOf(operations);
    }

    /**
     * Return operation results in execution order.
     *
     * @return immutable operation results
     */
    List<JdbcOperationResult> operations() {
        return operations;
    }

    /**
     * Return the only operation when a reducer requires a single-operation execution.
     *
     * @return the only operation
     * @throws DataException if the execution contains zero or multiple operations
     */
    JdbcOperationResult onlyOperation() {
        if (operations.size() != 1) {
            throw new DataException("Expected exactly one JDBC operation, but found " + operations.size());
        }
        return operations.getFirst();
    }

    /**
     * Return an exhaustive, pretty-formatted dump for temporary architecture diagnostics.
     * <p>
     * The dump contains all captured metadata and application data, including row values, generated keys, callable
     * values, warning messages, and failure messages. Production code must not call this method as part of normal
     * execution; {@link JdbcRunner} invokes it only when temporary JDBC tracing is enabled. This implementation must
     * be removed before the provider is checked in.
     *
     * @return exhaustive multiline dump
     */
    @Override
    public String toString() {
        return JdbcExecutionResultFormatter.format(this);
    }
}
