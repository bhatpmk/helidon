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

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcClientStreamingTest {

    @Test
    void openRowsMapsRowsAndClosesResourcesOnExhaustion() {
        TestContext context = populatedClient();

        JdbcResultIterable<String> rows = context.client.execute("SELECT name FROM pokemon ORDER BY id")
                .readColumns("name")
                .openRows(row -> row.value("name", String.class));

        assertThat(context.dataSource.allClosed(), is(false));
        List<String> values = new ArrayList<>();
        rows.forEach(values::add);

        assertThat(values, contains("Bulbasaur", "Charmander", "Squirtle"));
        assertThat(context.dataSource.allClosed(), is(true));
        assertThat(context.dataSource.closeOrder(), contains("resultSet", "statement", "connection"));
        rows.close();
    }

    @Test
    void openRowsClosesResourcesAfterEarlyTermination() {
        TestContext context = populatedClient();

        try (JdbcResultIterable<Integer> rows = context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .openRows(row -> row.value(1, Integer.class))) {
            Iterator<Integer> iterator = rows.iterator();
            assertThat(iterator.next(), is(1));
            assertThat(context.dataSource.allClosed(), is(false));
        }

        assertThat(context.dataSource.allClosed(), is(true));
    }

    @Test
    void withRowsClosesResourcesBeforeReturning() {
        TestContext context = populatedClient();
        List<Integer> values = new ArrayList<>();

        context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .withRows(row -> row.value(1, Integer.class), rows -> rows.forEach(values::add));

        assertThat(values, contains(1, 2, 3));
        assertThat(context.dataSource.allClosed(), is(true));
    }

    @Test
    void withRowsClosesResourcesWhenCallbackDoesNotIterate() {
        TestContext context = populatedClient();

        context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .withRows(row -> row.value(1, Integer.class), rows -> {
                    // The callback intentionally returns without claiming the iterator.
                });

        assertThat(context.dataSource.allClosed(), is(true));
        assertThat(context.dataSource.closeOrder(), contains("resultSet", "statement", "connection"));
    }

    @Test
    void withRowsPreservesConsumerFailureAndClosesResources() {
        TestContext context = populatedClient();
        IllegalStateException expected = new IllegalStateException("consumer failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                                                     () -> context.client.execute("SELECT id FROM pokemon ORDER BY id")
                                                             .withRows(row -> row.value(1, Integer.class), rows -> {
                                                                 rows.iterator().next();
                                                                 throw expected;
                                                             }));

        assertSame(expected, actual);
        assertThat(context.dataSource.allClosed(), is(true));
    }

    @Test
    void openRowsPreservesMapperFailureAndClosesResources() {
        TestContext context = populatedClient();
        IllegalStateException expected = new IllegalStateException("mapper failed");

        IllegalStateException actual;
        try (JdbcResultIterable<Integer> rows = context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .openRows(row -> {
                    throw expected;
                })) {
            actual = assertThrows(IllegalStateException.class, () -> rows.iterator().next());
        }

        assertSame(expected, actual);
        assertThat(context.dataSource.allClosed(), is(true));
    }

    @Test
    void openRowsHonorsMaximumRowsAndRejectsSecondTraversal() {
        TestContext context = populatedClient();

        try (JdbcResultIterable<Integer> rows = context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .maxRows(2)
                .openRows(row -> row.value(1, Integer.class))) {
            List<Integer> values = new ArrayList<>();
            rows.forEach(values::add);
            assertThat(values, contains(1, 2));
            assertThrows(IllegalStateException.class, rows::iterator);
        }

        assertThat(context.dataSource.allClosed(), is(true));
    }

    @Test
    void openRowsRejectsTraversalFromAnotherThread() throws InterruptedException {
        TestContext context = populatedClient();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (JdbcResultIterable<Integer> rows = context.client.execute("SELECT id FROM pokemon ORDER BY id")
                .openRows(row -> row.value(1, Integer.class))) {
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    rows.iterator();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            worker.join();

            assertThat(failure.get(), instanceOf(IllegalStateException.class));
        }

        assertThat(context.dataSource.allClosed(), is(true));
    }

    private static TestContext populatedClient() {
        JdbcDataSource delegate = new JdbcDataSource();
        delegate.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        TrackingDataSource dataSource = new TrackingDataSource(delegate);
        JdbcClient client = new JdbcClientImpl("test", dataSource, Optional.empty());
        client.execute("CREATE TABLE pokemon (id INTEGER PRIMARY KEY, name VARCHAR(40))").discard();
        client.execute("INSERT INTO pokemon (id, name) VALUES (1, 'Bulbasaur'), (2, 'Charmander'), (3, 'Squirtle')")
                .discard();
        dataSource.reset();
        return new TestContext(client, dataSource);
    }

    private static final class TestContext {
        private final JdbcClient client;
        private final TrackingDataSource dataSource;

        private TestContext(JdbcClient client, TrackingDataSource dataSource) {
            this.client = client;
            this.dataSource = dataSource;
        }
    }

    /**
     * Observes close calls without changing the H2 behavior used by the tests.
     */
    private static final class TrackingDataSource implements DataSource {
        private final DataSource delegate;
        private boolean connectionClosed;
        private boolean statementClosed;
        private boolean resultSetClosed;
        private final List<String> closeOrder = new ArrayList<>();

        private TrackingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            reset();
            return connection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            reset();
            return connection(delegate.getConnection(username, password));
        }

        private Connection connection(Connection connection) {
            return proxy(Connection.class, connection, (method, args) -> {
                if (method.getName().equals("prepareStatement")) {
                    return statement((PreparedStatement) invoke(method, connection, args));
                }
                if (method.getName().equals("close")) {
                    if (!connectionClosed) {
                        connectionClosed = true;
                        closeOrder.add("connection");
                    }
                }
                return invoke(method, connection, args);
            });
        }

        private PreparedStatement statement(PreparedStatement statement) {
            return proxy(PreparedStatement.class, statement, (method, args) -> {
                if (method.getName().equals("executeQuery")) {
                    return resultSet((ResultSet) invoke(method, statement, args));
                }
                if (method.getName().equals("close")) {
                    if (!statementClosed) {
                        statementClosed = true;
                        closeOrder.add("statement");
                    }
                }
                return invoke(method, statement, args);
            });
        }

        private ResultSet resultSet(ResultSet resultSet) {
            return proxy(ResultSet.class, resultSet, (method, args) -> {
                if (method.getName().equals("close")) {
                    if (!resultSetClosed) {
                        resultSetClosed = true;
                        closeOrder.add("resultSet");
                    }
                }
                return invoke(method, resultSet, args);
            });
        }

        private boolean allClosed() {
            return connectionClosed && statementClosed && resultSetClosed;
        }

        private List<String> closeOrder() {
            return List.copyOf(closeOrder);
        }

        private void reset() {
            connectionClosed = false;
            statementClosed = false;
            resultSetClosed = false;
            closeOrder.clear();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, T delegate, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(delegate.getClass().getClassLoader(),
                                                new Class<?>[] {type},
                                                (proxy, method, args) -> invocation.invoke(method, args)));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
