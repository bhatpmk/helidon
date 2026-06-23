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

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.helidon.data.jdbc.function.JdbcAutoCloseable;
import io.helidon.data.jdbc.function.JdbcBooleanSupplier;
import io.helidon.data.jdbc.function.JdbcRunnable;
import io.helidon.data.jdbc.function.JdbcSupplier;

final class JdbcResultsImpl implements JdbcResults {


    /*
     * Static fields.
     */


    private static final long[] EMPTY_LONG_ARRAY = new long[0];


    /*
     * Instance fields.
     */


    private final List<JdbcRunnable> closers;

    private final List<Preparation> preparations;

    private int preparationIndex;

    private Preparation currentPreparation;

    // Never null. Self-replacing.
    private JdbcBooleanSupplier advancer;

    private JdbcSupplier<? extends long[]> batchExecutor;

    private JdbcResult result;

    private boolean closed;


    /*
     * Constructors.
     */


    JdbcResultsImpl(Preparation p) {
        this(List.of(p));
    }

    JdbcResultsImpl(List<? extends Preparation> ps) {
        super();
        this.closers = new LinkedList<>();
        this.preparations = List.copyOf(ps);
        this.configure();
    }


    /*
     * Instance methods.
     */


    @Override // JdbcResults
    public boolean advance() throws SQLException {
        ensureOpen();
        if (this.currentPreparation == null) {
            return false;
        } else if (this.batchExecutor != null) {
            this.result = new JdbcBatchExecutionResultsImpl(this.batchExecutor.get());
        } else {
            Statement s = this.currentPreparation.statement();
            if (this.advancer.getAsBoolean()) {
                JdbcResultSetImpl result = new JdbcResultSetImpl(s.getResultSet());
                this.closers.add(this.preparationIndex, result::close);
                this.result = result;
            } else {
                long updateCount = updateCount(s);
                if (updateCount >= 0L) {
                    this.result = new JdbcUpdateCountImpl(updateCount);
                } else {
                    int[] outParameterIndices = this.currentPreparation.outParameterIndices();
                    if (outParameterIndices.length > 0) {
                        this.result = new JdbcOutValuesImpl((CallableStatement) s, outParameterIndices);
                    } else {
                        // Go to the next preparation if there is one (uncommon) and try to keep going.
                        ++this.preparationIndex;
                        this.configure();
                        return this.advance(); // recurse
                    }
                }
            }
        }
        return true; // i.e. this.result != null
    }

    @Override // JdbcResults (JdbcOpen)
    public void close() throws SQLException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.preparationIndex = this.preparations.size(); // deliberately out of bounds
        this.currentPreparation = null;
        this.batchExecutor = null;
        this.advancer = JdbcResultsImpl::returnFalse;
        this.result = null;
        Throwable t = null;
        Iterator<JdbcRunnable> closers = this.closers.iterator();
        while (closers.hasNext()) {
            JdbcRunnable closer = closers.next();
            try {
                closer.run();
            } catch (RuntimeException | SQLException e) {
                if (t == null) {
                    t = e;
                } else {
                    t.addSuppressed(e);
                }
            }
            closers.remove();
        }
        switch (t) {
        case null -> {}
        case SQLException s -> throw s;
        case RuntimeException r -> throw r;
        case Throwable x -> throw new AssertionError(x.getMessage(), x);
        }
    }

    @Override // JdbcResults
    public boolean closed() {
        return this.closed;
    }

    @Override // JdbcResults
    public Optional<JdbcResult> get() {
        ensureOpen();
        return Optional.ofNullable(this.result);
    }

    @Override // JdbcResults
    public Optional<ResultSet> keys() throws SQLException {
        ensureOpen();
        return this.currentPreparation == null
            ? Optional.empty()
            : Optional.ofNullable(this.currentPreparation.statement().getGeneratedKeys());
    }

    @Override // JdbcResults (JdbcOpen)
    public JdbcResultsImpl onClose(JdbcRunnable r) {
        ensureOpen();
        this.closers.addLast(r);
        return this;
    }

    @Override // JdbcResults (JdbcOpen)
    public JdbcResultsImpl onClose(Runnable r) {
        return this.onClose((JdbcRunnable) r::run);
    }

    @Override // JdbcResults (JdbcWarningsBearing)
    public Optional<SQLWarning> warnings() throws SQLException {
        ensureOpen();
        return this.currentPreparation == null
            ? Optional.empty()
            : Optional.ofNullable(this.currentPreparation.statement().getWarnings());
    }


    /*
     * Private methods.
     */


    private void configure() {
        if (this.preparationIndex >= this.preparations.size()) {
            this.currentPreparation = null;
            this.result = null;
            this.batchExecutor = null;
            this.advancer = JdbcResultsImpl::returnFalse;
            return;
        }
        Preparation p = this.preparations.get(this.preparationIndex);
        this.currentPreparation = p;
        this.result = p.initialResult().orElse(null);
        Statement s = p.statement();
        ResultsAdvancementBehavior resultsAdvancementBehavior = p.resultsAdvancementBehavior();
        JdbcBooleanSupplier subsequentAdvancer = switch (resultsAdvancementBehavior) {
        case UNSPECIFIED -> s::getMoreResults;
        default -> () -> s.getMoreResults(resultsAdvancementBehavior.value());
        };
        if (p.batch()) {
            // Simple and very uncommon.
            this.batchExecutor = () -> {
                long[] rv = executeLargeBatch(s);
                this.batchExecutor = null;
                this.advancer = subsequentAdvancer;
                return rv;
            };
            this.advancer = JdbcResultsImpl::returnFalse;
        } else if (this.result != null) {
            this.batchExecutor = null;
            this.advancer = subsequentAdvancer;
            if (this.result instanceof JdbcAutoCloseable jac) {
                this.closers.add(this.preparationIndex, jac::close);
            }
        } else if (s instanceof PreparedStatement ps) {
            this.batchExecutor = null;
            this.advancer = () -> {
                boolean rv = ps.execute();
                this.advancer = subsequentAdvancer;
                return rv;
            };
        } else {
            this.batchExecutor = null;
            String sql = p.sql().orElseThrow(() -> new IllegalArgumentException("p; p.sql() == null"));
            int[] columnIndices = p.columnIndices();
            String[] columnNames = p.columnNames();
            JdbcBooleanSupplier initialAdvancer;
            if (columnIndices.length > 0) {
                initialAdvancer = () -> s.execute(sql, columnIndices);
            } else if (columnNames.length > 0) {
                initialAdvancer = () -> s.execute(sql, columnNames);
            } else {
                GeneratedKeysBehavior generatedKeysBehavior = p.generatedKeysBehavior();
                initialAdvancer = switch (generatedKeysBehavior) {
                case UNSPECIFIED -> () -> s.execute(sql);
                default -> () -> s.execute(sql, generatedKeysBehavior.value());
                };
            }
            this.advancer = () -> {
                boolean rv = initialAdvancer.getAsBoolean();
                this.advancer = subsequentAdvancer;
                return rv;
            };
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
    }


    /*
     * Static methods.
     */

    private static long[] executeLargeBatch(Statement s) throws SQLException {
        return s == null ? EMPTY_LONG_ARRAY : s.executeLargeBatch();
    }

    private static long updateCount(Statement s) throws SQLException {
        try {
            return s == null ? -1L : s.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException e) {
            return s.getUpdateCount();
        }
    }

    private static void doNothing() {
    }

    private static boolean returnFalse() {
        return false;
    }

}
