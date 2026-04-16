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
package io.helidon.data.plan.jdbc;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import io.helidon.data.jdbc.GeneratedKeysBehavior;
import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;
import io.helidon.data.jdbc.JdbcResults;
import io.helidon.data.jdbc.ResultSetConcurrency;
import io.helidon.data.jdbc.ResultSetFetchDirection;
import io.helidon.data.jdbc.ResultSetHoldability;
import io.helidon.data.jdbc.ResultSetType;
import io.helidon.data.jdbc.ResultsAdvancementBehavior;
import io.helidon.data.jdbc.TransactionIsolation;
import io.helidon.data.jdbc.function.JdbcConsumer;
import io.helidon.data.jdbc.function.JdbcRunnable;
import io.helidon.data.jdbc.function.JdbcSupplier;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

final class JdbcPlanImpl implements JdbcPlan {

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final JdbcPlanConfig prototype;

    private final String jdbcStatementText;

    private final ExecutionState executionState;

    private final ConnectionState connectionState;

    private final StatementState statementState;

    private final ResultsAdvancementBehavior resultsAdvancementBehavior;

    JdbcPlanImpl(JdbcPlanConfig prototype) {
        super();
        this.jdbcStatementText = prototype.statement();
        this.connectionState = prototype.connectionState().map(ConnectionState::new).orElse(ConnectionState.EMPTY);
        this.statementState = prototype.statementState().map(StatementState::new).orElse(StatementState.EMPTY);
        this.executionState = prototype.executionState().map(ExecutionState::new).orElse(ExecutionState.EMPTY);
        this.resultsAdvancementBehavior = prototype.resultsAdvancementBehavior();
        this.prototype = prototype;
    }

    @Override // JdbcPlan
    public JdbcResults execute(JdbcSupplier<? extends Connection> cs,
                               JdbcConsumer<? super JdbcPreparedStatementBindingView> argsBinder) throws SQLException {
        requireNonNull(argsBinder, "argsBinder");
        Connection c = cs.get();
        PreparedStatement ps = null;
        try {
            ConnectionState initialConnectionState = new ConnectionState(c);
            this.connectionState.install(c);
            PreparedStatement s = this.executionState.prepareStatement(c, this.jdbcStatementText);
            ps = s;
            final StatementState initialStatementState = new StatementState(s);
            this.statementState.install(s);
            argsBinder.accept(bindingView(s));
            int[] outParameterIndices = this.executionState.outParameterIndices();
            JdbcResults jr;
            if (s instanceof CallableStatement callableStatement) {
                jr = JdbcResults.of(callableStatement, this.resultsAdvancementBehavior.value(), outParameterIndices);
            } else {
                jr = JdbcResults.of(s, this.resultsAdvancementBehavior.value());
            }
            return jr
                .onClose((JdbcRunnable) () -> this.restoreStatementStateAndClose(s, initialStatementState))
                .onClose((JdbcRunnable) () -> this.restoreConnectionStateAndClose(c, initialConnectionState));
        } catch (RuntimeException | SQLException e) {
            if (ps != null) {
                try {
                    ps.close();
                } catch (RuntimeException | SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            try {
                c.close();
            } catch (RuntimeException | SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override // JdbcPlan (RuntimeType.Api)
    public JdbcPlanConfig prototype() {
        return this.prototype;
    }

    private void restoreConnectionStateAndClose(Connection c, ConnectionState initial) throws SQLException {
        // We deliberately do not include networkTimeout because it cannot be portably reset. Same with sharding
        // information.
        Exception e = null;
        try {
            initial.install(c);
        } catch (RuntimeException | SQLException setterException) {
            e = setterException;
        }
        try {
            c.close();
        } catch (RuntimeException | SQLException closeFailure) {
            if (e == null) {
                e = closeFailure;
            } else {
                e.addSuppressed(closeFailure);
            }
        }
        switch (e) {
        case null -> {}
        case RuntimeException re -> throw re;
        case SQLException se -> throw se;
        default -> throw new AssertionError(e.getMessage(), e);
        }
    }

    private void restoreStatementStateAndClose(Statement s, StatementState initial) throws SQLException {
        Exception e = null;
        try {
            initial.install(s);
        } catch (RuntimeException | SQLException setterException) {
            e = setterException;
        }
        try {
            s.close();
        } catch (RuntimeException | SQLException closeFailure) {
            if (e == null) {
                e = closeFailure;
            } else {
                e.addSuppressed(closeFailure);
            }
        }
        switch (e) {
        case null -> {}
        case RuntimeException re -> throw re;
        case SQLException se -> throw se;
        default -> throw new AssertionError(e.getMessage(), e);
        }
    }


    /*
     * Static methods.
     */


    static <T> void doNothing(T ignored) {
    }

    private static JdbcPreparedStatementBindingView bindingView(PreparedStatement s) {
        return JdbcPreparedStatementBindingView.of(s);
    }

    /*
     * Inner and nested classes.
     */



    // Deliberately omitted, because it is not restorable:
    // - networkTimeout
    // - shardingKey
    // Deliberately omitted, because it is handled by ExecutionState:
    // - holdability
    private record ConnectionState(Catalog catalog,
                                   Properties clientInfo,
                                   boolean readOnly,
                                   Schema schema,
                                   TransactionIsolation transactionIsolation,
                                   Map<String, Class<?>> typeMap) {

        private static final ConnectionState EMPTY = new ConnectionState();

        private ConnectionState() {
            this(null, null, false, null, TransactionIsolation.NONE, null);
        }

        private ConnectionState(ConnectionStateConfig prototype) {
            this(prototype.catalog().map(c -> new Catalog(c.value().orElse(null))).orElse(null),
                 prototype.clientInfo().orElse(null),
                 prototype.readOnly(),
                 prototype.schema().map(s -> new Schema(s.value().orElse(null))).orElse(null),
                 prototype.transactionIsolation().orElse(TransactionIsolation.NONE),
                 prototype.typeMap().orElse(null));
        }

        private ConnectionState(Connection c) throws SQLException {
            this(new Catalog(c.getCatalog()),
                 c.getClientInfo(),
                 c.isReadOnly(),
                 new Schema(c.getSchema()),
                 TransactionIsolation.of(c.getTransactionIsolation()),
                 c.getTypeMap());
        }

        private ConnectionState {
            if (clientInfo != null) {
                clientInfo = copy(clientInfo);
            }
            requireNonNull(transactionIsolation, "transactionIsolation");
            if (typeMap != null) {
                typeMap = Map.copyOf(typeMap);
            }
        }

        private Connection install(Connection c) throws SQLException {
            if (this.catalog != null && !Objects.equals(this.catalog.value(), c.getCatalog())) {
                c.setCatalog(this.catalog.value());
            }
            if (this.clientInfo != null && !equals(this.clientInfo, c.getClientInfo())) {
                // Note: this is a full replacement
                c.setClientInfo(this.clientInfo);
            }
            if (this.readOnly != c.isReadOnly()) {
                c.setReadOnly(this.readOnly);
            }
            if (this.schema != null && !Objects.equals(this.schema.value(), c.getSchema())) {
                // We deliberately treat null as a sentinel value. This prohibits you from, for example, overriding a
                // non-null value with a null value, but the tradeoff seems worth it.
                c.setSchema(this.schema.value());
            }
            if (this.transactionIsolation != TransactionIsolation.NONE
                && this.transactionIsolation != TransactionIsolation.of(c.getTransactionIsolation())) {
                c.setTransactionIsolation(this.transactionIsolation.value());
            }
            if (this.typeMap != null && !this.typeMap.equals(c.getTypeMap())) {
                c.setTypeMap(this.typeMap);
            }
            return c;
        }

        private static Properties copy(Properties p0) {
            Properties p1 = new Properties();
            for (String pn : p0.stringPropertyNames()) {
                p1.setProperty(pn, p0.getProperty(pn));
            }
            return p1;
        }

        private static boolean equals(Properties p0, Properties p1) {
            if (p0 == p1) {
                return true;
            } else if (p0 == null || p1 == null || !Objects.equals(p0.stringPropertyNames(), p1.stringPropertyNames())) {
                return false;
            }
            for (String k : p0.stringPropertyNames()) {
                if (!Objects.equals(p0.getProperty(k), p1.getProperty(k))) {
                    return false;
                }
            }
            return true;
        }

        private record Catalog(String value) {
        }

        private record Schema(String value) {
        }

    }

    // Deliberately omitted, because it is not restorable:
    // - cursorName // (there is no accessor, only a mutator; what happens if statement is pooled?)
    // - escapeProcessing // (there is no accessor)
    private record StatementState(boolean closeOnCompletion,
                                  ResultSetFetchDirection fetchDirection,
                                  int fetchSize,
                                  long maxRows,
                                  int maxFieldSize,
                                  Boolean poolable,
                                  int queryTimeout) {

        private static final StatementState EMPTY = new StatementState();

        private StatementState() {
            this(false, ResultSetFetchDirection.FORWARD, -1, -1L, -1, null, -1);
        }

        private StatementState(Statement s) throws SQLException {
            this(false, // no way to read it
                 ResultSetFetchDirection.of(s.getFetchDirection()),
                 s.getFetchSize(),
                 maxRows(s),
                 s.getMaxFieldSize(),
                 s.isPoolable(),
                 s.getQueryTimeout());
        }

        private StatementState(StatementStateConfig prototype) {
            this(prototype.closeOnCompletion(),
                 prototype.fetchDirection(),
                 prototype.fetchSize(),
                 prototype.maxRows(),
                 prototype.maxFieldSize(),
                 null, // poolable, can't set it without an actual statement
                 prototype.queryTimeout());
        }

        private StatementState {
            if (fetchDirection == null) {
                fetchDirection = ResultSetFetchDirection.FORWARD;
            }
        }

        private <S extends Statement> S install(S s) throws SQLException {
            // closeOnCompletion is not strictly speaking reversible and has undefined semantics for pooled
            // statements. We make a best effort.
            if (this.closeOnCompletion && (this.closeOnCompletion != s.isCloseOnCompletion())) {
                // This is a little shaky since it strictly speaking is not fully reversible.
                s.closeOnCompletion();
            }
            // We ignore escape processing because it has no "getter".
            if (!Objects.equals(this.fetchDirection, ResultSetFetchDirection.of(s.getFetchDirection()))) {
                s.setFetchDirection(this.fetchDirection.value());
            }
            if (this.fetchSize >= 0 && (this.fetchSize != s.getFetchSize())) {
                s.setFetchSize(this.fetchSize);
            }
            if (this.maxRows >= 0L) {
                try {
                    if (this.maxRows != s.getLargeMaxRows()) {
                        s.setLargeMaxRows(this.maxRows);
                    }
                } catch (SQLFeatureNotSupportedException e) {
                    int maxRows = this.maxRows >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) this.maxRows;
                    if (maxRows != s.getMaxRows()) {
                        s.setMaxRows(maxRows);
                    }
                }
            }
            if (this.maxFieldSize >= 0 && (this.maxFieldSize != s.getMaxFieldSize())) {
                s.setMaxFieldSize(this.maxFieldSize);
            }
            if (this.poolable != null && (this.poolable.booleanValue() != s.isPoolable())) {
                s.setPoolable(this.poolable.booleanValue());
            }
            if (this.queryTimeout >= 0 && (this.queryTimeout != s.getQueryTimeout())) {
                s.setQueryTimeout(this.queryTimeout);
            }
            return s;
        }

        private static long maxRows(Statement s) throws SQLException {
            try {
                return s.getLargeMaxRows();
            } catch (SQLFeatureNotSupportedException e) {
                return s.getMaxRows();
            }
        }

    }

    private record ExecutionState(Class<? extends PreparedStatement> type,
                                  GeneratedKeysBehavior generatedKeysBehavior,
                                  int[] columnIndexes,
                                  String[] columnNames,
                                  ResultSetType resultSetType,
                                  ResultSetConcurrency resultSetConcurrency,
                                  ResultSetHoldability resultSetHoldability,
                                  int[] outParameterIndices) {

        private static final ExecutionState EMPTY = new ExecutionState();

        private ExecutionState() {
            this(PreparedStatement.class,
                 GeneratedKeysBehavior.NONE,
                 EMPTY_INT_ARRAY,
                 EMPTY_STRING_ARRAY,
                 ResultSetType.FORWARD_ONLY,
                 ResultSetConcurrency.READ_ONLY,
                 ResultSetHoldability.CLOSE_CURSORS_AT_COMMIT,
                 EMPTY_INT_ARRAY);
        }

        private ExecutionState(ExecutionStateConfig prototype) {
            this(prototype.type(),
                 prototype.generatedKeysBehavior(),
                 prototype.columnIndexes().stream().mapToInt(Integer::intValue).toArray(),
                 prototype.columnNames().toArray(new String[0]),
                 prototype.resultSetType(),
                 prototype.resultSetConcurrency(),
                 prototype.resultSetHoldability(),
                 prototype.outParameterIndices().stream().mapToInt(Integer::intValue).toArray());
        }

        private ExecutionState {
            requireNonNull(type, "type");
            if (generatedKeysBehavior == null) {
                generatedKeysBehavior = GeneratedKeysBehavior.NONE;
            } else if (generatedKeysBehavior != GeneratedKeysBehavior.NONE && CallableStatement.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("CallableStatements and generated keys don't work together");
            }
            if (columnIndexes == null || columnIndexes.length == 0) {
                columnIndexes = EMPTY_INT_ARRAY;
            } else if (CallableStatement.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("CallableStatements and generated keys don't work together");
            } else {
                int[] a = new int[columnIndexes.length];
                System.arraycopy(columnIndexes, 0, a, 0, columnIndexes.length);
                columnIndexes = a;
            }
            if (columnNames == null || columnNames.length == 0) {
                columnNames = EMPTY_STRING_ARRAY;
            } else if (CallableStatement.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("CallableStatements and generated keys don't work together");
            } else if (columnIndexes.length == 0) {
                String[] a = new String[columnNames.length];
                System.arraycopy(columnNames, 0, a, 0, columnNames.length);
                columnNames = a;
            } else {
                throw new IllegalArgumentException("columnNames and columnIndexes cannot coexist");
            }
            if (resultSetType == null) {
                resultSetType = ResultSetType.FORWARD_ONLY;
            }
            if (resultSetConcurrency == null) {
                resultSetConcurrency = ResultSetConcurrency.READ_ONLY;
            }
            if (resultSetHoldability == null) {
                resultSetHoldability = ResultSetHoldability.CLOSE_CURSORS_AT_COMMIT;
            }
            if (outParameterIndices == null || outParameterIndices.length == 0) {
                outParameterIndices = EMPTY_INT_ARRAY;
            } else if (!CallableStatement.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("outParameterIndices: " + asList(outParameterIndices));
            } else {
                int[] a = new int[outParameterIndices.length];
                System.arraycopy(outParameterIndices, 0, a, 0, outParameterIndices.length);
                outParameterIndices = a;
            }
        }

        private PreparedStatement prepareStatement(Connection c, String jdbcStatementText) throws SQLException {
            PreparedStatement ps;
            if (this.columnIndexes.length > 0) {
                ps = c.prepareStatement(jdbcStatementText,
                                        this.columnIndexes);
            } else if (this.columnNames.length > 0) {
                ps = c.prepareStatement(jdbcStatementText,
                                        this.columnNames);
            } else if (CallableStatement.class.isAssignableFrom(this.type)) {
                ps = c.prepareCall(jdbcStatementText,
                                   this.resultSetType.value(),
                                   this.resultSetConcurrency.value(),
                                   this.resultSetHoldability(c));
            } else if (this.generatedKeysBehavior == GeneratedKeysBehavior.RETURN) {
                ps = c.prepareStatement(jdbcStatementText,
                                        GeneratedKeysBehavior.RETURN.value());
            } else {
                ps = c.prepareStatement(jdbcStatementText,
                                        this.resultSetType.value(),
                                        this.resultSetConcurrency.value(),
                                        this.resultSetHoldability(c));
            }
            return ps;
        }

        private int resultSetHoldability(Connection c) throws SQLException {
            return switch (this.resultSetHoldability) {
            case CLOSE_CURSORS_AT_COMMIT, HOLD_CURSORS_OVER_COMMIT -> this.resultSetHoldability.value();
            default -> c.getHoldability();
            };
        }

    }

}
