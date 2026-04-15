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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import io.helidon.data.jdbc.function.JdbcBooleanSupplier;
import io.helidon.data.jdbc.function.JdbcFunction;
import io.helidon.data.jdbc.function.JdbcLongFunction;
import io.helidon.data.jdbc.function.JdbcLongSupplier;
import io.helidon.data.jdbc.function.JdbcSupplier;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

final class JdbcStatementResultTest {

    private JdbcDataSource ds;
    
    private JdbcStatementResultTest() {
        super();
    }

    @BeforeEach
    void setupDataSource() {
        this.ds = new JdbcDataSource();
        this.ds.setUrl("jdbc:h2:mem:" + randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void testJdbcResultsSpike() throws SQLException {
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM INFORMATION_SCHEMA.TABLES;");
             JdbcResults rs = JdbcResults.of(ps)) {
            assertThat(rs.advance(), is(true));
            JdbcResultSet jrs = (JdbcResultSet) rs.get().orElseThrow(AssertionError::new);
            assertThat(jrs, not(nullValue()));
            assertThat(rs.advance(), is(false));
            assertThat(rs.closed(), is(false)); // exhaustion is not closure
        }
    }

}
