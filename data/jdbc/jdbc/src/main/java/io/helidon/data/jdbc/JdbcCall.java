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

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable JDBC stored-procedure or function parameter layout.
 * <p>
 * A layout describes every positional marker without carrying input values or JDBC resources. Imperative applications
 * and generated repositories supply input values through {@link JdbcClient.Statement#bind(int, Object)} and pass the
 * same reusable layout to a {@code call} terminal. The provider validates the layout before acquiring a connection.
 */
public final class JdbcCall {
    private final List<Parameter> parameters;

    private JdbcCall(List<Parameter> parameters) {
        this.parameters = List.copyOf(parameters);
    }

    /**
     * Creates a mutable builder for a callable parameter layout.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    List<Parameter> parameters() {
        return parameters;
    }

    boolean hasOutputs() {
        return parameters.stream().anyMatch(Parameter::output);
    }

    boolean hasScalarOutputs() {
        return parameters.stream().anyMatch(parameter -> parameter.output() && !parameter.cursor());
    }

    boolean hasCursorOutputs() {
        return parameters.stream().anyMatch(Parameter::cursor);
    }

    void validate(JdbcOperation.Bind[] binds) {
        if (parameters.size() != binds.length) {
            throw new IllegalStateException("JDBC call layout must describe all " + binds.length
                                                    + " parameter positions");
        }
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            int expected = i + 1;
            if (parameter.index() != expected) {
                throw new IllegalStateException("JDBC call layout is missing parameter position " + expected);
            }
            boolean bound = binds[i] != null;
            if (parameter.input() != bound) {
                String expectation = parameter.input() ? "requires an input bind" : "is output-only";
                throw new IllegalStateException("JDBC call parameter " + expected + " " + expectation);
            }
        }
    }

    /**
     * Mutable builder for an immutable {@link JdbcCall} layout.
     * <p>
     * Every JDBC position must be declared exactly once. {@link #build()} sorts declarations by position and captures
     * an immutable snapshot, so the builder may be reused without changing an existing layout.
     */
    public static final class Builder {
        private final List<Parameter> parameters = new ArrayList<>();
        private final Set<Integer> positions = new HashSet<>();
        private final Set<String> outputNames = new HashSet<>();
        private boolean functionReturn;

        private Builder() {
        }

        /**
         * Declares an input whose JDBC type is inferred by the driver.
         *
         * @param index one-based JDBC position
         * @return this builder
         */
        public Builder in(int index) {
            return add(new Parameter(index,
                                     Direction.IN,
                                     "",
                                     Jdbc.INFERRED_TYPE,
                                     Void.class,
                                     ""));
        }

        /**
         * Declares an input with an explicit JDBC type code.
         *
         * @param index one-based JDBC position
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @return this builder
         */
        public Builder in(int index, int jdbcType) {
            inferredTypeNotAllowed(jdbcType, "Explicit JDBC input type");
            return add(new Parameter(index, Direction.IN, "", jdbcType, Void.class, ""));
        }

        /**
         * Declares an input/output parameter.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical output name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar output type
         * @return this builder
         */
        public Builder inOut(int index, String name, int jdbcType, Class<?> javaType) {
            return add(output(index, Direction.INOUT, name, jdbcType, javaType, ""));
        }

        /**
         * Declares a named-database-type input/output parameter.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical output name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar output type
         * @param typeName non-blank database type name used for output registration
         * @return this builder
         */
        public Builder inOut(int index,
                             String name,
                             int jdbcType,
                             Class<?> javaType,
                             String typeName) {
            return add(output(index,
                              Direction.INOUT,
                              name,
                              jdbcType,
                              javaType,
                              validateTypeName(typeName)));
        }

        /**
         * Declares a scalar output parameter.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical output name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar output type
         * @return this builder
         */
        public Builder out(int index, String name, int jdbcType, Class<?> javaType) {
            return add(output(index, Direction.OUT, name, jdbcType, javaType, ""));
        }

        /**
         * Declares a named-database-type scalar output parameter.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical output name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar output type
         * @param typeName non-blank database type name used for output registration
         * @return this builder
         */
        public Builder out(int index,
                           String name,
                           int jdbcType,
                           Class<?> javaType,
                           String typeName) {
            return add(output(index,
                              Direction.OUT,
                              name,
                              jdbcType,
                              javaType,
                              validateTypeName(typeName)));
        }

        /**
         * Declares a standard {@link Types#REF_CURSOR} output.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical cursor name
         * @return this builder
         */
        public Builder cursor(int index, String name) {
            return cursorParameter(index, name, Types.REF_CURSOR, "");
        }

        /**
         * Declares a cursor output using an explicit JDBC type code.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical cursor name
         * @param jdbcType {@link Types#REF_CURSOR} or a vendor cursor type code
         * @return this builder
         */
        public Builder cursor(int index, String name, int jdbcType) {
            return cursorParameter(index, name, jdbcType, "");
        }

        /**
         * Declares a cursor output using an explicit JDBC type code and database type name.
         *
         * @param index one-based JDBC position
         * @param name non-blank logical cursor name
         * @param jdbcType {@link Types#REF_CURSOR} or a vendor cursor type code
         * @param typeName non-blank database type name used for output registration
         * @return this builder
         */
        public Builder cursor(int index, String name, int jdbcType, String typeName) {
            return cursorParameter(index, name, jdbcType, validateTypeName(typeName));
        }

        private Builder cursorParameter(int index, String name, int jdbcType, String typeName) {
            inferredTypeNotAllowed(jdbcType, "Cursor JDBC type");
            return add(new Parameter(validateIndex(index),
                                     Direction.CURSOR,
                                     validateName(name),
                                     jdbcType,
                                     Void.class,
                                     typeName));
        }

        /**
         * Declares a scalar function return at JDBC position {@code 1}.
         *
         * @param name non-blank logical return name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar return type
         * @return this builder
         */
        public Builder returns(String name, int jdbcType, Class<?> javaType) {
            return returnsParameter(name, jdbcType, javaType, "");
        }

        /**
         * Declares a named-database-type scalar function return at JDBC position {@code 1}.
         *
         * @param name non-blank logical return name
         * @param jdbcType {@link Types} or vendor JDBC type code
         * @param javaType supported scalar return type
         * @param typeName non-blank database type name used for output registration
         * @return this builder
         */
        public Builder returns(String name, int jdbcType, Class<?> javaType, String typeName) {
            return returnsParameter(name, jdbcType, javaType, validateTypeName(typeName));
        }

        private Builder returnsParameter(String name, int jdbcType, Class<?> javaType, String typeName) {
            if (functionReturn) {
                throw new IllegalArgumentException("A JDBC call permits only one function return");
            }
            Parameter parameter = output(1, Direction.RETURN, name, jdbcType, javaType, typeName);
            add(parameter);
            functionReturn = true;
            return this;
        }

        /**
         * Builds an immutable parameter layout.
         *
         * @return immutable call layout
         */
        public JdbcCall build() {
            List<Parameter> snapshot = new ArrayList<>(parameters);
            snapshot.sort((left, right) -> Integer.compare(left.index(), right.index()));
            return new JdbcCall(snapshot);
        }

        private Builder add(Parameter parameter) {
            if (positions.contains(parameter.index())) {
                throw new IllegalArgumentException("Duplicate JDBC call parameter position: " + parameter.index());
            }
            if (parameter.output() && outputNames.contains(parameter.name())) {
                throw new IllegalArgumentException("Duplicate JDBC call output name: " + parameter.name());
            }
            positions.add(parameter.index());
            if (parameter.output()) {
                outputNames.add(parameter.name());
            }
            parameters.add(parameter);
            return this;
        }

        private static Parameter output(int index,
                                        Direction direction,
                                        String name,
                                        int jdbcType,
                                        Class<?> javaType,
                                        String typeName) {
            inferredTypeNotAllowed(jdbcType, "Output JDBC type");
            Class<?> outputType = Objects.requireNonNull(javaType, "JDBC call output Java type must not be null");
            if (outputType == Void.class || outputType == void.class || !JdbcRow.supportedScalar(outputType)) {
                throw new IllegalArgumentException("Unsupported JDBC call output Java type: "
                                                           + outputType.getTypeName());
            }
            return new Parameter(validateIndex(index),
                                 direction,
                                 validateName(name),
                                 jdbcType,
                                 outputType,
                                 validateOptionalTypeName(typeName));
        }

        private static int validateIndex(int index) {
            if (index < 1) {
                throw new IllegalArgumentException("JDBC call parameter index must be positive: " + index);
            }
            return index;
        }

        private static String validateName(String name) {
            Objects.requireNonNull(name, "JDBC call output name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("JDBC call output name must not be blank");
            }
            return name;
        }

        private static String validateOptionalTypeName(String typeName) {
            Objects.requireNonNull(typeName, "JDBC call database type name must not be null");
            if (!typeName.isEmpty() && typeName.isBlank()) {
                throw new IllegalArgumentException("JDBC call database type name must not be blank");
            }
            return typeName;
        }

        private static String validateTypeName(String typeName) {
            Objects.requireNonNull(typeName, "JDBC call database type name must not be null");
            if (typeName.isBlank()) {
                throw new IllegalArgumentException("JDBC call database type name must not be blank");
            }
            return typeName;
        }

        private static void inferredTypeNotAllowed(int jdbcType, String description) {
            if (jdbcType == Jdbc.INFERRED_TYPE) {
                throw new IllegalArgumentException(description + " must be explicit");
            }
        }
    }

    enum Direction {
        IN,
        OUT,
        INOUT,
        CURSOR,
        RETURN
    }

    record Parameter(int index,
                     Direction direction,
                     String name,
                     int jdbcType,
                     Class<?> javaType,
                     String typeName) {
        Parameter {
            Builder.validateIndex(index);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(name);
            Objects.requireNonNull(javaType);
            Objects.requireNonNull(typeName);
        }

        boolean input() {
            return direction == Direction.IN || direction == Direction.INOUT;
        }

        boolean output() {
            return direction != Direction.IN;
        }

        boolean cursor() {
            return direction == Direction.CURSOR;
        }
    }
}
