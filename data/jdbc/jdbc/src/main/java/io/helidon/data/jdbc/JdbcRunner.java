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

import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.helidon.data.DataException;

/**
 * Executes JDBC operations and turns JDBC results into detached transcript objects.
 */
final class JdbcRunner {

    private static final boolean TRACE = Boolean.getBoolean("io.helidon.data.jdbc.trace");

    private final DataSource dataSource;

    /**
     * Create a runner for the configured data source.
     *
     * @param dataSource data source used for JDBC connections
     */
    JdbcRunner(DataSource dataSource) {
        // Store the data source used by every operation this runner executes.
        this.dataSource = dataSource;

        // Print runner creation when JDBC tracing is enabled.
        trace("created runner for data source " + dataSource.getClass().getName());
    }

    /**
     * Execute a single JDBC operation and return its transcript.
     *
     * @param operation operation to execute
     * @return transcript produced by the operation
     */
    JdbcTranscript execute(JdbcOperation operation) {
        // Wrap the single operation in a plan so all execution paths use the same plan logic.
        trace("executing single operation kind=" + operation.kind());
        return execute(JdbcPlan.of(operation));
    }

    /**
     * Execute every operation in a JDBC plan and return one transcript with ordered steps.
     *
     * @param plan plan to execute
     * @return transcript containing all completed steps
     */
    JdbcTranscript execute(JdbcPlan plan) {
        // Allocate room for one transcript step per planned operation.
        trace("executing plan with operations=" + plan.operations().size());
        List<StepTranscript> steps = new ArrayList<>(plan.operations().size());

        // Execute each planned operation in order.
        for (int i = 0; i < plan.operations().size(); i++) {
            try {
                // Execute the current operation and append its step transcript.
                trace("executing plan step index=" + i + ", kind=" + plan.operations().get(i).kind());
                steps.add(executeStep(plan.operations().get(i), StepRef.create(i)));
            } catch (JdbcExecutionException e) {
                // Preserve completed steps before attaching the failed step transcript.
                trace("plan step failed at index=" + i);
                List<StepTranscript> failedSteps = new ArrayList<>(steps);

                // Add the failed step transcript carried by the lower-level exception.
                failedSteps.addAll(e.transcript().steps());

                // Rethrow with a transcript that contains both completed steps and the failed step.
                throw new JdbcExecutionException("JDBC plan execution failed.", (SQLException) e.getCause(),
                                                 new JdbcTranscript(failedSteps));
            }
        }

        // Return the transcript for the successfully completed plan.
        trace("plan completed with steps=" + steps.size());
        return new JdbcTranscript(steps);
    }

    /**
     * Execute one operation and return one transcript step.
     *
     * @param operation operation to execute
     * @param stepRef   transcript step reference
     * @return transcript step for the operation
     */
    private StepTranscript executeStep(JdbcOperation operation, StepRef stepRef) {
        // Route batch operations to the batch-specific execution path.
        trace("executing step index=" + stepRef.index() + ", kind=" + operation.kind());
        if (operation.kind() == SqlKind.BATCH) {
            return executeBatchStep(operation, stepRef);
        }

        // Route callable operations to the callable-specific execution path.
        if (operation.kind() == SqlKind.CALL) {
            return executeCallStep(operation, stepRef);
        }

        // Collect JDBC events observed during the operation.
        List<JdbcEvent> events = new ArrayList<>();

        // Collect JDBC warnings observed during the operation.
        List<JdbcWarningInfo> warnings = new ArrayList<>();

        // Parse bind markers and rewrite SQL before statement creation.
        ParsedSql parsed = NamedSqlParser.parse(operation.sql());

        // Open the connection and prepared statement for this operation.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepareStatement(connection,
                                                              parsed.sql(),
                                                              operation.options(),
                                                              operation.generatedKeys())) {
            // Apply JDBC statement options such as fetch size and query timeout.
            applyOptions(statement, operation.options(), operation.resultLimit());

            // Bind method parameters to JDBC parameter indexes.
            bind(statement, parsed, operation.parameters());

            // Execute the statement and learn whether the first outcome is a result set.
            boolean hasResultSet = statement.execute();

            // Track the event order within this step.
            int ordinal = 0;

            // Track whether generated keys have already been collected.
            boolean generatedKeysCollected = false;

            // Traverse every result set and update count returned by JDBC.
            while (true) {
                if (hasResultSet) {
                    // Read and close the current result set before moving to the next JDBC outcome.
                    try (ResultSet resultSet = statement.getResultSet()) {
                        // Copy all result set data into detached row objects.
                        RowSet rowSet = materialize(resultSet,
                                                    operation.columnSelection(),
                                                    effectiveMaxRows(operation.options(), operation.resultLimit()));

                        // Record the materialized result set as a rows event.
                        trace("step index=" + stepRef.index() + " captured result set rows=" + rowSet.size());
                        events.add(new RowsEvent(stepRef,
                                                 ordinal++,
                                                 RowsEvent.RowRole.DIRECT_RESULT_SET,
                                                 java.util.Optional.empty(),
                                                 rowSet));
                    }
                } else {
                    // Read the current update count.
                    long updateCount = updateCount(statement);

                    // A count of -1 means JDBC has no more update counts.
                    if (updateCount == -1) {
                        break;
                    }

                    // Record the update count as an event.
                    trace("step index=" + stepRef.index() + " captured update count=" + updateCount);
                    events.add(new UpdateCountEvent(stepRef, ordinal++, updateCount));
                }

                // Collect generated keys once after execution when the operation requested them.
                if (operation.generatedKeys().requested() && !generatedKeysCollected) {
                    ordinal = collectGeneratedKeys(statement,
                                                   stepRef,
                                                   events,
                                                   ordinal,
                                                   operation.columnSelection(),
                                                   effectiveMaxRows(operation.options(), operation.resultLimit()));
                    generatedKeysCollected = true;
                }

                // Move to the next JDBC outcome and close the current result set if one was open.
                hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }

            // Some drivers expose generated keys only after the result loop is complete.
            if (operation.generatedKeys().requested() && !generatedKeysCollected) {
                collectGeneratedKeys(statement,
                                     stepRef,
                                     events,
                                     ordinal,
                                     operation.columnSelection(),
                                     effectiveMaxRows(operation.options(), operation.resultLimit()));
            }

            // Copy SQL warnings after execution while the statement is still open.
            warnings.addAll(warnings(statement.getWarnings()));

            // Build the successful transcript step.
            trace("step index=" + stepRef.index() + " completed with events=" + events.size()
                          + ", warnings=" + warnings.size());
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.empty());

            // Return the completed step to the plan executor.
            return step;
        } catch (SQLException e) {
            // Build a failed transcript step with all events collected before the exception.
            trace("step index=" + stepRef.index() + " failed with SQL state=" + e.getSQLState());
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.of(failure(e)));

            // Wrap the failed step in a transcript for the exception.
            JdbcTranscript transcript = new JdbcTranscript(List.of(step));

            // Throw a runtime exception that keeps the original SQLException as cause.
            throw new JdbcExecutionException("JDBC " + operation.kind() + " execution failed.", e, transcript);
        }
    }

    /**
     * Execute a callable statement and capture scalar OUT parameter values.
     *
     * @param operation callable operation to execute
     * @param stepRef   transcript step reference
     * @return transcript step for the callable operation
     */
    private StepTranscript executeCallStep(JdbcOperation operation, StepRef stepRef) {
        // Collect callable events observed during execution.
        trace("executing callable step index=" + stepRef.index());
        List<JdbcEvent> events = new ArrayList<>();

        // Collect callable warnings observed during execution.
        List<JdbcWarningInfo> warnings = new ArrayList<>();

        // Parse bind markers and rewrite callable SQL before statement creation.
        ParsedSql parsed = NamedSqlParser.parse(operation.sql());

        // Open the connection and callable statement for this operation.
        try (Connection connection = dataSource.getConnection();
                CallableStatement statement = prepareCall(connection, parsed.sql(), operation.options())) {
            // Apply JDBC statement options such as timeout.
            applyOptions(statement, operation.options(), operation.resultLimit());

            // Register OUT parameters before execution.
            registerOutParameters(statement, operation.outParameters());

            // Bind IN and INOUT values without overwriting pure OUT positions.
            bindCallable(statement, parsed, operation.parameters(), operation.outParameters());

            // Execute the callable statement and learn whether the first outcome is a result set.
            boolean hasResultSet = statement.execute();

            // Capture scalar OUT parameter values and cursor OUT result sets in detached transcript events.
            trace("callable step index=" + stepRef.index() + " captured out params="
                          + operation.outParameters().size());
            int ordinal = collectOutParameterEvents(statement,
                                                    operation.outParameters(),
                                                    stepRef,
                                                    events,
                                                    0,
                                                    operation.columnSelection(),
                                                    effectiveMaxRows(operation.options(), operation.resultLimit()));

            // Capture direct result sets and update counts returned by the call itself.
            collectStatementResults(statement,
                                    stepRef,
                                    events,
                                    ordinal,
                                    hasResultSet,
                                    operation.columnSelection(),
                                    effectiveMaxRows(operation.options(), operation.resultLimit()));

            // Copy SQL warnings while the callable statement is still open.
            warnings.addAll(warnings(statement.getWarnings()));

            // Return the successful callable transcript step.
            return new StepTranscript(stepRef,
                                      operation.kind(),
                                      events,
                                      warnings,
                                      java.util.Optional.empty());
        } catch (SQLException e) {
            // Build a failed callable step with any events collected before the exception.
            trace("callable step index=" + stepRef.index() + " failed with SQL state=" + e.getSQLState());
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.of(failure(e)));

            // Wrap the failed step in a transcript for the exception.
            JdbcTranscript transcript = new JdbcTranscript(List.of(step));

            // Throw a runtime exception that keeps the original SQLException as cause.
            throw new JdbcExecutionException("JDBC " + operation.kind() + " execution failed.", e, transcript);
        }
    }

    /**
     * Execute a prepared statement batch and capture per-item batch outcomes.
     *
     * @param operation batch operation to execute
     * @param stepRef   transcript step reference
     * @return transcript step for the batch operation
     */
    private StepTranscript executeBatchStep(JdbcOperation operation, StepRef stepRef) {
        // Collect batch events observed during execution.
        trace("executing batch step index=" + stepRef.index()
                      + ", batch items=" + operation.batchParameters().size());
        List<JdbcEvent> events = new ArrayList<>();

        // Collect batch warnings observed during execution.
        List<JdbcWarningInfo> warnings = new ArrayList<>();

        // Parse bind markers and rewrite SQL before statement creation.
        ParsedSql parsed = NamedSqlParser.parse(operation.sql());

        // Open the connection and prepared statement for the batch.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepareStatement(connection,
                                                              parsed.sql(),
                                                              operation.options(),
                                                              GeneratedKeysRequest.none())) {
            // Apply JDBC statement options such as timeout.
            applyOptions(statement, operation.options(), operation.resultLimit());

            // Bind every batch item and add it to the JDBC batch.
            for (List<JdbcParameter> batchParameters : operation.batchParameters()) {
                // Bind the current batch item values to the prepared statement.
                bind(statement, parsed, batchParameters);

                // Add the currently bound values as one batch item.
                statement.addBatch();
            }

            // Store the driver-reported batch counts.
            long[] counts;
            try {
                // Prefer large update counts so row counts are not truncated.
                counts = executeLargeBatch(statement);
            } catch (BatchUpdateException e) {
                // Preserve partial batch counts from the driver.
                trace("batch step index=" + stepRef.index() + " failed after partial counts");
                events.add(new BatchEvent(stepRef, 0, batchItems(batchUpdateCounts(e), operation.batchParameters().size())));

                // Copy SQL warnings while the statement is still open.
                warnings.addAll(warnings(statement.getWarnings()));

                // Build a failed batch step that carries partial batch information.
                StepTranscript step = new StepTranscript(stepRef,
                                                         operation.kind(),
                                                         events,
                                                         warnings,
                                                         java.util.Optional.of(failure(e)));

                // Throw a transcript-carrying exception for the partial failure.
                throw new JdbcExecutionException("JDBC " + operation.kind() + " execution failed.",
                                                 e,
                                                 new JdbcTranscript(List.of(step)));
            }

            // Record all successful batch item outcomes.
            trace("batch step index=" + stepRef.index() + " completed with counts=" + counts.length);
            events.add(new BatchEvent(stepRef, 0, batchItems(counts, operation.batchParameters().size())));

            // Copy SQL warnings while the statement is still open.
            warnings.addAll(warnings(statement.getWarnings()));

            // Return the successful batch transcript step.
            return new StepTranscript(stepRef,
                                      operation.kind(),
                                      events,
                                      warnings,
                                      java.util.Optional.empty());
        } catch (SQLException e) {
            // Build a failed batch step with any events collected before the exception.
            trace("batch step index=" + stepRef.index() + " failed with SQL state=" + e.getSQLState());
            StepTranscript step = new StepTranscript(stepRef,
                                                     operation.kind(),
                                                     events,
                                                     warnings,
                                                     java.util.Optional.of(failure(e)));

            // Wrap the failed step in a transcript for the exception.
            JdbcTranscript transcript = new JdbcTranscript(List.of(step));

            // Throw a runtime exception that keeps the original SQLException as cause.
            throw new JdbcExecutionException("JDBC " + operation.kind() + " execution failed.", e, transcript);
        }
    }

    /**
     * Prepare a JDBC prepared statement with optional generated-key and result-set settings.
     *
     * @param connection    JDBC connection
     * @param sql           rewritten SQL text
     * @param options       statement options
     * @param generatedKeys generated-key request
     * @return prepared statement
     * @throws SQLException if the driver cannot prepare the statement
     */
    private static PreparedStatement prepareStatement(Connection connection,
                                                      String sql,
                                                      JdbcStatementOptions options,
                                                      GeneratedKeysRequest generatedKeys) throws SQLException {
        // Use ordinary prepareStatement overloads when generated keys are not requested.
        trace("preparing statement generatedKeys=" + generatedKeys.requested());
        if (!generatedKeys.requested()) {
            // Use the driver default result set shape when no shape was configured.
            if (!options.hasResultSetShape()) {
                return connection.prepareStatement(sql);
            }

            // Use configured result set type and concurrency when holdability was not configured.
            if (!options.hasResultSetHoldability()) {
                return connection.prepareStatement(sql, options.resultSetType(), options.resultSetConcurrency());
            }

            // Use the full configured result set shape.
            return connection.prepareStatement(sql,
                                               options.resultSetType(),
                                               options.resultSetConcurrency(),
                                               options.resultSetHoldability());
        }

        // Reject incompatible options before asking the JDBC driver to prepare the statement.
        if (options.hasResultSetShape()) {
            throw new DataException("Generated-key statements do not support result set shape options");
        }

        // Request generated keys by one-based column indexes when they are configured.
        if (!generatedKeys.columnIndexes().isEmpty()) {
            return connection.prepareStatement(sql, generatedKeys.columnIndexes()
                    .stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
        }

        // Request generated keys by column names when they are configured.
        if (!generatedKeys.columnNames().isEmpty()) {
            return connection.prepareStatement(sql, generatedKeys.columnNames().toArray(String[]::new));
        }

        // Request all generated keys when no column selection was configured.
        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    /**
     * Prepare a callable statement with optional result-set settings.
     *
     * @param connection JDBC connection
     * @param sql        rewritten callable SQL text
     * @param options    statement options
     * @return callable statement
     * @throws SQLException if the driver cannot prepare the call
     */
    private static CallableStatement prepareCall(Connection connection,
                                                 String sql,
                                                 JdbcStatementOptions options) throws SQLException {
        // Use the driver default result set shape when no shape was configured.
        trace("preparing callable statement");
        if (!options.hasResultSetShape()) {
            return connection.prepareCall(sql);
        }

        // Use configured result set type and concurrency when holdability was not configured.
        if (!options.hasResultSetHoldability()) {
            return connection.prepareCall(sql, options.resultSetType(), options.resultSetConcurrency());
        }

        // Use the full configured result set shape.
        return connection.prepareCall(sql,
                                      options.resultSetType(),
                                      options.resultSetConcurrency(),
                                      options.resultSetHoldability());
    }

    /**
     * Apply statement options that can be set after statement creation.
     *
     * @param statement   prepared or callable statement
     * @param options     statement options
     * @param resultLimit terminal-imposed row limit, or zero for no terminal limit
     * @throws SQLException if the driver rejects an option
     */
    private static void applyOptions(PreparedStatement statement,
                                     JdbcStatementOptions options,
                                     long resultLimit) throws SQLException {
        // Apply fetch size when the caller configured it.
        if (options.fetchSize() > 0) {
            trace("applying fetch size=" + options.fetchSize());
            statement.setFetchSize(options.fetchSize());
        }

        // Apply the smaller configured or terminal-required row limit when one exists.
        long maxRows = effectiveMaxRows(options, resultLimit);
        if (maxRows > 0) {
            trace("applying max rows=" + maxRows);
            setMaxRows(statement, maxRows);
        }

        // Apply query timeout when the caller configured it.
        if (options.queryTimeoutSeconds() > 0) {
            trace("applying query timeout seconds=" + options.queryTimeoutSeconds());
            statement.setQueryTimeout(options.queryTimeoutSeconds());
        }
    }

    /**
     * Resolve the effective maximum result-set rows for a statement execution.
     *
     * @param options     caller-configured statement options
     * @param resultLimit terminal-imposed row limit, or zero for no terminal limit
     * @return effective row limit, or zero for no limit
     */
    private static long effectiveMaxRows(JdbcStatementOptions options, long resultLimit) {
        // Use the terminal limit when the caller did not configure a statement max.
        if (options.maxRows() == 0) {
            return resultLimit;
        }

        // Use the caller's max rows when the terminal does not need a bound.
        if (resultLimit == 0) {
            return options.maxRows();
        }

        // Preserve the stricter bound when both limits are present.
        return Math.min(options.maxRows(), resultLimit);
    }

    /**
     * Set max rows using the large-count API when available.
     *
     * @param statement statement to configure
     * @param maxRows   maximum rows
     * @throws SQLException if the driver rejects the option
     */
    private static void setMaxRows(Statement statement, long maxRows) throws SQLException {
        try {
            // Prefer the large max rows API so large limits are not truncated.
            statement.setLargeMaxRows(maxRows);
        } catch (SQLFeatureNotSupportedException e) {
            // Fall back to the older int-based API when the driver does not support large max rows.
            statement.setMaxRows(maxRows >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxRows);
        }
    }

    /**
     * Execute a batch using large update counts when supported.
     *
     * @param statement statement containing batch items
     * @return batch update counts
     * @throws SQLException if batch execution fails
     */
    private static long[] executeLargeBatch(Statement statement) throws SQLException {
        try {
            // Prefer large update counts so values are not truncated.
            return statement.executeLargeBatch();
        } catch (SQLFeatureNotSupportedException e) {
            // Fall back to the older int-based API when the driver does not support large batch counts.
            int[] counts = statement.executeBatch();

            // Allocate a long array so the rest of the runtime uses one count type.
            long[] result = new long[counts.length];

            // Copy every int count into the long array.
            for (int i = 0; i < counts.length; i++) {
                result[i] = counts[i];
            }

            // Return counts normalized to long values.
            return result;
        }
    }

    /**
     * Read partial batch update counts from a batch exception.
     *
     * @param e batch update exception
     * @return partial counts normalized to long values
     */
    private static long[] batchUpdateCounts(BatchUpdateException e) {
        // Prefer large partial counts when the driver provides them.
        long[] counts = e.getLargeUpdateCounts();

        // Return large partial counts when they are available.
        if (counts.length > 0) {
            return counts;
        }

        // Fall back to int partial counts when large counts are unavailable.
        int[] intCounts = e.getUpdateCounts();

        // Allocate a long array so callers use one count type.
        long[] result = new long[intCounts.length];

        // Copy every int count into the long array.
        for (int i = 0; i < intCounts.length; i++) {
            result[i] = intCounts[i];
        }

        // Return counts normalized to long values.
        return result;
    }

    /**
     * Convert JDBC batch counts into transcript batch items.
     *
     * @param counts    driver-reported counts
     * @param batchSize number of requested batch items
     * @return transcript batch item list
     */
    private static List<BatchEvent.BatchItem> batchItems(long[] counts, int batchSize) {
        // Allocate room for one item per requested batch entry.
        List<BatchEvent.BatchItem> items = new ArrayList<>(batchSize);

        // Convert every driver-reported count into a transcript item.
        for (long count : counts) {
            items.add(batchItem(count));
        }

        // Fill missing driver results as not attempted after partial failures.
        while (items.size() < batchSize) {
            items.add(new BatchEvent.BatchItem(BatchEvent.BatchStatus.NOT_ATTEMPTED, java.util.OptionalLong.empty()));
        }

        // Return the normalized batch item list.
        return items;
    }

    /**
     * Convert one JDBC batch count into one transcript item.
     *
     * @param count JDBC batch count or sentinel
     * @return transcript batch item
     */
    private static BatchEvent.BatchItem batchItem(long count) {
        // Map the JDBC success-without-count sentinel to a transcript status.
        if (count == Statement.SUCCESS_NO_INFO) {
            return new BatchEvent.BatchItem(BatchEvent.BatchStatus.SUCCESS_NO_INFO, java.util.OptionalLong.empty());
        }

        // Map the JDBC failure sentinel to a transcript status.
        if (count == Statement.EXECUTE_FAILED) {
            return new BatchEvent.BatchItem(BatchEvent.BatchStatus.EXECUTE_FAILED, java.util.OptionalLong.empty());
        }

        // Treat any non-sentinel value as an update count.
        return new BatchEvent.BatchItem(BatchEvent.BatchStatus.UPDATED, java.util.OptionalLong.of(count));
    }

    /**
     * Collect generated keys from a prepared statement.
     *
     * @param statement prepared statement
     * @param stepRef   transcript step reference
     * @param events    event list to append to
     * @param ordinal   next event ordinal
     * @param columnSelection selected generated-key columns
     * @param resultLimit maximum generated-key rows to detach, or zero for no limit
     * @return next event ordinal
     * @throws SQLException if generated keys cannot be read
     */
    private static int collectGeneratedKeys(PreparedStatement statement,
                                            StepRef stepRef,
                                            List<JdbcEvent> events,
                                            int ordinal,
                                            JdbcColumnSelection columnSelection,
                                            long resultLimit) throws SQLException {
        // Ask the JDBC driver for generated keys.
        trace("collecting generated keys for step index=" + stepRef.index());
        ResultSet resultSet = statement.getGeneratedKeys();

        // Record an empty generated-key row set when the driver returns no result set.
        if (resultSet == null) {
            events.add(new GeneratedKeysEvent(stepRef, ordinal, new RowSet(List.of(), List.of())));
            return ordinal + 1;
        }

        // Materialize and close the generated-key result set.
        try (resultSet) {
            events.add(new GeneratedKeysEvent(stepRef, ordinal, materialize(resultSet, columnSelection, resultLimit)));
            return ordinal + 1;
        }
    }

    /**
     * Collect direct result sets and update counts from a JDBC statement.
     *
     * @param statement    statement to inspect
     * @param stepRef      transcript step reference
     * @param events       event list to append to
     * @param ordinal      next event ordinal
     * @param hasResultSet whether the first statement outcome is a result set
     * @param columnSelection selected result-set columns
     * @param resultLimit maximum rows to detach from each result set, or zero for no limit
     * @return next event ordinal
     * @throws SQLException if a result set or update count cannot be read
     */
    private static int collectStatementResults(Statement statement,
                                               StepRef stepRef,
                                               List<JdbcEvent> events,
                                               int ordinal,
                                               boolean hasResultSet,
                                               JdbcColumnSelection columnSelection,
                                               long resultLimit) throws SQLException {
        // Traverse every result set and update count returned by JDBC.
        while (true) {
            if (hasResultSet) {
                // Read and close the current result set before moving to the next JDBC outcome.
                try (ResultSet resultSet = statement.getResultSet()) {
                    // Some drivers can report a result set outcome without exposing a result set instance.
                    if (resultSet != null) {
                        // Copy all result set data into detached row objects.
                        RowSet rowSet = materialize(resultSet, columnSelection, resultLimit);

                        // Record the materialized result set as a direct rows event.
                        events.add(new RowsEvent(stepRef,
                                                 ordinal++,
                                                 RowsEvent.RowRole.DIRECT_RESULT_SET,
                                                 java.util.Optional.empty(),
                                                 rowSet));
                    }
                }
            } else {
                // Read the current update count.
                long updateCount = updateCount(statement);

                // A count of -1 means JDBC has no more update counts.
                if (updateCount == -1) {
                    break;
                }

                // Record the update count as an event.
                events.add(new UpdateCountEvent(stepRef, ordinal++, updateCount));
            }

            // Move to the next JDBC outcome and close the current result set if one was open.
            hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }

        // Return the next available ordinal for subsequent events in the same step.
        return ordinal;
    }

    /**
     * Bind operation parameters to a prepared statement.
     *
     * @param statement  prepared statement
     * @param parsed     parsed SQL metadata
     * @param parameters operation parameters
     * @throws SQLException if a parameter cannot be bound
     */
    private static void bind(PreparedStatement statement, ParsedSql parsed, List<JdbcParameter> parameters) throws SQLException {
        // Resolve SQL placeholders into ordered JDBC values.
        List<Object> values = values(parsed, parameters);

        // Bind each value to the one-based JDBC parameter index.
        trace("binding parameters count=" + values.size());
        for (int i = 0; i < values.size(); i++) {
            statement.setObject(i + 1, values.get(i));
        }
    }

    /**
     * Register callable OUT parameters with the JDBC driver.
     *
     * @param statement     callable statement
     * @param outParameters OUT parameter metadata
     * @throws SQLException if an OUT parameter cannot be registered
     */
    private static void registerOutParameters(CallableStatement statement, List<JdbcOutParameter> outParameters)
            throws SQLException {
        // Register each declared OUT parameter by JDBC index and SQL type.
        trace("registering out parameters count=" + outParameters.size());
        for (JdbcOutParameter outParameter : outParameters) {
            statement.registerOutParameter(outParameter.index(), outParameter.sqlType());
        }
    }

    /**
     * Bind callable IN and INOUT parameters according to the parsed SQL parameter mode.
     *
     * @param statement     callable statement
     * @param parsed        parsed SQL metadata
     * @param parameters    input parameters
     * @param outParameters OUT parameter metadata
     * @throws SQLException if a parameter cannot be bound
     */
    private static void bindCallable(CallableStatement statement,
                                     ParsedSql parsed,
                                     List<JdbcParameter> parameters,
                                     List<JdbcOutParameter> outParameters) throws SQLException {
        // Build a set of JDBC indexes that are registered as OUT parameters.
        Set<Integer> outIndexes = outIndexes(outParameters);

        // Dispatch binding based on the SQL placeholder style.
        trace("binding callable parameters mode=" + parsed.parameterMode());
        switch (parsed.parameterMode()) {
        case NONE -> noParameters(parameters);
        case NAMED -> bindCallableNamed(statement, parsed, parameters, outIndexes);
        case POSITIONAL -> bindCallablePositional(statement, parsed.parameterIndexes().size(), parameters, outIndexes);
        case ORDINAL -> bindCallableOrdinal(statement, parsed, parameters, outIndexes);
        }
    }

    /**
     * Bind callable parameters for SQL that uses named placeholders.
     *
     * @param statement  callable statement
     * @param parsed     parsed SQL metadata
     * @param parameters input parameters
     * @param outIndexes JDBC indexes registered as OUT parameters
     * @throws SQLException if a parameter cannot be bound
     */
    private static void bindCallableNamed(CallableStatement statement,
                                          ParsedSql parsed,
                                          List<JdbcParameter> parameters,
                                          Set<Integer> outIndexes) throws SQLException {
        // Index method parameters by name for lookup during SQL placeholder traversal.
        Map<String, JdbcParameter> byName = parameters.stream()
                .peek(parameter -> {
                    // Reject positional bindings when the SQL uses named placeholders.
                    if (parameter.name().isEmpty()) {
                        throw new DataException("Named SQL parameters require named bindings");
                    }
                })
                .collect(Collectors.toMap(JdbcParameter::name, Function.identity(), (first, second) -> first));

        // Track which named bindings were consumed by SQL placeholders.
        Set<String> used = new HashSet<>();

        // Get the ordered list of named placeholders from the parsed SQL.
        List<String> names = parsed.parameterNames();

        // Bind each SQL placeholder to its one-based JDBC index.
        for (int i = 0; i < names.size(); i++) {
            int jdbcIndex = i + 1;
            String parameterName = names.get(i);
            JdbcParameter parameter = byName.get(parameterName);

            // Allow an unbound placeholder only when that JDBC index is a pure OUT parameter.
            if (parameter == null) {
                if (outIndexes.contains(jdbcIndex)) {
                    continue;
                }
                throw new DataException("SQL parameter :" + parameterName + " has no matching method parameter binding");
            }

            // Remember that this named binding was used.
            used.add(parameterName);

            // Bind the named value at the current JDBC index.
            statement.setObject(jdbcIndex, parameter.value());
        }

        // Reject named method parameters that did not correspond to SQL placeholders.
        if (used.size() != byName.size()) {
            Set<String> unused = new HashSet<>(byName.keySet());
            unused.removeAll(used);
            throw new DataException("SQL statement has unused named parameter bindings: " + unused);
        }
    }

    /**
     * Bind callable parameters for SQL that uses positional placeholders.
     *
     * @param statement   callable statement
     * @param markerCount number of placeholders in SQL
     * @param parameters  input parameters
     * @param outIndexes  JDBC indexes registered as OUT parameters
     * @throws SQLException if a parameter cannot be bound
     */
    private static void bindCallablePositional(CallableStatement statement,
                                               int markerCount,
                                               List<JdbcParameter> parameters,
                                               Set<Integer> outIndexes) throws SQLException {
        // Reject named bindings when the SQL uses positional placeholders.
        requirePositionalBindings(parameters);

        // Track the next input parameter to bind.
        int parameterIndex = 0;

        // Visit every JDBC placeholder index in SQL order.
        for (int jdbcIndex = 1; jdbcIndex <= markerCount; jdbcIndex++) {
            // Count remaining non-OUT positions so pure OUT indexes can be skipped safely.
            int remainingNonOut = remainingNonOut(markerCount, jdbcIndex, outIndexes);

            // Skip pure OUT parameters that do not need a method parameter value.
            if (outIndexes.contains(jdbcIndex) && parameters.size() - parameterIndex <= remainingNonOut) {
                continue;
            }

            // Fail when no input parameter exists for this JDBC index.
            if (parameterIndex >= parameters.size()) {
                throw new DataException("SQL parameter at JDBC index " + jdbcIndex + " has no matching binding");
            }

            // Bind the next input parameter at the current JDBC index.
            statement.setObject(jdbcIndex, parameters.get(parameterIndex++).value());
        }

        // Reject extra method parameters that were not consumed by SQL placeholders.
        if (parameterIndex != parameters.size()) {
            throw new DataException("SQL statement has unused positional parameter bindings");
        }
    }

    /**
     * Bind callable parameters for SQL that uses ordinal placeholders.
     *
     * @param statement  callable statement
     * @param parsed     parsed SQL metadata
     * @param parameters input parameters
     * @param outIndexes JDBC indexes registered as OUT parameters
     * @throws SQLException if a parameter cannot be bound
     */
    private static void bindCallableOrdinal(CallableStatement statement,
                                            ParsedSql parsed,
                                            List<JdbcParameter> parameters,
                                            Set<Integer> outIndexes) throws SQLException {
        // Reject named bindings when the SQL uses ordinal placeholders.
        requirePositionalBindings(parameters);

        // Get the ordered list of source parameter indexes from SQL placeholders.
        List<Integer> sourceIndexes = parsed.parameterIndexes();

        // Bind each SQL placeholder to the method parameter identified by the ordinal.
        for (int i = 0; i < sourceIndexes.size(); i++) {
            int jdbcIndex = i + 1;
            int sourceIndex = sourceIndexes.get(i);

            // Allow missing source indexes only when the JDBC index is a pure OUT parameter.
            if (sourceIndex > parameters.size()) {
                if (outIndexes.contains(jdbcIndex)) {
                    continue;
                }
                throw new DataException("SQL ordinal parameter ?" + sourceIndex
                                                + " has no matching method parameter binding");
            }

            // Bind the method parameter selected by the ordinal placeholder.
            statement.setObject(jdbcIndex, parameters.get(sourceIndex - 1).value());
        }
    }

    /**
     * Build a set of JDBC OUT parameter indexes.
     *
     * @param outParameters OUT parameter metadata
     * @return set of JDBC indexes
     */
    private static Set<Integer> outIndexes(List<JdbcOutParameter> outParameters) {
        // Convert OUT parameter metadata into a lookup set.
        return outParameters.stream()
                .map(JdbcOutParameter::index)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Count non-OUT placeholders that remain after the current JDBC index.
     *
     * @param markerCount      total placeholder count
     * @param currentJdbcIndex current JDBC index
     * @param outIndexes       JDBC indexes registered as OUT parameters
     * @return remaining non-OUT placeholder count
     */
    private static int remainingNonOut(int markerCount, int currentJdbcIndex, Set<Integer> outIndexes) {
        // Start with no remaining non-OUT placeholders counted.
        int remaining = 0;

        // Scan the placeholder indexes after the current index.
        for (int i = currentJdbcIndex + 1; i <= markerCount; i++) {
            // Count only placeholders that are not pure OUT positions.
            if (!outIndexes.contains(i)) {
                remaining++;
            }
        }

        // Return the number of input values still required after the current index.
        return remaining;
    }

    /**
     * Read callable OUT parameter values into transcript events.
     *
     * @param statement     callable statement
     * @param outParameters OUT parameter metadata
     * @param stepRef       transcript step reference
     * @param events        event list to append to
     * @param ordinal       next event ordinal
     * @param columnSelection selected OUT cursor columns
     * @param resultLimit   maximum OUT cursor rows to detach, or zero for no limit
     * @return next event ordinal
     * @throws SQLException if an OUT value cannot be read
     */
    private static int collectOutParameterEvents(CallableStatement statement,
                                                 List<JdbcOutParameter> outParameters,
                                                 StepRef stepRef,
                                                 List<JdbcEvent> events,
                                                 int ordinal,
                                                 JdbcColumnSelection columnSelection,
                                                 long resultLimit) throws SQLException {
        // Preserve OUT parameter encounter order in the returned map.
        Map<String, Object> values = new LinkedHashMap<>();

        // Read every registered OUT parameter by JDBC index.
        for (JdbcOutParameter outParameter : outParameters) {
            if (outParameter.cursor()) {
                events.add(new RowsEvent(stepRef,
                                         ordinal++,
                                         RowsEvent.RowRole.OUT_CURSOR,
                                         java.util.Optional.of(outParameter.name()),
                                         outCursor(statement, outParameter, columnSelection, resultLimit)));
            } else {
                values.put(outParameter.name(), statement.getObject(outParameter.index()));
            }
        }

        // Record scalar OUT values as a single event even when no scalar OUT values were registered.
        events.add(new OutParamsEvent(stepRef, ordinal++, values));

        // Return the next available ordinal for direct result sets and update counts.
        return ordinal;
    }

    /**
     * Read one cursor OUT parameter into a detached row set.
     *
     * @param statement       callable statement
     * @param outParameter    cursor OUT parameter metadata
     * @param columnSelection selected cursor columns
     * @param resultLimit     maximum cursor rows to detach, or zero for no limit
     * @return detached cursor rows
     * @throws SQLException if the cursor cannot be read
     */
    private static RowSet outCursor(CallableStatement statement,
                                    JdbcOutParameter outParameter,
                                    JdbcColumnSelection columnSelection,
                                    long resultLimit) throws SQLException {
        // Read the cursor object from the callable statement.
        Object cursor = statement.getObject(outParameter.index());

        // A null cursor maps to an empty row set.
        if (cursor == null) {
            return new RowSet(List.of(), List.of());
        }

        // JDBC drivers normally expose cursor OUT parameters as ResultSet instances.
        if (cursor instanceof ResultSet resultSet) {
            try (resultSet) {
                return materialize(resultSet, columnSelection, resultLimit);
            }
        }

        // Treat any non-ResultSet cursor value as a driver contract failure for this API.
        throw new SQLException("Callable OUT cursor \"" + outParameter.name()
                                       + "\" returned " + cursor.getClass().getName()
                                       + " instead of java.sql.ResultSet");
    }

    /**
     * Resolve parsed SQL placeholders into JDBC values.
     *
     * @param parsed     parsed SQL metadata
     * @param parameters operation parameters
     * @return ordered JDBC parameter values
     */
    private static List<Object> values(ParsedSql parsed, List<JdbcParameter> parameters) {
        // Dispatch value resolution based on the SQL placeholder style.
        return switch (parsed.parameterMode()) {
        case NONE -> noParameters(parameters);
        case NAMED -> namedValues(parsed, parameters);
        case POSITIONAL -> positionalValues(parsed, parameters);
        case ORDINAL -> ordinalValues(parsed, parameters);
        };
    }

    /**
     * Validate that no parameters were provided for SQL without placeholders.
     *
     * @param parameters operation parameters
     * @return empty value list
     */
    private static List<Object> noParameters(List<JdbcParameter> parameters) {
        // Reject provided parameters when SQL declares no placeholders.
        if (!parameters.isEmpty()) {
            throw new DataException("SQL statement declares no parameters, but " + parameters.size()
                                            + " bindings were provided");
        }

        // Return an empty binding list for SQL without placeholders.
        return List.of();
    }

    /**
     * Resolve named SQL placeholders into ordered JDBC values.
     *
     * @param parsed     parsed SQL metadata
     * @param parameters operation parameters
     * @return ordered JDBC parameter values
     */
    private static List<Object> namedValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        // Index method parameters by name for lookup during SQL placeholder traversal.
        Map<String, JdbcParameter> byName = parameters.stream()
                .peek(parameter -> {
                    // Reject positional bindings when the SQL uses named placeholders.
                    if (parameter.name().isEmpty()) {
                        throw new DataException("Named SQL parameters require named bindings");
                    }
                })
                .collect(Collectors.toMap(JdbcParameter::name, Function.identity(), (first, second) -> first));

        // Allocate the JDBC value list in SQL placeholder order.
        List<Object> values = new ArrayList<>(parsed.parameterNames().size());

        // Track which named bindings were consumed by SQL placeholders.
        Set<String> used = new HashSet<>();

        // Resolve every named placeholder occurrence in SQL order.
        for (String parameterName : parsed.parameterNames()) {
            JdbcParameter parameter = byName.get(parameterName);

            // Fail if the SQL placeholder has no matching method parameter.
            if (parameter == null) {
                throw new DataException("SQL parameter :" + parameterName + " has no matching method parameter binding");
            }

            // Remember that this named binding was used.
            used.add(parameterName);

            // Append the value for this SQL placeholder occurrence.
            values.add(parameter.value());
        }

        // Reject named method parameters that did not correspond to SQL placeholders.
        if (used.size() != byName.size()) {
            Set<String> unused = new HashSet<>(byName.keySet());
            unused.removeAll(used);
            throw new DataException("SQL statement has unused named parameter bindings: " + unused);
        }

        // Return values ordered by JDBC placeholder position.
        return values;
    }

    /**
     * Resolve positional SQL placeholders into ordered JDBC values.
     *
     * @param parsed     parsed SQL metadata
     * @param parameters operation parameters
     * @return ordered JDBC parameter values
     */
    private static List<Object> positionalValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        // Reject named bindings when the SQL uses positional placeholders.
        requirePositionalBindings(parameters);

        // Ensure the method supplies exactly one value per positional placeholder.
        if (parameters.size() != parsed.parameterIndexes().size()) {
            throw new DataException("SQL statement declares " + parsed.parameterIndexes().size()
                                            + " positional parameters, but " + parameters.size()
                                            + " bindings were provided");
        }

        // Return method parameter values in encounter order.
        return parameters.stream()
                .map(JdbcParameter::value)
                .toList();
    }

    /**
     * Resolve ordinal SQL placeholders into ordered JDBC values.
     *
     * @param parsed     parsed SQL metadata
     * @param parameters operation parameters
     * @return ordered JDBC parameter values
     */
    private static List<Object> ordinalValues(ParsedSql parsed, List<JdbcParameter> parameters) {
        // Reject named bindings when the SQL uses ordinal placeholders.
        requirePositionalBindings(parameters);

        // Allocate one JDBC value per ordinal placeholder occurrence.
        List<Object> values = new ArrayList<>(parsed.parameterIndexes().size());

        // Resolve every ordinal placeholder to the referenced method parameter.
        for (int parameterIndex : parsed.parameterIndexes()) {
            // Fail if the ordinal placeholder references a missing method parameter.
            if (parameterIndex > parameters.size()) {
                throw new DataException("SQL ordinal parameter ?" + parameterIndex
                                                + " has no matching method parameter binding");
            }

            // Append the method parameter value selected by this ordinal placeholder.
            values.add(parameters.get(parameterIndex - 1).value());
        }

        // Return values ordered by JDBC placeholder position.
        return values;
    }

    /**
     * Ensure all supplied parameters are positional.
     *
     * @param parameters operation parameters
     */
    private static void requirePositionalBindings(List<JdbcParameter> parameters) {
        // Find the first named parameter because named parameters are invalid for positional SQL.
        parameters.stream()
                .filter(parameter -> !parameter.name().isEmpty())
                .findFirst()
                .ifPresent(parameter -> {
                    // Fail fast when a named binding appears with positional or ordinal placeholders.
                    throw new DataException("Positional SQL parameters require positional bindings");
                });
    }

    /**
     * Copy a JDBC result set into a detached row set.
     *
     * @param resultSet JDBC result set to copy
     * @return detached row set
     * @throws SQLException if metadata or row values cannot be read
     */
    private static RowSet materialize(ResultSet resultSet) throws SQLException {
        return materialize(resultSet, JdbcColumnSelection.ALL, 0);
    }

    /**
     * Copy a JDBC result set into a detached row set.
     *
     * @param resultSet       JDBC result set to copy
     * @param columnSelection columns to expose in the detached row set
     * @param rowLimit        maximum rows to detach, or zero for no limit
     * @return detached row set
     * @throws SQLException if metadata or row values cannot be read
     */
    private static RowSet materialize(ResultSet resultSet,
                                      JdbcColumnSelection columnSelection,
                                      long rowLimit) throws SQLException {
        // Read result set metadata before copying rows.
        ResultSetMetaData metaData = resultSet.getMetaData();

        // Determine how many columns each row contains.
        int columnCount = metaData.getColumnCount();

        // Allocate column metadata using the JDBC column count.
        List<ColumnInfo> sourceColumns = new ArrayList<>(columnCount);

        // Copy metadata for every one-based JDBC column index.
        for (int i = 1; i <= columnCount; i++) {
            String label = columnLabel(metaData, i);
            int type = columnType(metaData, i);
            sourceColumns.add(new ColumnInfo(label,
                                             columnName(metaData, i, label),
                                             type,
                                             columnTypeName(metaData, i, type),
                                             columnNullable(metaData, i)));
        }

        // Resolve requested source result-set columns to one-based JDBC indexes.
        int[] selectedIndexes = columnSelection.selectedIndexes(sourceColumns);

        // Allocate metadata only for the columns exposed to transcript readers.
        List<ColumnInfo> columns = new ArrayList<>(selectedIndexes.length);

        // Copy metadata for every selected column in caller-requested order.
        for (int selectedIndex : selectedIndexes) {
            columns.add(sourceColumns.get(selectedIndex - 1));
        }

        // Share one immutable row layout across every materialized row in this result set.
        RowLayout layout = new RowLayout(columns);

        // Allocate the materialized row list with a small known capacity when a terminal bounded the result.
        List<MaterializedRow> rows = rows(rowLimit);

        // Copy result set rows while the JDBC result set is open.
        while (resultSet.next()) {
            // Allocate one value slot per selected column.
            Object[] values = new Object[selectedIndexes.length];

            // Copy selected column values by their original one-based JDBC column indexes.
            for (int i = 0; i < selectedIndexes.length; i++) {
                values[i] = resultSet.getObject(selectedIndexes[i]);
            }

            // Store one detached row with shared column metadata.
            rows.add(MaterializedRow.trusted(layout, values));

            // Stop local copying after the terminal has enough rows to decide its result.
            if (rowLimit > 0 && rows.size() >= rowLimit) {
                break;
            }
        }

        // Print the materialized row and column counts when JDBC tracing is enabled.
        trace("materialized result set rows=" + rows.size() + ", columns=" + columns.size());

        // Return a detached row set that no longer depends on JDBC resources.
        return new RowSet(layout, rows);
    }

    /**
     * Create the materialized row list, using bounded capacity only for small known limits.
     *
     * @param rowLimit maximum rows to detach, or zero for no limit
     * @return mutable row list
     */
    private static List<MaterializedRow> rows(long rowLimit) {
        // Pre-size common single-result terminal paths without allocating large arrays for user-supplied maxRows values.
        if (rowLimit > 0 && rowLimit <= 64) {
            return new ArrayList<>((int) rowLimit);
        }

        // Use ordinary ArrayList growth for unbounded or large result sets.
        return new ArrayList<>();
    }

    /**
     * Read a column label, falling back to a generated label when the driver cannot provide one.
     *
     * @param metaData JDBC result set metadata
     * @param index    one-based column index
     * @return column label
     */
    private static String columnLabel(ResultSetMetaData metaData, int index) {
        try {
            // Ask the driver for the column label normally used by ResultSet lookup.
            String label = metaData.getColumnLabel(index);

            // Use a stable generated label when the driver returns no label.
            return label == null || label.isBlank() ? "column" + index : label;
        } catch (SQLException e) {
            // Some callable function result sets have incomplete metadata; keep row materialization usable.
            trace("column label metadata unavailable at index=" + index);
            return "column" + index;
        }
    }

    /**
     * Read a column name, falling back to the label when the driver cannot provide one.
     *
     * @param metaData JDBC result set metadata
     * @param index    one-based column index
     * @param label    fallback column label
     * @return column name
     */
    private static String columnName(ResultSetMetaData metaData, int index, String label) {
        try {
            // Ask the driver for the physical column name.
            String name = metaData.getColumnName(index);

            // Fall back to the label for computed expressions and driver-generated columns.
            return name == null || name.isBlank() ? label : name;
        } catch (SQLException e) {
            // Metadata failures should not prevent row values from being detached.
            trace("column name metadata unavailable at index=" + index);
            return label;
        }
    }

    /**
     * Read a column SQL type, falling back to {@link java.sql.Types#JAVA_OBJECT}.
     *
     * @param metaData JDBC result set metadata
     * @param index    one-based column index
     * @return SQL type
     */
    private static int columnType(ResultSetMetaData metaData, int index) {
        try {
            // Ask the driver for the JDBC SQL type.
            return metaData.getColumnType(index);
        } catch (SQLException e) {
            // A generic type is enough for value conversion because actual values are still copied.
            trace("column type metadata unavailable at index=" + index);
            return java.sql.Types.JAVA_OBJECT;
        }
    }

    /**
     * Read a column SQL type name, falling back to a generic type name.
     *
     * @param metaData JDBC result set metadata
     * @param index    one-based column index
     * @param type     fallback SQL type
     * @return SQL type name
     */
    private static String columnTypeName(ResultSetMetaData metaData, int index, int type) {
        try {
            // Ask the driver for the database-specific type name.
            String typeName = metaData.getColumnTypeName(index);

            // Use a stable fallback name when the driver returns no type name.
            return typeName == null || typeName.isBlank() ? columnTypeName(type) : typeName;
        } catch (SQLException e) {
            // Keep the transcript complete even when optional type-name metadata is unavailable.
            trace("column type name metadata unavailable at index=" + index);
            return columnTypeName(type);
        }
    }

    /**
     * Read column nullability, falling back to nullable when the driver cannot report it.
     *
     * @param metaData JDBC result set metadata
     * @param index    one-based column index
     * @return whether the column may contain SQL NULL
     */
    private static boolean columnNullable(ResultSetMetaData metaData, int index) {
        try {
            // Convert JDBC nullability metadata into the transcript boolean.
            return metaData.isNullable(index) != ResultSetMetaData.columnNoNulls;
        } catch (SQLException e) {
            // Nullable is the least surprising fallback for incomplete metadata.
            trace("column nullability metadata unavailable at index=" + index);
            return true;
        }
    }

    /**
     * Return a readable fallback type name for a JDBC SQL type.
     *
     * @param type SQL type
     * @return fallback type name
     */
    private static String columnTypeName(int type) {
        // Only the generic fallback type is currently needed for incomplete metadata paths.
        if (type == java.sql.Types.JAVA_OBJECT) {
            return "JAVA_OBJECT";
        }

        // Preserve the numeric type in diagnostics for uncommon fallback paths.
        return "SQL_TYPE_" + type;
    }

    /**
     * Read the current update count from a statement.
     *
     * @param statement JDBC statement
     * @return current update count or -1 when no update count is available
     * @throws SQLException if the update count cannot be read
     */
    private static long updateCount(Statement statement) throws SQLException {
        try {
            // Prefer the large update count API so counts are not truncated.
            return statement.getLargeUpdateCount();
        } catch (SQLFeatureNotSupportedException e) {
            // Fall back to the older int-based API when the driver does not support large counts.
            return statement.getUpdateCount();
        }
    }

    /**
     * Copy a JDBC warning chain into detached warning records.
     *
     * @param warning first JDBC warning
     * @return detached warning records
     */
    private static List<JdbcWarningInfo> warnings(SQLWarning warning) {
        // Allocate the warning list.
        List<JdbcWarningInfo> warnings = new ArrayList<>();

        // Start with the first warning in the JDBC warning chain.
        SQLWarning current = warning;

        // Copy every warning in the chain.
        while (current != null) {
            // Store SQL state, vendor code, and message without retaining the JDBC warning object.
            warnings.add(new JdbcWarningInfo(current.getSQLState(), current.getErrorCode(), current.getMessage()));

            // Move to the next warning in the chain.
            current = current.getNextWarning();
        }

        // Print the warning count when JDBC tracing is enabled.
        trace("captured warnings count=" + warnings.size());

        // Return detached warning records.
        return warnings;
    }

    /**
     * Copy SQL exception details into detached failure metadata.
     *
     * @param e SQL exception
     * @return detached failure metadata
     */
    private static JdbcFailure failure(SQLException e) {
        // Copy stable SQLException fields into transcript failure metadata.
        trace("captured failure sqlState=" + e.getSQLState() + ", vendorCode=" + e.getErrorCode());
        return new JdbcFailure(e.getSQLState(), e.getErrorCode(), e.getMessage());
    }

    /**
     * Print a trace message when JDBC tracing is enabled.
     *
     * @param message message to print
     */
    private static void trace(String message) {
        // Skip printing unless the user explicitly enables JDBC tracing.
        if (!TRACE) {
            return;
        }

        // Print the trace message with the class name for easier reading.
        System.out.println("[JdbcRunner] " + message);
    }
}
