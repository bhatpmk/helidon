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
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.helidon.data.DataException;
import io.helidon.data.NoResultException;
import io.helidon.data.NonUniqueResultException;

/**
 * Reduces detached JDBC operation results to the value requested by a terminal method.
 * <p>
 * The reducer selects a result source by shape rather than by the SQL text or the first event in an execution. Direct
 * rows and update counts are selected from the ordered direct-result sequence. Generated keys, callable outputs, and
 * batch outcomes are selected from their typed attachments.
 */
final class JdbcReducers {

    private JdbcReducers() {
    }

    static <T> List<T> list(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        return rows(result).rows()
                .stream()
                .map(mapper::map)
                .toList();
    }

    static <T> T item(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        RowSet rows = rows(result);
        if (rows.isEmpty()) {
            throw new NoResultException("JDBC query returned no rows.");
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC query returned more than one row.");
        }
        return mapper.map(rows.rows().getFirst());
    }

    static <T> T itemOrNull(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        RowSet rows = rows(result);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC query returned more than one row.");
        }
        return mapper.map(rows.rows().getFirst());
    }

    static <T> Optional<T> optional(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        return Optional.ofNullable(itemOrNull(result, mapper));
    }

    /**
     * Reduce a page count query to the range supported by {@link io.helidon.data.Page#totalSize()}.
     *
     * @param result detached count-query result
     * @return non-negative page total
     */
    static int pageTotal(JdbcExecutionResult result) {
        RowSet rows = rows(result);
        if (rows.isEmpty()) {
            throw new NoResultException("JDBC page count query returned no rows.");
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC page count query returned more than one row.");
        }
        if (rows.columns().size() != 1) {
            throw new DataException("JDBC page count query must return exactly one column");
        }

        Object value = rows.rows().getFirst().value(1);
        if (!(value instanceof Number number)) {
            throw new DataException("JDBC page count query must return an integral number");
        }
        BigInteger total = integral(number);
        if (total.signum() < 0) {
            throw new DataException("JDBC page count query returned a negative total: " + total);
        }
        if (total.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new DataException("JDBC page count " + total + " exceeds Page.totalSize() range");
        }
        return total.intValue();
    }

    /**
     * Reduce one scalar row or one update count to the requested scalar type.
     * <p>
     * This shape-aware terminal does not inspect SQL text. A single row is mapped from column one, while a single
     * update count is converted directly. Mixed or multiple direct results are rejected.
     *
     * @param result detached JDBC result
     * @param type requested scalar type
     * @param <T> scalar type
     * @return mapped scalar value
     */
    static <T> T scalar(JdbcExecutionResult result, Class<T> type) {
        Objects.requireNonNull(type, "Scalar type must not be null");
        JdbcDirectResult direct = result.onlyOperation().directResults().only();
        if (direct instanceof JdbcUpdateCountResult updateCount) {
            return JdbcValues.convert(updateCount.count(), type);
        }
        if (direct instanceof JdbcRowsResult rows) {
            if (rows.rows().isEmpty()) {
                throw new NoResultException("JDBC scalar operation returned no rows.");
            }
            if (rows.rows().size() > 1) {
                throw new NonUniqueResultException("JDBC scalar operation returned more than one row.");
            }
            return JdbcValues.convert(rows.rows().rows().getFirst().value(1), type);
        }
        throw new DataException("JDBC operation did not produce a scalar row or update count");
    }

    /**
     * Return one direct update count without inventing a row-shaped result or silently summing multiple counts.
     *
     * @param result detached JDBC result
     * @return the single update count
     */
    static Number updateCount(JdbcExecutionResult result) {
        JdbcOperationResult operation = result.onlyOperation();
        JdbcDirectResult direct = operation.directResults().only();
        if (!(direct instanceof JdbcUpdateCountResult updateCount)) {
            throw new DataException("JDBC operation did not produce an update count");
        }
        return updateCount.count();
    }

    static long[] batchUpdateCounts(JdbcExecutionResult result) {
        JdbcBatchResult batch = result.onlyOperation().batch()
                .orElseThrow(() -> new DataException("JDBC operation contains no batch result"));
        long[] counts = new long[batch.items().size()];
        for (int i = 0; i < counts.length; i++) {
            JdbcBatchResult.JdbcBatchItem item = batch.items().get(i);
            counts[i] = switch (item.status()) {
            case UPDATED -> item.updateCount().orElseThrow(
                    () -> new DataException("JDBC batch item is marked UPDATED without a count"));
            case SUCCESS_NO_INFO -> Statement.SUCCESS_NO_INFO;
            case EXECUTE_FAILED -> Statement.EXECUTE_FAILED;
            case NOT_REPORTED -> throw new DataException("JDBC driver did not report the outcome of batch item " + i);
            };
        }
        return counts;
    }

    static Map<String, Object> outParams(JdbcExecutionResult result) {
        return result.onlyOperation().callableOutputs().values();
    }

    static <T> T outParam(JdbcExecutionResult result, String name, Class<T> type) {
        Objects.requireNonNull(name, "OUT parameter name must not be null");
        Objects.requireNonNull(type, "OUT parameter type must not be null");
        Map<String, Object> values = result.onlyOperation().callableOutputs().values();
        if (!values.containsKey(name)) {
            throw new DataException("JDBC operation contains no OUT parameter named \"" + name + "\".");
        }
        return JdbcValues.convert(values.get(name), type);
    }

    static <T> List<T> outCursor(JdbcExecutionResult result, String name, JdbcRowMapper<T> mapper) {
        Objects.requireNonNull(name, "OUT cursor name must not be null");
        Objects.requireNonNull(mapper, "OUT cursor row mapper must not be null");
        RowSet rows = result.onlyOperation().callableOutputs().cursor(name)
                .orElseThrow(() -> new DataException("JDBC operation contains no OUT cursor named \"" + name + "\"."));
        return rows.rows()
                .stream()
                .map(mapper::map)
                .toList();
    }

    static <T> List<T> generatedKeys(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        RowSet rows = generatedKeyRows(result);
        return rows.rows()
                .stream()
                .map(mapper::map)
                .toList();
    }

    static <T> T generatedKey(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        RowSet rows = generatedKeyRows(result);
        if (rows.isEmpty()) {
            throw new NoResultException("JDBC update returned no generated keys.");
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC update returned more than one generated-key row.");
        }
        return mapper.map(rows.rows().getFirst());
    }

    static <T> Optional<T> optionalGeneratedKey(JdbcExecutionResult result, JdbcRowMapper<T> mapper) {
        RowSet rows = generatedKeyRows(result);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            throw new NonUniqueResultException("JDBC update returned more than one generated-key row.");
        }
        return Optional.of(mapper.map(rows.rows().getFirst()));
    }

    private static RowSet rows(JdbcExecutionResult result) {
        JdbcDirectResult direct = result.onlyOperation().directResults().only();
        if (!(direct instanceof JdbcRowsResult rows)) {
            throw new DataException("JDBC operation did not produce a result set");
        }
        return rows.rows();
    }

    private static RowSet generatedKeyRows(JdbcExecutionResult result) {
        return result.onlyOperation().generatedKeys()
                .orElseThrow(() -> new DataException("JDBC operation contains no generated-key result set"));
    }

    private static BigInteger integral(Number number) {
        try {
            return switch (number) {
            case BigInteger value -> value;
            case BigDecimal value -> value.toBigIntegerExact();
            case Byte value -> BigInteger.valueOf(value.longValue());
            case Short value -> BigInteger.valueOf(value.longValue());
            case Integer value -> BigInteger.valueOf(value.longValue());
            case Long value -> BigInteger.valueOf(value);
            default -> new BigDecimal(number.toString()).toBigIntegerExact();
            };
        } catch (ArithmeticException | NumberFormatException e) {
            throw new DataException("JDBC page count query returned a non-integral value: " + number, e);
        }
    }
}
