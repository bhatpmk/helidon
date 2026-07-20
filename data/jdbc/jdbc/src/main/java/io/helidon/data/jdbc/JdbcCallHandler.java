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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.helidon.data.DataException;

/**
 * Owns stored-procedure and function semantics while {@link JdbcRunner} owns the JDBC lifecycle.
 * <p>
 * The handler registers outputs, executes once, enforces direct-result/cursor/scalar ordering, dispatches the
 * application callback, and invalidates every scoped view. It never acquires or releases a connection lease.
 */
final class JdbcCallHandler {
    private final JdbcQueryHandler queryHandler;

    /**
     * Creates a call handler that reuses the query row-consumption engine.
     *
     * @param queryHandler shared row-consumption handler
     */
    JdbcCallHandler(JdbcQueryHandler queryHandler) {
        this.queryHandler = queryHandler;
    }

    /**
     * Registers every OUT, INOUT, cursor, and function-return position in JDBC order.
     *
     * @param statement prepared callable statement
     * @param call immutable callable layout
     * @throws SQLException if registration fails
     */
    void registerOutputs(CallableStatement statement, JdbcCall call) throws SQLException {
        for (JdbcCall.Parameter parameter : call.parameters()) {
            if (!parameter.output()) {
                continue;
            }
            if (parameter.typeName().isEmpty()) {
                statement.registerOutParameter(parameter.index(), parameter.jdbcType());
            } else {
                statement.registerOutParameter(parameter.index(), parameter.jdbcType(), parameter.typeName());
            }
        }
    }

    /**
     * Invokes an input-only procedure and rejects every reported result channel.
     *
     * @param scope runner-owned execution scope
     * @throws SQLException if execution or result advancement fails
     */
    void invoke(JdbcRunner.ExecutionScope scope) throws SQLException {
        scope.require(JdbcPreparationPlan.ResultKind.CALL);
        if (scope.operation().preparationPlan().call().parameters().stream().anyMatch(JdbcCall.Parameter::output)) {
            throw new IllegalStateException("A JDBC call with outputs requires callForOutputs or a callback request");
        }
        DirectResults results = new DirectResults(scope, scope.callableStatement().execute());
        if (results.discardInternal()) {
            throw new DataException("Input-only JDBC call returned an unexpected result channel");
        }
    }

    /**
     * Invokes a callable operation with a void callback.
     *
     * @param scope runner-owned execution scope
     * @param request callback request
     * @throws SQLException if JDBC processing fails
     */
    void invoke(JdbcRunner.ExecutionScope scope, JdbcResultRequest.Call request) throws SQLException {
        invoke(scope, call -> {
            request.accept(call);
            return Boolean.TRUE;
        });
    }

    /**
     * Invokes a callable operation and snapshots every declared scalar output.
     *
     * @param scope runner-owned execution scope
     * @return detached scalar output values
     * @throws SQLException if JDBC processing fails
     */
    JdbcClient.CallOutputValues invokeForOutputs(JdbcRunner.ExecutionScope scope) throws SQLException {
        return invoke(scope, call -> {
            if (call.results.discardInternal()) {
                throw new DataException("Detached JDBC call returned an unexpected direct result channel; "
                                                + "use a callback-scoped call to consume it");
            }
            return call.outputs.detach();
        });
    }

    /**
     * Invokes a callable operation and returns a detached callback result.
     *
     * @param scope runner-owned execution scope
     * @param request callback request
     * @param <R> detached result type
     * @return callback result
     * @throws SQLException if JDBC processing fails
     */
    <R> R invoke(JdbcRunner.ExecutionScope scope, JdbcResultRequest.CallWith<R> request) throws SQLException {
        return invoke(scope, request::apply);
    }

    private <R> R invoke(JdbcRunner.ExecutionScope scope, Callback<R> callback) throws SQLException {
        scope.require(JdbcPreparationPlan.ResultKind.CALL);
        CallableStatement statement = scope.callableStatement();
        CallScopeImpl call = new CallScopeImpl(scope,
                                               queryHandler,
                                               scope.operation().preparationPlan().call(),
                                               statement.execute());
        R result = null;
        Throwable failure = null;
        try {
            result = callback.apply(call);
            if (result == null) {
                throw new DataException("JDBC call callback returned null");
            }
            call.verifyComplete();
        } catch (Throwable t) {
            failure = t;
        }

        Throwable cleanupFailure = call.cleanup();
        call.invalidate();
        if (cleanupFailure != null) {
            if (failure == null) {
                failure = cleanupFailure;
            } else if (failure instanceof SQLException) {
                failure.addSuppressed(cleanupFailure);
            } else {
                failure.addSuppressed(translate(scope, cleanupFailure));
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
        return result;
    }

    private static Throwable translate(JdbcRunner.ExecutionScope scope, Throwable failure) {
        if (failure instanceof SQLException sqlException) {
            return JdbcExceptionTranslator.translate(scope.operation(), sqlException);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) throws SQLException {
        if (failure instanceof SQLException sqlException) {
            throw sqlException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new DataException("JDBC call callback failed", failure);
    }

    @FunctionalInterface
    private interface Callback<R> {
        R apply(CallScopeImpl call) throws SQLException;
    }

    private static final class CallScopeImpl implements JdbcClient.CallScope {
        private final JdbcRunner.ExecutionScope scope;
        private final JdbcQueryHandler queryHandler;
        private final DirectResults results;
        private final CallOutputsImpl outputs;
        private boolean active = true;

        private CallScopeImpl(JdbcRunner.ExecutionScope scope,
                              JdbcQueryHandler queryHandler,
                              JdbcCall call,
                              boolean initialResultSet) {
            this.scope = scope;
            this.queryHandler = queryHandler;
            this.results = new DirectResults(scope, initialResultSet, this);
            this.outputs = new CallOutputsImpl(this, scope, call);
        }

        @Override
        public JdbcClient.CallResults results() {
            ensureActive();
            return results;
        }

        @Override
        public JdbcClient.CallOutputs outputs() {
            ensureActive();
            return outputs;
        }

        private void verifyComplete() {
            ensureActive();
            if (!results.complete()) {
                throw new IllegalStateException("JDBC call direct results must be consumed or discarded");
            }
            outputs.verifyComplete();
        }

        private Throwable cleanup() {
            Throwable failure = results.cleanup();
            Throwable outputFailure = outputs.cleanup();
            if (failure == null) {
                return outputFailure;
            }
            if (outputFailure != null) {
                failure.addSuppressed(outputFailure);
            }
            return failure;
        }

        private void invalidate() {
            active = false;
        }

        private void ensureActive() {
            if (!active) {
                throw new IllegalStateException("JDBC call results are valid only during the call callback");
            }
        }

        private DataException translate(SQLException failure) {
            return JdbcExceptionTranslator.translate(scope.operation(), failure);
        }
    }

    private static final class DirectResults implements JdbcClient.CallResults {
        private final JdbcRunner.ExecutionScope scope;
        private final CallScopeImpl owner;
        private boolean currentIsResultSet;
        private boolean started;
        private boolean complete;

        private DirectResults(JdbcRunner.ExecutionScope scope, boolean initialResultSet) {
            this(scope, initialResultSet, null);
        }

        private DirectResults(JdbcRunner.ExecutionScope scope,
                              boolean initialResultSet,
                              CallScopeImpl owner) {
            this.scope = scope;
            this.owner = owner;
            this.currentIsResultSet = initialResultSet;
        }

        @Override
        public void visit(JdbcClient.CallResultVisitor visitor) {
            ensureAvailable();
            Objects.requireNonNull(visitor, "JDBC call result visitor must not be null");
            try {
                consume(visitor, false);
            } catch (SQLException e) {
                throw translate(e);
            }
        }

        @Override
        public void discard() {
            ensureAvailable();
            try {
                consume(null, true);
            } catch (SQLException e) {
                throw translate(e);
            }
        }

        private boolean discardInternal() throws SQLException {
            ensureAvailable();
            return consume(null, true);
        }

        private boolean consume(JdbcClient.CallResultVisitor visitor, boolean discard) throws SQLException {
            started = true;
            boolean encountered = false;
            int itemIndex = 0;
            int resultSetIndex = 0;
            while (true) {
                if (currentIsResultSet) {
                    encountered = true;
                    ResultSet resultSet = scope.callableStatement().getResultSet();
                    if (resultSet == null) {
                        throw new DataException("JDBC call reported a direct result set but returned null");
                    }
                    scope.resultSet(resultSet);
                    if (discard) {
                        closeCurrent(resultSet);
                    } else {
                        CallRowsImpl rows = new CallRowsImpl(requiredOwner(), scope, resultSet, false);
                        visitor.rows(resultSetIndex, rows);
                        rows.requireConsumed("Direct result set " + resultSetIndex);
                        closeCurrent(resultSet);
                    }
                    resultSetIndex++;
                } else {
                    long count = scope.largeUpdateCount();
                    if (count == -1) {
                        complete = true;
                        return encountered;
                    }
                    encountered = true;
                    if (!discard) {
                        visitor.updateCount(itemIndex, count);
                    }
                }
                itemIndex++;
                currentIsResultSet = scope.callableStatement().getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
        }

        private Throwable cleanup() {
            if (complete) {
                return null;
            }
            try {
                if (!started) {
                    started = true;
                }
                consume(null, true);
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        private void closeCurrent(ResultSet resultSet) throws SQLException {
            scope.captureResultWarnings(resultSet);
            resultSet.close();
            scope.clearResultSet(resultSet);
        }

        private void ensureAvailable() {
            if (owner != null) {
                owner.ensureActive();
            }
            if (started) {
                throw new IllegalStateException("JDBC call direct results permit exactly one terminal operation");
            }
        }

        private CallScopeImpl requiredOwner() {
            if (owner == null) {
                throw new IllegalStateException("Input-only JDBC call cannot expose row results");
            }
            return owner;
        }

        private RuntimeException translate(SQLException failure) {
            return owner == null
                    ? JdbcExceptionTranslator.translate(scope.operation(), failure)
                    : owner.translate(failure);
        }

        private boolean complete() {
            return complete;
        }
    }

    private static final class CallOutputsImpl implements JdbcClient.CallOutputs {
        private final CallScopeImpl owner;
        private final JdbcRunner.ExecutionScope scope;
        private final Map<Integer, JdbcCall.Parameter> byIndex = new HashMap<>();
        private final Map<String, JdbcCall.Parameter> byName = new HashMap<>();
        private final List<JdbcCall.Parameter> scalarParameters = new ArrayList<>();
        private final Set<Integer> cursorPositions = new HashSet<>();
        private final Set<Integer> consumedCursors = new HashSet<>();
        private final Map<Integer, Optional<Object>> scalarValues = new HashMap<>();
        private CallRowsImpl openCursor;
        private int openCursorPosition = -1;
        private boolean scalarStarted;

        private CallOutputsImpl(CallScopeImpl owner,
                                JdbcRunner.ExecutionScope scope,
                                JdbcCall call) {
            this.owner = owner;
            this.scope = scope;
            for (JdbcCall.Parameter parameter : call.parameters()) {
                if (!parameter.output()) {
                    continue;
                }
                byIndex.put(parameter.index(), parameter);
                byName.put(parameter.name(), parameter);
                if (parameter.cursor()) {
                    cursorPositions.add(parameter.index());
                } else {
                    scalarParameters.add(parameter);
                }
            }
        }

        @Override
        public <T> T required(String name, Class<T> type) {
            JdbcCall.Parameter parameter = output(name);
            return required(parameter, type);
        }

        @Override
        public <T> T required(int index, Class<T> type) {
            return required(output(index), type);
        }

        @Override
        public <T> Optional<T> optional(String name, Class<T> type) {
            return optional(output(name), type);
        }

        @Override
        public <T> Optional<T> optional(int index, Class<T> type) {
            return optional(output(index), type);
        }

        @Override
        public JdbcClient.CallRows cursor(String name) {
            return cursor(output(name));
        }

        @Override
        public JdbcClient.CallRows cursor(int index) {
            return cursor(output(index));
        }

        @Override
        public void discardCursor(String name) {
            discardCursor(output(name));
        }

        @Override
        public void discardCursor(int index) {
            discardCursor(output(index));
        }

        private <T> T required(JdbcCall.Parameter parameter, Class<T> type) {
            return optional(parameter, type)
                    .orElseThrow(() -> new DataException("Required JDBC call output '" + parameter.name()
                                                                + "' contains SQL NULL"));
        }

        private <T> Optional<T> optional(JdbcCall.Parameter parameter, Class<T> requestedType) {
            ensureScalarAccess(parameter, requestedType);
            Optional<Object> value = scalarValues.computeIfAbsent(parameter.index(), ignored -> read(parameter));
            @SuppressWarnings("unchecked")
            Optional<T> result = (Optional<T>) (Optional<?>) value;
            return result;
        }

        private Optional<Object> read(JdbcCall.Parameter parameter) {
            try {
                Class<?> target = JdbcRow.normalizedScalar(parameter.javaType());
                Object value;
                if (target == BigInteger.class) {
                    value = toBigInteger(scope.callableStatement().getObject(parameter.index()), parameter);
                } else {
                    value = scope.callableStatement().getObject(parameter.index(), target);
                }
                return Optional.ofNullable(detachedValue(value));
            } catch (SQLException e) {
                throw owner.translate(e);
            }
        }

        private JdbcClient.CallOutputValues detach() {
            owner.ensureActive();
            ensureDirectResultsComplete();
            if (!cursorPositions.isEmpty()) {
                throw new IllegalStateException("A detached JDBC call cannot contain cursor outputs");
            }
            Map<Integer, DetachedOutput> valuesByIndex = new LinkedHashMap<>();
            Map<String, DetachedOutput> valuesByName = new LinkedHashMap<>();
            for (JdbcCall.Parameter parameter : scalarParameters) {
                Optional<Object> value = scalarValues.computeIfAbsent(parameter.index(), ignored -> read(parameter));
                DetachedOutput output = new DetachedOutput(parameter, value);
                valuesByIndex.put(parameter.index(), output);
                valuesByName.put(parameter.name(), output);
            }
            return new DetachedCallOutputValues(valuesByIndex, valuesByName);
        }

        private JdbcClient.CallRows cursor(JdbcCall.Parameter parameter) {
            ensureCursorAccess(parameter);
            try {
                Object value = scope.callableStatement().getObject(parameter.index());
                if (value == null) {
                    throw new DataException("JDBC cursor output '" + parameter.name() + "' contains SQL NULL");
                }
                if (!(value instanceof ResultSet resultSet)) {
                    throw new DataException("JDBC cursor output '" + parameter.name() + "' returned "
                                                    + value.getClass().getTypeName() + " instead of ResultSet");
                }
                return openCursor(parameter, resultSet);
            } catch (SQLException e) {
                throw owner.translate(e);
            }
        }

        private void discardCursor(JdbcCall.Parameter parameter) {
            ensureCursorAccess(parameter);
            try {
                Object value = scope.callableStatement().getObject(parameter.index());
                if (value instanceof ResultSet resultSet) {
                    openCursor(parameter, resultSet).discard();
                } else if (value != null) {
                    throw new DataException("JDBC cursor output '" + parameter.name() + "' returned "
                                                    + value.getClass().getTypeName() + " instead of ResultSet");
                } else {
                    consumedCursors.add(parameter.index());
                }
            } catch (SQLException e) {
                throw owner.translate(e);
            }
        }

        private CallRowsImpl openCursor(JdbcCall.Parameter parameter, ResultSet resultSet) {
            scope.resultSet(resultSet);
            openCursor = new CallRowsImpl(owner, scope, resultSet, true);
            openCursorPosition = parameter.index();
            openCursor.onClose(() -> {
                consumedCursors.add(parameter.index());
                openCursor = null;
                openCursorPosition = -1;
            });
            return openCursor;
        }

        private void ensureScalarAccess(JdbcCall.Parameter parameter, Class<?> requestedType) {
            owner.ensureActive();
            ensureDirectResultsComplete();
            if (parameter.cursor()) {
                throw new IllegalArgumentException("JDBC call output '" + parameter.name() + "' is a cursor");
            }
            if (openCursor != null || !consumedCursors.containsAll(cursorPositions)) {
                throw new IllegalStateException("JDBC call cursor outputs must be consumed or discarded before scalar "
                                                        + "outputs are read");
            }
            Class<?> requested = JdbcRow.normalizedScalar(requestedType);
            Class<?> declared = JdbcRow.normalizedScalar(parameter.javaType());
            if (requested != declared) {
                throw new IllegalArgumentException("JDBC call output '" + parameter.name() + "' was declared as "
                                                           + parameter.javaType().getTypeName() + " but requested as "
                                                           + requestedType.getTypeName());
            }
            scalarStarted = true;
        }

        private void ensureCursorAccess(JdbcCall.Parameter parameter) {
            owner.ensureActive();
            ensureDirectResultsComplete();
            if (!parameter.cursor()) {
                throw new IllegalArgumentException("JDBC call output '" + parameter.name() + "' is not a cursor");
            }
            if (scalarStarted) {
                throw new IllegalStateException("JDBC call cursors cannot be accessed after scalar outputs");
            }
            if (openCursor != null) {
                throw new IllegalStateException("A JDBC call permits only one open cursor output at a time");
            }
            if (consumedCursors.contains(parameter.index())) {
                throw new IllegalStateException("JDBC cursor output '" + parameter.name() + "' was already consumed");
            }
        }

        private void ensureDirectResultsComplete() {
            if (!owner.results.complete()) {
                throw new IllegalStateException("JDBC call direct results must be consumed or discarded before outputs");
            }
        }

        private JdbcCall.Parameter output(String name) {
            owner.ensureActive();
            Objects.requireNonNull(name, "JDBC call output name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("JDBC call output name must not be blank");
            }
            JdbcCall.Parameter parameter = byName.get(name);
            if (parameter == null) {
                throw new IllegalArgumentException("Unknown JDBC call output name: " + name);
            }
            return parameter;
        }

        private JdbcCall.Parameter output(int index) {
            owner.ensureActive();
            JdbcCall.Parameter parameter = byIndex.get(index);
            if (parameter == null) {
                throw new IllegalArgumentException("Unknown JDBC call output position: " + index);
            }
            return parameter;
        }

        private void verifyComplete() {
            if (openCursor != null) {
                throw new IllegalStateException("An opened JDBC cursor output was not consumed or discarded");
            }
            if (!consumedCursors.containsAll(cursorPositions)) {
                int missing = cursorPositions.stream()
                        .filter(position -> !consumedCursors.contains(position))
                        .findFirst()
                        .orElseThrow();
                throw new IllegalStateException("JDBC cursor output '" + byIndex.get(missing).name()
                                                        + "' must be consumed or discarded");
            }
        }

        private Throwable cleanup() {
            Throwable failure = null;
            if (openCursor != null) {
                try {
                    openCursor.closeForCleanup();
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (owner.results.complete()) {
                for (Integer position : cursorPositions) {
                    if (consumedCursors.contains(position) || position == openCursorPosition) {
                        continue;
                    }
                    try {
                        discardCursor(byIndex.get(position));
                    } catch (Throwable t) {
                        if (failure == null) {
                            failure = t;
                        } else {
                            failure.addSuppressed(t);
                        }
                    }
                }
            }
            return failure;
        }

        private static BigInteger toBigInteger(Object value, JdbcCall.Parameter parameter) {
            if (value == null || value instanceof BigInteger) {
                return (BigInteger) value;
            }
            if (value instanceof BigDecimal decimal) {
                try {
                    return decimal.toBigIntegerExact();
                } catch (ArithmeticException e) {
                    throw conversionFailure(parameter, e);
                }
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return BigInteger.valueOf(((Number) value).longValue());
            }
            if (value instanceof Number number) {
                try {
                    return new BigDecimal(number.toString()).toBigIntegerExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw conversionFailure(parameter, e);
                }
            }
            throw conversionFailure(parameter, null);
        }

        private static DataException conversionFailure(JdbcCall.Parameter parameter, Throwable cause) {
            String message = "JDBC call output '" + parameter.name() + "' cannot be converted to BigInteger";
            return cause == null ? new DataException(message) : new DataException(message, cause);
        }

        private static Object detachedValue(Object value) {
            if (value instanceof byte[] bytes) {
                return bytes.clone();
            }
            if (value instanceof Timestamp timestamp) {
                Timestamp copy = new Timestamp(timestamp.getTime());
                copy.setNanos(timestamp.getNanos());
                return copy;
            }
            if (value instanceof Time time) {
                return new Time(time.getTime());
            }
            if (value instanceof Date date) {
                return new Date(date.getTime());
            }
            return value;
        }
    }

    private record DetachedOutput(JdbcCall.Parameter parameter, Optional<Object> value) {
    }

    private static final class DetachedCallOutputValues implements JdbcClient.CallOutputValues {
        private final Map<Integer, DetachedOutput> byIndex;
        private final Map<String, DetachedOutput> byName;

        private DetachedCallOutputValues(Map<Integer, DetachedOutput> byIndex,
                                         Map<String, DetachedOutput> byName) {
            this.byIndex = Map.copyOf(byIndex);
            this.byName = Map.copyOf(byName);
        }

        @Override
        public <T> T required(String name, Class<T> type) {
            DetachedOutput output = output(name);
            return optional(output, type)
                    .orElseThrow(() -> new DataException("Required JDBC call output '" + output.parameter().name()
                                                                + "' contains SQL NULL"));
        }

        @Override
        public <T> T required(int index, Class<T> type) {
            DetachedOutput output = output(index);
            return optional(output, type)
                    .orElseThrow(() -> new DataException("Required JDBC call output '" + output.parameter().name()
                                                                + "' contains SQL NULL"));
        }

        @Override
        public <T> Optional<T> optional(String name, Class<T> type) {
            return optional(output(name), type);
        }

        @Override
        public <T> Optional<T> optional(int index, Class<T> type) {
            return optional(output(index), type);
        }

        private <T> Optional<T> optional(DetachedOutput output, Class<T> requestedType) {
            Objects.requireNonNull(requestedType, "JDBC call output type must not be null");
            Class<?> requested = JdbcRow.normalizedScalar(requestedType);
            Class<?> declared = JdbcRow.normalizedScalar(output.parameter().javaType());
            if (requested != declared) {
                throw new IllegalArgumentException("JDBC call output '" + output.parameter().name()
                                                           + "' was declared as "
                                                           + output.parameter().javaType().getTypeName()
                                                           + " but requested as " + requestedType.getTypeName());
            }
            if (output.value().isEmpty()) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            T result = (T) CallOutputsImpl.detachedValue(output.value().orElseThrow());
            return Optional.of(result);
        }

        private DetachedOutput output(String name) {
            Objects.requireNonNull(name, "JDBC call output name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("JDBC call output name must not be blank");
            }
            DetachedOutput output = byName.get(name);
            if (output == null) {
                throw new IllegalArgumentException("Unknown JDBC call output name: " + name);
            }
            return output;
        }

        private DetachedOutput output(int index) {
            DetachedOutput output = byIndex.get(index);
            if (output == null) {
                throw new IllegalArgumentException("Unknown JDBC call output position: " + index);
            }
            return output;
        }
    }

    private static final class CallRowsImpl implements JdbcClient.CallRows {
        private final CallScopeImpl owner;
        private final JdbcRunner.ExecutionScope scope;
        private final ResultSet resultSet;
        private final boolean closeAfterTerminal;
        private Runnable onClose = () -> { };
        private boolean selected;
        private boolean consumed;
        private boolean closed;

        private CallRowsImpl(CallScopeImpl owner,
                             JdbcRunner.ExecutionScope scope,
                             ResultSet resultSet,
                             boolean closeAfterTerminal) {
            this.owner = owner;
            this.scope = scope;
            this.resultSet = resultSet;
            this.closeAfterTerminal = closeAfterTerminal;
        }

        @Override
        public <T> JdbcClient.ScopedRows<T> map(JdbcClient.RowMapper<T> mapper) {
            Objects.requireNonNull(mapper, "JDBC call row mapper must not be null");
            select();
            return new ScopedRowsImpl<>(this, mapper);
        }

        @Override
        public <T> JdbcClient.ScopedRows<T> map(Class<T> scalarType) {
            Objects.requireNonNull(scalarType, "JDBC call scalar type must not be null");
            if (!JdbcRow.supportedScalar(scalarType)) {
                throw new IllegalArgumentException("Unsupported JDBC scalar type: " + scalarType.getTypeName());
            }
            return map(row -> row.required(1, scalarType));
        }

        @Override
        public void discard() {
            select();
            Throwable failure = complete(null);
            if (failure != null) {
                throw runtimeFailure(failure);
            }
        }

        private void select() {
            owner.ensureActive();
            if (selected) {
                throw new IllegalStateException("JDBC call rows permit exactly one mapping or discard operation");
            }
            selected = true;
        }

        private <R> R execute(RowTerminal<R> terminal) {
            owner.ensureActive();
            if (consumed) {
                throw new IllegalStateException("JDBC call mapped rows permit exactly one terminal operation");
            }
            consumed = true;
            R result = null;
            Throwable failure = null;
            try {
                result = terminal.execute();
            } catch (Throwable t) {
                failure = t;
            }
            failure = complete(failure);
            if (failure != null) {
                throw runtimeFailure(failure);
            }
            return result;
        }

        private Throwable complete(Throwable failure) {
            consumed = true;
            if (!closeAfterTerminal || closed) {
                return failure;
            }
            try {
                scope.captureResultWarnings(resultSet);
                resultSet.close();
                scope.clearResultSet(resultSet);
                closed = true;
                onClose.run();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    return closeFailure;
                }
                failure.addSuppressed(closeFailure);
            }
            return failure;
        }

        private void requireConsumed(String description) {
            if (!consumed) {
                throw new IllegalStateException(description + " must be consumed or discarded before advancing");
            }
        }

        private void onClose(Runnable action) {
            onClose = action;
        }

        private void closeForCleanup() throws SQLException {
            if (closed) {
                return;
            }
            scope.captureResultWarnings(resultSet);
            resultSet.close();
            scope.clearResultSet(resultSet);
            closed = true;
            consumed = true;
            onClose.run();
        }

        private RuntimeException runtimeFailure(Throwable failure) {
            if (failure instanceof SQLException sqlException) {
                return owner.translate(sqlException);
            }
            if (failure instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new DataException("JDBC call row processing failed", failure);
        }
    }

    private static final class ScopedRowsImpl<T> implements JdbcClient.ScopedRows<T> {
        private final CallRowsImpl rows;
        private final JdbcClient.RowMapper<T> mapper;

        private ScopedRowsImpl(CallRowsImpl rows, JdbcClient.RowMapper<T> mapper) {
            this.rows = rows;
            this.mapper = mapper;
        }

        @Override
        public T one() {
            return rows.execute(() -> rows.owner.queryHandler.oneScoped(rows.scope, mapper, rows.resultSet));
        }

        @Override
        public Optional<T> optional() {
            return rows.execute(() -> rows.owner.queryHandler.optionalScoped(rows.scope, mapper, rows.resultSet));
        }

        @Override
        public List<T> list() {
            return rows.execute(() -> rows.owner.queryHandler.listScoped(rows.scope, mapper, rows.resultSet));
        }

        @Override
        public <R> R reduce(JdbcClient.RowReducer<R> reducer) {
            Objects.requireNonNull(reducer, "JDBC call row reducer must not be null");
            return rows.execute(() -> rows.owner.queryHandler.reduceScoped(rows.scope, reducer, rows.resultSet));
        }

        @Override
        public void visitAll(JdbcResultRequest.VisitAll<T> request) {
            Objects.requireNonNull(request, "JDBC call visit-all request must not be null");
            requireNoNestedOptions(request.options());
            rows.execute(() -> {
                rows.owner.queryHandler.visitAllScoped(rows.scope, mapper, request, rows.resultSet);
                return Boolean.TRUE;
            });
        }

        @Override
        public boolean visitWhile(JdbcResultRequest.VisitWhile<T> request) {
            Objects.requireNonNull(request, "JDBC call visit-while request must not be null");
            requireNoNestedOptions(request.options());
            return rows.execute(() -> rows.owner.queryHandler.visitWhileScoped(rows.scope,
                                                                                mapper,
                                                                                request,
                                                                                rows.resultSet));
        }

        @Override
        public void discard() {
            rows.execute(() -> Boolean.TRUE);
        }

        private static void requireNoNestedOptions(JdbcStatementOptions options) {
            if (!options.empty()) {
                throw new IllegalArgumentException("JDBC call statement options must be applied to the outer call "
                                                           + "request");
            }
        }
    }

    @FunctionalInterface
    private interface RowTerminal<R> {
        R execute() throws SQLException;
    }
}
