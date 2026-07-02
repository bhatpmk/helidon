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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.helidon.data.DataException;
import io.helidon.data.PageRequest;

/**
 * Validates SQL-level pagination and supplies the reserved bindings consumed by repository-provided SQL.
 * <p>
 * Pagination syntax is database specific, so the JDBC provider does not append a limit or offset clause. The SQL must
 * contain {@value #SIZE_PARAMETER}. Offset pagination must also contain {@value #OFFSET_PARAMETER}. A query without the
 * offset marker is treated as keyset pagination: application parameters provide the cursor predicate and the page
 * request contributes only the requested size.
 */
final class JdbcPagination {

    static final String OFFSET_PARAMETER = "__helidon_page_offset";
    static final String SIZE_PARAMETER = "__helidon_page_size";

    private JdbcPagination() {
    }

    /**
     * Validate a slice request and add its SQL pagination bindings.
     *
     * @param sql        repository-provided SQL
     * @param parameters application parameter bindings
     * @param request    pagination request
     * @return immutable parameters including SQL pagination bindings
     */
    static List<JdbcParameter> sliceParameters(String sql,
                                               List<JdbcParameter> parameters,
                                               PageRequest request) {
        Request validated = validate(sql, parameters, request, false);
        return validated.parameters();
    }

    /**
     * Validate an offset page request and add its SQL pagination bindings.
     *
     * @param sql        repository-provided page SQL
     * @param countSql   repository-provided count SQL
     * @param parameters application parameter bindings shared by both statements
     * @param request    pagination request
     * @return immutable parameters including SQL pagination bindings for the page statement
     */
    static PageParameters pageParameters(String sql,
                                         String countSql,
                                         List<JdbcParameter> parameters,
                                         PageRequest request) {
        if (countSql == null || countSql.isBlank()) {
            throw new IllegalArgumentException("Page count SQL must not be blank");
        }

        Request validated = validate(sql, parameters, request, true);
        ParsedSql parsedCount = NamedSqlParser.parse(countSql);
        Set<String> expected = applicationParameterNames(parameters);
        Set<String> actual = parameterNames(parsedCount, "Page count SQL");
        if (actual.contains(OFFSET_PARAMETER) || actual.contains(SIZE_PARAMETER)) {
            throw new DataException("Page count SQL must not contain JDBC pagination parameters");
        }
        if (!actual.equals(expected)) {
            throw new DataException("Page SQL and count SQL must use the same application parameters; expected "
                                            + expected + " but count SQL uses " + actual);
        }
        return new PageParameters(validated.parameters(), List.copyOf(parameters), validated.offset());
    }

    /**
     * Validate one repository pagination request before JDBC resources are acquired.
     */
    private static Request validate(String sql,
                                    List<JdbcParameter> parameters,
                                    PageRequest request,
                                    boolean requireOffset) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Page SQL must not be blank");
        }
        if (request == null) {
            throw new NullPointerException("Page request must not be null");
        }
        if (request.page() < 0) {
            throw new IllegalArgumentException("Page number must not be negative: " + request.page());
        }
        if (request.size() <= 0) {
            throw new IllegalArgumentException("Page size must be positive: " + request.size());
        }

        ParsedSql parsed = NamedSqlParser.parse(sql);
        Set<String> sqlNames = parameterNames(parsed, "Page SQL");
        requireSingleMarker(parsed, SIZE_PARAMETER);
        long offset = offset(request);
        boolean hasOffset = sqlNames.contains(OFFSET_PARAMETER);
        if (hasOffset) {
            requireSingleMarker(parsed, OFFSET_PARAMETER);
        } else if (requireOffset) {
            throw new DataException("Page SQL must contain the offset parameter :" + OFFSET_PARAMETER);
        } else if (request.page() != 0) {
            throw new IllegalArgumentException("Keyset pagination requires page number 0; the cursor is supplied "
                                                       + "through explicit SQL parameters");
        }

        Set<String> expected = applicationParameterNames(parameters);
        Set<String> actual = new HashSet<>(sqlNames);
        actual.remove(OFFSET_PARAMETER);
        actual.remove(SIZE_PARAMETER);
        if (!actual.equals(expected)) {
            throw new DataException("Page SQL parameters do not match application bindings; expected "
                                            + expected + " but SQL uses " + actual);
        }

        List<JdbcParameter> result = new ArrayList<>(parameters.size() + (hasOffset ? 2 : 1));
        result.addAll(parameters);
        if (hasOffset) {
            result.add(JdbcParameter.create(OFFSET_PARAMETER, offset));
        }
        result.add(JdbcParameter.create(SIZE_PARAMETER, request.size()));
        return new Request(List.copyOf(result), offset);
    }

    private static Set<String> parameterNames(ParsedSql parsed, String description) {
        return switch (parsed.parameterMode()) {
        case NONE -> Set.of();
        case NAMED -> new LinkedHashSet<>(parsed.parameterNames());
        case POSITIONAL, ORDINAL -> throw new DataException(description
                                                                    + " must use named parameters so the provider can "
                                                                    + "bind SQL-level pagination values");
        };
    }

    private static Set<String> applicationParameterNames(List<JdbcParameter> parameters) {
        Set<String> names = new LinkedHashSet<>();
        for (JdbcParameter parameter : parameters) {
            if (parameter.name().isEmpty()) {
                throw new DataException("Paginated SQL requires named application parameter bindings");
            }
            String name = parameter.name();
            if (name.equals(OFFSET_PARAMETER) || name.equals(SIZE_PARAMETER)) {
                throw new DataException("Application parameters must not use reserved JDBC pagination name :" + name);
            }
            if (!names.add(name)) {
                throw new DataException("Duplicate application parameter binding: " + name);
            }
        }
        return names;
    }

    private static void requireSingleMarker(ParsedSql parsed, String name) {
        long count = parsed.parameterNames().stream().filter(name::equals).count();
        if (count != 1) {
            throw new DataException("Page SQL must contain exactly one :" + name + " parameter, but found " + count);
        }
    }

    private static long offset(PageRequest request) {
        try {
            return Math.multiplyExact((long) request.page(), (long) request.size());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Page offset exceeds the supported long range", e);
        }
    }

    record PageParameters(List<JdbcParameter> pageParameters,
                          List<JdbcParameter> countParameters,
                          long offset) {
    }

    private record Request(List<JdbcParameter> parameters, long offset) {
    }
}
