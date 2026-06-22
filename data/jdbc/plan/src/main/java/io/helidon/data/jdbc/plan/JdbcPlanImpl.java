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
package io.helidon.data.jdbc.plan;

import java.io.PrintWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.helidon.data.jdbc.GeneratedKeysBehavior;
import io.helidon.data.jdbc.JdbcPreparedStatementBindingView;
import io.helidon.data.jdbc.JdbcResults;
import io.helidon.data.jdbc.JdbcResults.Preparation;
import io.helidon.data.jdbc.ResultSetFetchDirection;
import io.helidon.data.jdbc.ResultSetHoldability;
import io.helidon.data.jdbc.ResultSetType;
import io.helidon.data.jdbc.TransactionIsolation;
import io.helidon.data.jdbc.UncheckedSQLException;

import static java.util.Objects.requireNonNull;

final class JdbcPlanImpl<T> implements JdbcPlan<T> {

    private final JdbcPlanConfig<T> prototype;

    JdbcPlanImpl(JdbcPlanConfig<T> prototype) {
        super();
        this.prototype = requireNonNull(prototype, "prototype");
    }

    @Override // JdbcPlan
    public T execute() throws SQLException {
        // Close actions to run either (a) in the case of error while we're preparing this plan, or (b) as part of
        // JdbcResults.close() once the caller has taken ownership.
        //
        // A connection is added first. Its statements are then inserted in order before it. The next connection, in the
        // rare event that there is one, is then added first, and its statements are inserted in order before it, and so
        // on.
        //
        // The net effect is that statements are closed in order, then their creating connection is closed, then any
        // further statements are closed in order, and their creating connection, and so on.
        List<SqlRunnable> closers = new LinkedList<>();
        List<Preparation> preparations = new ArrayList<>();
        try {
            for (ConnectionPlanConfig cpConfig : this.prototype.connectionPlans()) { // normally only one
                List<StatementPlanConfig> spConfigs = cpConfig.statementPlans(); // normally only one
                if (spConfigs.isEmpty()) {
                    // If there won't be any statements for this connection to execute, there's no point in going
                    // further in this loop iteration.
                    continue;
                }

                Connection c = cpConfig.dataSource().getConnection();
                ConnectionPlan initialConnectionState;
                try {
                    initialConnectionState = new ConnectionPlan(c); // for later restoration if necessary
                } catch (RuntimeException | SQLException e) {
                    try {
                        c.close();
                    } catch (SQLException closeFailure) {
                        e.addSuppressed(closeFailure);
                    }
                    throw e;
                }
                closers.addFirst(() -> restoreConnectionStateAndClose(c, initialConnectionState));
                new ConnectionPlan(cpConfig).install(c);

                for (int i = 0; i < spConfigs.size(); i++) {
                    StatementPlanConfig spConfig = spConfigs.get(i);

                    Statement s = prepareStatement(c, spConfig);
                    StatementPlan initialStatementState;
                    try {
                        initialStatementState = new StatementPlan(s); // for later restoration if necessary
                    } catch (RuntimeException | SQLException e) {
                        try {
                            s.close();
                        } catch (SQLException closeFailure) {
                            e.addSuppressed(closeFailure);
                        }
                        throw e;
                    }
                    closers.add(i, () -> restoreStatementStateAndClose(s, initialStatementState));
                    new StatementPlan(spConfig).install(s);

                    ExecutionPlanConfig epConfig = spConfig.executionPlan().orElse(null);
                    if (epConfig == null) {
                        // No particular execution instructions or overrides. Very common.
                        preparations.add(s instanceof PreparedStatement ps
                                         ? new Preparation(ps)
                                         : new Preparation(s, spConfig.statement()));
                    } else if (s instanceof CallableStatement cs && !epConfig.outParameterIndices().isEmpty()) {
                        // Stored procedure with OUT parameters.
                        preparations.add(new Preparation(cs,
                                                         epConfig.resultsAdvancementBehavior(),
                                                         epConfig.outParameterIndices().stream()
                                                         .mapToInt(Integer::intValue).toArray()));
                    } else if (s instanceof PreparedStatement ps) {
                        // Specific instructions on what to do with multiple result sets and closing behavior.
                        preparations.add(new Preparation(ps, epConfig.resultsAdvancementBehavior()));
                    } else if (!epConfig.columnIndexes().isEmpty()) {
                        preparations.add(new Preparation(s,
                                                         spConfig.statement(),
                                                         epConfig.columnIndexes().toArray(String[]::new),
                                                         epConfig.resultsAdvancementBehavior()));
                    } else if (!epConfig.columnNames().isEmpty()) {
                        preparations.add(new Preparation(s,
                                                         spConfig.statement(),
                                                         epConfig.columnNames().toArray(String[]::new),
                                                         epConfig.resultsAdvancementBehavior()));
                    } else if (epConfig.generatedKeysBehavior() != GeneratedKeysBehavior.UNSPECIFIED) {
                        preparations.add(new Preparation(s,
                                                         spConfig.statement(),
                                                         epConfig.generatedKeysBehavior(),
                                                         epConfig.resultsAdvancementBehavior()));
                    } else {
                        preparations.add(new Preparation(s, spConfig.statement(), epConfig.resultsAdvancementBehavior()));
                    }
                }
            }
            JdbcResults jr = JdbcResults.of(preparations);
            for (SqlRunnable closer : closers) {
                Runnable closeAction = () -> {
                    try {
                        closer.run();
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                };
                jr.onClose(closeAction);
            }
            return this.prototype().transformer().apply(jr);
        } catch (RuntimeException | SQLException e) {
            for (SqlRunnable closer : closers) {
                try {
                    closer.run();
                } catch (RuntimeException | SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            closers.clear();
            throw e;
        }
    }

    @Override // JdbcPlan (RuntimeType.Api)
    public JdbcPlanConfig<T> prototype() {
        return this.prototype;
    }


    /*
     * Static methods.
     */


    private static Statement prepareStatement(Connection c, StatementPlanConfig spc) throws SQLException {
        Statement s;
        ExecutionPlanConfig epc = spc.executionPlan().orElse(null);
        String jdbcStatementText = spc.statement();
        if (epc == null) {
            if (CallableStatement.class.isAssignableFrom(spc.type())) {
                s = c.prepareCall(jdbcStatementText);
            } else if (PreparedStatement.class.isAssignableFrom(spc.type())) {
                s = c.prepareStatement(jdbcStatementText);
            } else {
                s = c.createStatement();
            }
        } else if (!epc.columnIndexes().isEmpty()) {
            // assert PreparedStatement.class.isAssignableFrom(epc.type());
            s = c.prepareStatement(jdbcStatementText,
                                   epc.columnIndexes().stream().mapToInt(Integer::intValue).toArray());
        } else if (!epc.columnNames().isEmpty()) {
            // assert PreparedStatement.class.isAssignableFrom(epc.type());
            s = c.prepareStatement(jdbcStatementText,
                                   epc.columnNames().toArray(String[]::new));
        } else if (CallableStatement.class.isAssignableFrom(spc.type())) {
            if (epc.resultSetType() == ResultSetType.UNSPECIFIED) {
                s = c.prepareCall(jdbcStatementText);
            } else if (epc.resultSetHoldability() == ResultSetHoldability.UNSPECIFIED) {
                s = c.prepareCall(jdbcStatementText,
                                  epc.resultSetType().value(),
                                  epc.resultSetConcurrency().value());
            } else {
                s = c.prepareCall(jdbcStatementText,
                                  epc.resultSetType().value(),
                                  epc.resultSetConcurrency().value(),
                                  epc.resultSetHoldability().value());
            }
        } else if (epc.generatedKeysBehavior() != GeneratedKeysBehavior.UNSPECIFIED) {
            // assert PreparedStatement.class.isAssignableFrom(epc.type());
            s = c.prepareStatement(jdbcStatementText,
                                   epc.generatedKeysBehavior().value());
        } else if (epc.resultSetType() == ResultSetType.UNSPECIFIED) {
            if (PreparedStatement.class.isAssignableFrom(spc.type())) {
                s = c.prepareStatement(jdbcStatementText);
            } else {
                s = c.createStatement();
            }
        } else if (epc.resultSetHoldability() == ResultSetHoldability.UNSPECIFIED) {
            if (PreparedStatement.class.isAssignableFrom(spc.type())) {
                s = c.prepareStatement(jdbcStatementText,
                                       epc.resultSetType().value(),
                                       epc.resultSetConcurrency().value());
            } else {
                s = c.createStatement(epc.resultSetType().value(),
                                      epc.resultSetConcurrency().value());
            }
        } else if (PreparedStatement.class.isAssignableFrom(spc.type())) {
            s = c.prepareStatement(jdbcStatementText,
                                   epc.resultSetType().value(),
                                   epc.resultSetConcurrency().value(),
                                   epc.resultSetHoldability().value());
        } else {
            s = c.createStatement(epc.resultSetType().value(),
                                  epc.resultSetConcurrency().value(),
                                  epc.resultSetHoldability().value());
        }
        if (s instanceof PreparedStatement ps) {
            spc.argumentsBinder().bind(JdbcPreparedStatementBindingView.of(ps));
        }
        return s;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    private static void restoreConnectionStateAndClose(Connection c, ConnectionPlan initial) throws SQLException {
        try (c) {
            initial.install(c);
        }
    }

    private static void restoreStatementStateAndClose(Statement s, StatementPlan initial) throws SQLException {
        try (s) {
            initial.install(s);
        }
    }


    /*
     * Inner and nested classes.
     */


    private record ConnectionPlan(ConnectionPlanConfig prototype) {

        private ConnectionPlan {
            requireNonNull(prototype, "prototype");
        }

        private ConnectionPlan(Connection c) throws SQLException {
            this(ConnectionPlanConfig.builder()
                 .dataSource(new DataSourceStub()) // not used by this ConnectionPlan class; just needs to be non-null
                 .catalog(CatalogConfig.builder().value(c.getCatalog()).build())
                 .clientInfo(c.getClientInfo())
                 .resultSetHoldability(ResultSetHoldability.of(c.getHoldability()))
                 .readOnly(c.isReadOnly())
                 .schema(SchemaConfig.builder().value(c.getSchema()).build())
                 .transactionIsolation(TransactionIsolation.of(c.getTransactionIsolation()))
                 .typeMap(c.getTypeMap())
                 .build());
        }

        private Connection install(Connection c) throws SQLException {
            CatalogConfig catalogConfig = this.prototype.catalog().orElse(null);
            Catalog catalog = catalogConfig == null ? null : new Catalog(catalogConfig.value().orElse(null));
            if (catalog != null && !Objects.equals(catalog.value(), c.getCatalog())) {
                c.setCatalog(catalog.value());
            }
            Properties clientInfo = this.prototype.clientInfo().orElse(null);
            if (clientInfo != null && !equals(clientInfo, c.getClientInfo())) {
                // Note: this is a full replacement
                c.setClientInfo(clientInfo);
            }
            ResultSetHoldability resultSetHoldability = this.prototype.resultSetHoldability();
            if (resultSetHoldability != ResultSetHoldability.UNSPECIFIED
                && resultSetHoldability.value() != c.getHoldability()) {
                c.setHoldability(resultSetHoldability.value());
            }
            boolean readOnly = this.prototype.readOnly();
            if (readOnly != c.isReadOnly()) {
                c.setReadOnly(readOnly);
            }
            SchemaConfig schemaConfig = this.prototype.schema().orElse(null);
            Schema schema = schemaConfig == null ? null : new Schema(schemaConfig.value().orElse(null));
            if (schema != null && !Objects.equals(schema.value(), c.getSchema())) {
                c.setSchema(schema.value());
            }
            TransactionIsolation ti = this.prototype.transactionIsolation().orElse(null);
            if (ti != null
                && ti != TransactionIsolation.of(c.getTransactionIsolation())) {
                c.setTransactionIsolation(ti.value());
            }
            Map<String, Class<?>> typeMap = this.prototype.typeMap().orElse(null);
            if (typeMap != null && !typeMap.equals(c.getTypeMap())) {
                c.setTypeMap(typeMap);
            }
            return c;
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

    private record StatementPlan(StatementPlanConfig prototype) {

        private StatementPlan {
            requireNonNull(prototype, "prototype");
        }

        private StatementPlan(Statement s) throws SQLException {
            this(StatementPlanConfig.builder()
                 .type(switch (requireNonNull(s, "s")) {
                     case CallableStatement cs -> CallableStatement.class;
                     case PreparedStatement ps -> PreparedStatement.class;
                     default -> Statement.class;
                     })
                 .statement("SELECT 1") // not used by this StatementPlan class; just needs to be non-null
                 .closeOnCompletion(false) // no way to read it; false is the default
                 .fetchDirection(ResultSetFetchDirection.of(s.getFetchDirection()))
                 .fetchSize(s.getFetchSize())
                 .maxRows(maxRows(s))
                 .maxFieldSize(s.getMaxFieldSize())
                 .queryTimeout(s.getQueryTimeout())
                 .argumentsBinder(ps -> {})
                 .build());
        }

        private <S extends Statement> S install(S s) throws SQLException {
            // closeOnCompletion is not strictly speaking reversible and has undefined semantics for pooled
            // statements. We make a best effort.
            if (this.prototype.closeOnCompletion()
                && (this.prototype.closeOnCompletion() != s.isCloseOnCompletion())) {
                // This is a little shaky since it strictly speaking is not fully reversible.
                s.closeOnCompletion();
            }
            if (this.prototype.fetchDirection() != ResultSetFetchDirection.UNKNOWN
                && this.prototype.fetchDirection() != ResultSetFetchDirection.of(s.getFetchDirection())) {
                s.setFetchDirection(this.prototype.fetchDirection().value());
            }
            if (this.prototype.fetchSize() >= 0 && (this.prototype.fetchSize() != s.getFetchSize())) {
                s.setFetchSize(this.prototype.fetchSize());
            }
            if (this.prototype.maxRows() >= 0L) {
                try {
                    if (this.prototype.maxRows() != s.getLargeMaxRows()) {
                        s.setLargeMaxRows(this.prototype.maxRows());
                    }
                } catch (SQLFeatureNotSupportedException e) {
                    int maxRows =
                        this.prototype.maxRows() >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) this.prototype.maxRows();
                    if (maxRows != s.getMaxRows()) {
                        s.setMaxRows(maxRows);
                    }
                }
            }
            if (this.prototype.maxFieldSize() >= 0 && (this.prototype.maxFieldSize() != s.getMaxFieldSize())) {
                s.setMaxFieldSize(this.prototype.maxFieldSize());
            }
            // poolable omitted for now; not clear what the semantics are
            if (this.prototype.queryTimeout() >= 0 && (this.prototype.queryTimeout() != s.getQueryTimeout())) {
                s.setQueryTimeout(this.prototype.queryTimeout());
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

    private static class DataSourceStub implements DataSource {

        private DataSourceStub() {
            super();
        }

        @Override // DataSource
        public Connection getConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override // DataSource
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override // DataSource
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override // DataSource
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }

        @Override // DataSource
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override // DataSource
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override // DataSource
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override // DataSource
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override // DataSource
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

    }

}
