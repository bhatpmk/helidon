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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import io.helidon.data.jdbc.JdbcResult;
import io.helidon.data.jdbc.JdbcResultSet;
import io.helidon.data.jdbc.JdbcResults;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.instanceOf;

final class BasicUsageTest {

    private JdbcDataSource ds;

    private BasicUsageTest() {
        super();
    }

    @BeforeEach
    void setupDataSource() {
        this.ds = new JdbcDataSource();
        this.ds.setUrl("jdbc:h2:mem:" + randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void testUsage() throws SQLException {
        var plan = JdbcPlanConfig.<JdbcResults>builder()
            .addConnectionPlan(ConnectionPlanConfig.builder()
                               .dataSource(this.ds)
                               .addStatementPlan(StatementPlanConfig.builder()
                                                 .statement("SELECT * FROM INFORMATION_SCHEMA.TABLES")
                                                 .argumentsBinder(ps -> {})
                                                 .build())
                               .build())
            .transformer(jr -> jr)
            .build();
        try (var jrss = plan.execute()) {
            jrss.forOnly(JdbcResultSet.class, BasicUsageTest::printResultSet);
        }
    }

    @Test
    void testConvenienceUsage() throws SQLException {
        try (var jrss = JdbcPlan.execute(this.ds, "SELECT * FROM INFORMATION_SCHEMA.TABLES")) {
            jrss.forOnly(JdbcResultSet.class, BasicUsageTest::printResultSet);
        }
    }

    private static void printResultSet(JdbcResultSet jrs) throws SQLException {
        printResultSet(jrs.resultSet());
    }

    private static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        while (rs.next()) {
            for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                System.out.print(rs.getObject(i));
                if (i < rsmd.getColumnCount()) {
                    System.out.print(", ");
                }
            }
            System.out.println();
        }
    }

}
