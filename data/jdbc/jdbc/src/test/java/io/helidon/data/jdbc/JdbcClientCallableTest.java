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

import java.sql.ResultSet;
import java.sql.Types;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class JdbcClientCallableTest {

    @Test
    void mapsCallableOutParameter() {
        JdbcClient client = client();

        Map<String, Object> values = client.execute("{? = call ABS(?)}")
                .outParam(1, "absolute", Types.INTEGER)
                .param(-7)
                .outParams();

        assertThat(values.get("absolute"), is(7));
    }

    @Test
    void mapsCallableOutParameterTerminal() {
        JdbcClient client = client();

        Integer value = client.execute("{? = call ABS(?)}")
                .outParam(1, "absolute", Types.INTEGER)
                .param(-7)
                .outParam("absolute", Integer.class);

        assertThat(value, is(7));
    }

    @Test
    void bindsNamedCallableInput() {
        JdbcClient client = client();

        Integer value = client.execute("{:result = call ABS(:value)}")
                .outParam(1, "absolute", Types.INTEGER)
                .params(java.util.List.of(JdbcParameter.create("value", -7).withSqlType(Types.INTEGER)))
                .outParam("absolute", Integer.class);

        assertThat(value, is(7));
    }

    @Test
    void mapsNullCallableOutParameter() {
        JdbcClient client = client();

        Map<String, Object> values = client.execute("{? = call NULLIF(?, ?)}")
                .outParam(1, "nullable", Types.INTEGER)
                .param(7)
                .param(7)
                .outParams();

        assertThat(values.containsKey("nullable"), is(true));
        assertThat(values.get("nullable"), nullValue());
    }

    @Test
    void appliesCallableResultSetOptions() {
        JdbcClient client = client();

        Map<String, Object> values = client.execute("{? = call ABS(?)}")
                .resultSet(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
                .outParam(1, "absolute", Types.INTEGER)
                .param(-7)
                .outParams();

        assertThat(values.get("absolute"), is(7));
    }

    private static JdbcClient client() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcClientImpl("test", dataSource, Optional.empty());
    }
}
