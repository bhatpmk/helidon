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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

import io.helidon.data.jdbc.function.JdbcBooleanSupplier;
import io.helidon.data.jdbc.function.JdbcRunnable;

import static java.sql.Statement.CLOSE_CURRENT_RESULT;
import static java.util.Objects.requireNonNull;

final class JdbcResultsImpl implements JdbcResults {

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private final Deque<JdbcRunnable> closers;

    private final Statement s;

    // Treat as final
    private int[] outParameterIndices;

    // Self-replacing; treat as final
    private JdbcBooleanSupplier advancer;

    private boolean closed;

    private JdbcResult jr;

    JdbcResultsImpl(CallableStatement s,
                    JdbcBooleanSupplier subsequentAdvancer,
                    int[] outParameterIndices) {
        this(s, s::execute, subsequentAdvancer, outParameterIndices == null ? EMPTY_INT_ARRAY : outParameterIndices);
    }

    JdbcResultsImpl(PreparedStatement s,
                    JdbcBooleanSupplier subsequentAdvancer) {
        this(s, s::execute, subsequentAdvancer, EMPTY_INT_ARRAY);
    }

    // Experimental; edge case
    JdbcResultsImpl(ResultSet rs) throws SQLException {
        this(rs, CLOSE_CURRENT_RESULT);
    }

    // Experimental; edge case
    JdbcResultsImpl(ResultSet rs, int current) throws SQLException {
        this(rs, rs.getStatement(), current);
    }

    // Private bridge constructor
    private JdbcResultsImpl(ResultSet rs, Statement s, int current) {
        this(s, () -> s.getMoreResults(current), EMPTY_INT_ARRAY);
        JdbcResultSetImpl jr = new JdbcResultSetImpl(rs);
        this.closers.push(jr::close);
        this.jr = jr;
    }

    // Private bridge constructor
    private JdbcResultsImpl(Statement s, JdbcBooleanSupplier subsequentAdvancer, int[] outParameterIndices) {
        this(s, subsequentAdvancer, subsequentAdvancer, outParameterIndices);
    }

    JdbcResultsImpl(Statement s,
                    JdbcBooleanSupplier initialAdvancer, // e.g. some variant of Statement.execute()
                    JdbcBooleanSupplier subsequentAdvancer) { // e.g. some variant of Statement.getMoreResults(int)
        this(s, initialAdvancer, subsequentAdvancer, EMPTY_INT_ARRAY);
    }

    JdbcResultsImpl(Statement s,
                    JdbcBooleanSupplier initialAdvancer, // e.g. some variant of Statement.execute()
                    JdbcBooleanSupplier subsequentAdvancer, // e.g. some variant of Statement.getMoreResults(int)
                    int[] outParameterIndices) {
        super();
        this.closers = new ArrayDeque<>();
        // yes, mutable, copied by the relevant JdbcResult subclass
        this.outParameterIndices = outParameterIndices.length == 0 ? EMPTY_INT_ARRAY : outParameterIndices;
        this.s = requireNonNull(s, "s");
        requireNonNull(initialAdvancer, "initialAdvancer");
        requireNonNull(subsequentAdvancer, "subsequentAdvancer");
        this.advancer = () -> {
            boolean rv = initialAdvancer.getAsBoolean();
            this.advancer = subsequentAdvancer; // self-replacing
            return rv;
        };
    }

    @Override
    public boolean advance() throws SQLException {
        ensureOpen();
        if (this.advancer.getAsBoolean()) {
            JdbcResultSetImpl jrsi = new JdbcResultSetImpl(this.s.getResultSet());
            this.closers.push(jrsi::close);
            this.jr = jrsi;
        } else {
            long updateCount;
            try {
                updateCount = this.s.getLargeUpdateCount();
            } catch (SQLFeatureNotSupportedException e) {
                updateCount = this.s.getUpdateCount();
            }
            if (updateCount >= 0L) {
                this.jr = new JdbcUpdateCountImpl(updateCount);
            } else if (this.outParameterIndices.length > 0) {
                this.jr = new JdbcOutValuesImpl((CallableStatement) this.s, this.outParameterIndices);
                this.outParameterIndices = EMPTY_INT_ARRAY;
            } else {
                this.jr = null;
                return false;
            }
        }
        return true;
    }

    @Override // JdbcResults (JdbcAutoCloseable)
    public void close() throws SQLException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.outParameterIndices = EMPTY_INT_ARRAY;
        this.advancer = JdbcResultsImpl::returnFalse;
        this.jr = null;
        Throwable t = null;
        Iterator<JdbcRunnable> i = this.closers.iterator();
        while (i.hasNext()) {
            JdbcRunnable c = i.next();
            try {
                c.run();
            } catch (UncheckedSQLException e) {
                if (t == null) {
                    t = e.getCause(); // won't be null
                } else {
                    t.addSuppressed(e.getCause()); // won't be null
                }
            } catch (RuntimeException | SQLException e) {
                if (t == null) {
                    t = e;
                } else {
                    t.addSuppressed(e);
                }
            }
            i.remove();
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
        return Optional.ofNullable(this.jr);
    }

    @Override // JdbcResults
    public Optional<ResultSet> keys() throws SQLException {
        ensureOpen();
        return Optional.ofNullable(this.s.getGeneratedKeys());
    }

    @Override // JdbcResults
    public JdbcResultsImpl onClose(JdbcRunnable r) {
        ensureOpen();
        this.closers.add(r);
        return this;
    }

    @Override // JdbcResults
    public JdbcResultsImpl onClose(Runnable r) {
        return this.onClose((JdbcRunnable) r::run);
    }

    @Override // JdbcResults (JdbcWarningsBearing)
    public Optional<SQLWarning> warnings() throws SQLException {
        ensureOpen();
        return Optional.ofNullable(this.s.getWarnings());
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
    }

    private static void doNothing() {
    }

    private static boolean returnFalse() {
        return false;
    }

}
