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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.helidon.data.DataException;

import static java.util.Objects.requireNonNull;

final class JdbcResultReader {

    <T> List<T> list(ResultSet resultSet, JdbcRowMapper<? extends T> mapper) throws SQLException {
        requireNonNull(resultSet, "resultSet");
        requireNonNull(mapper, "mapper");
        List<T> result = new ArrayList<>();
        JdbcResultSetRowView row = new JdbcResultSetRowViewImpl(resultSet);
        while (resultSet.next()) {
            result.add(mapper.map(row));
        }
        return List.copyOf(result);
    }

    <T> List<T> list(ResultSet resultSet, JdbcRowReducer<T> reducer) throws SQLException {
        requireNonNull(resultSet, "resultSet");
        requireNonNull(reducer, "reducer");
        JdbcResultSetRowView row = new JdbcResultSetRowViewImpl(resultSet);
        while (resultSet.next()) {
            reducer.add(row);
        }
        return List.copyOf(reducer.result());
    }

    <T> Optional<T> optional(ResultSet resultSet,
                            JdbcRowMapper<? extends T> mapper,
                            String tooManyRowsMessage) throws SQLException {
        requireNonNull(resultSet, "resultSet");
        requireNonNull(mapper, "mapper");
        requireNonNull(tooManyRowsMessage, "tooManyRowsMessage");
        if (!resultSet.next()) {
            return Optional.empty();
        }
        JdbcResultSetRowView row = new JdbcResultSetRowViewImpl(resultSet);
        T result = mapper.map(row);
        if (resultSet.next()) {
            throw new DataException(tooManyRowsMessage);
        }
        return Optional.ofNullable(result);
    }

    <T> Optional<T> optional(ResultSet resultSet,
                            JdbcRowReducer<T> reducer,
                            String tooManyRowsMessage) throws SQLException {
        requireNonNull(resultSet, "resultSet");
        requireNonNull(reducer, "reducer");
        requireNonNull(tooManyRowsMessage, "tooManyRowsMessage");
        List<T> result = list(resultSet, reducer);
        if (result.size() > 1) {
            throw new DataException(tooManyRowsMessage);
        }
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(result.getFirst());
    }

}
