# Transcript Model

## Purpose

`JdbcTranscript` is the immutable representation of one JDBC operation or one JDBC plan execution.

It exists because JDBC can produce heterogeneous outcomes:

- result sets;
- update counts;
- generated keys;
- OUT and INOUT parameters;
- cursor OUT parameters;
- batch item counts;
- warnings;
- partial results before failure.

The model must not expose live JDBC objects.

## Visibility

Default transcript and event types to package-private inside `data/jdbc`.

Make them public only if an approved user-facing feature requires it, such as a raw diagnostic return type. If made public, keep them immutable and small.

## Top-Level Shape

Conceptual model:

```java
record JdbcTranscript(List<StepTranscript> steps) {
}
```

Rules:

- `steps` is never `null`.
- `steps` is defensively copied.
- a single SQL operation has exactly one step;
- a multi-operation plan has steps in execution order;
- helper methods such as `onlyStep()` and `step(String name)` should throw semantic shape exceptions, not generic runtime exceptions.

## Step Transcript

Conceptual model:

```java
record StepTranscript(
        StepRef ref,
        SqlKind kind,
        List<JdbcEvent> events,
        List<JdbcWarningInfo> warnings,
        Optional<JdbcFailure> failure) {
}
```

Rules:

- one step represents one JDBC execution;
- event order follows the order exposed by JDBC;
- warnings are captured per step;
- failure metadata is present only for partial transcripts;
- a failed step still keeps all events captured before the failure.

## Step References

Conceptual model:

```java
record StepRef(int index, Optional<String> name) {
}
```

Rules:

- `index` is zero-based and stable;
- `name` is optional for single-step operations;
- multi-step generated methods must assign non-empty unique names;
- generated code should use compile-time constants for step names.

## SQL Kind

The SQL kind records operation intent, not the first SQL keyword:

```text
QUERY
UPDATE
CALL
BATCH
SCRIPT
UNKNOWN
```

Examples:

- `@Data.Query` or a select-style generated method uses `QUERY`.
- Update-count terminals use `UPDATE`.
- Explicit `@Data.Query` DML/DDL may still use `QUERY` because the provider does not classify SQL text; JDBC execution
  can still produce `UpdateCountEvent` entries in that transcript.
- `CallableStatement` execution uses `CALL`.
- `PreparedStatement.addBatch()` execution uses `BATCH`.

## Events

Required event categories:

```text
RowsEvent
UpdateCountEvent
GeneratedKeysEvent
OutParamsEvent
BatchEvent
WarningEvent
NoResultEvent
```

The Java implementation may use sealed interfaces, records, package-private classes, or another Helidon-consistent immutable style. The model must remain extensible for vendor-specific details.

Every event should include:

- step reference;
- ordinal within the step;
- event-specific payload.

## Rows Event

Represents a materialized result set or cursor result.

Conceptual payload:

```text
step
ordinal
role
name
rowSet
```

Roles:

```text
DIRECT_RESULT_SET
RETURNING_RESULT_SET
OUT_CURSOR
GENERATED_KEYS_VIEW
```

Use rows events for:

- normal query result sets;
- result sets returned by stored procedures;
- cursor OUT parameters;
- DML returning rows.

Do not expose `ResultSet`. Materialize rows while the JDBC resource is open unless a streaming implementation explicitly owns the resource lifecycle.

## Update Count Event

Represents one JDBC update count.

Rules:

- store counts internally as `long`;
- convert to `int` only in reducers, with exact overflow checks;
- `getUpdateCount() == -1` means no more update count and must not create an event;
- batch sentinel values belong in `BatchEvent`, not `UpdateCountEvent`.

## Generated Keys Event

Represents keys returned by JDBC generated-key APIs.

Rules:

- collect keys only when requested;
- call `getGeneratedKeys()` while the statement is open;
- missing keys are not an execution failure by themselves, but reducers that require keys must fail with a semantic exception;
- generated keys may be scalar or record-shaped through generated mapping.

## OUT Parameters Event

Represents non-cursor OUT and INOUT values from a `CallableStatement`.

Conceptual payload:

```text
Map<ParamRef, OutParamValue>
```

Rules:

- preserve name and index when known;
- preserve JDBC type and optional type name;
- do not put cursor `ResultSet` values in this event;
- collect OUT values after direct result sequence consumption when the driver requires it.

## Batch Event

Represents batch execution.

Conceptual payload:

```text
items
generatedKeys
```

Batch item status:

```text
UPDATED
SUCCESS_NO_INFO
EXECUTE_FAILED
NOT_ATTEMPTED
```

Rules:

- map JDBC `Statement.SUCCESS_NO_INFO` to `SUCCESS_NO_INFO`;
- map JDBC `Statement.EXECUTE_FAILED` to `EXECUTE_FAILED`;
- use `NOT_ATTEMPTED` when a driver reports fewer results than the requested batch size after failure;
- preserve generated key rows in returned order.

## Warnings

Warnings can be stored in `StepTranscript.warnings`.

Use a separate `WarningEvent` only when relative ordering among result sets, counts, and warnings matters.

Warning metadata should include:

- SQL state;
- vendor code;
- message.

## No Result

An empty event list can represent a DDL statement with no result.

Use `NoResultEvent` only when an explicit no-result marker improves validation or diagnostics.

## RowSet

Conceptual model:

```text
columns
rows
size
isEmpty
```

Rules:

- capture metadata before row values;
- prefer JDBC column labels for name-based lookup;
- preserve indexed lookup to handle duplicate labels;
- never expose a mutable row collection;
- never keep a live `ResultSet` reference in a materialized transcript.

## Row

Rows should support:

- lookup by column label;
- lookup by one-based JDBC column index or zero-based internal index, documented consistently;
- typed conversion through the reducer or mapper layer;
- detection of missing columns.

Do not use reflection to populate records or DTOs. Generate row mapping code from compile-time metadata.

## Column Info

Capture:

- column label;
- column name;
- JDBC type;
- JDBC type name;
- nullability when available.

Use `ResultSetMetaData.getColumnLabel()` for label-based mapping.

## Failure Metadata

Partial transcripts should include lightweight failure metadata:

```text
message
sqlState
vendorCode
exceptionClass
```

Runtime exceptions can carry the original `SQLException` as cause. Immutable transcript records should avoid storing mutable exception objects.

## Immutability Rules

- Copy lists and maps before storing them.
- Do not expose mutable row maps.
- Do not retain JDBC resources in materialized transcripts.
- For streaming, bind resource lifetime to the returned stream and document that the stream must be closed.
- Avoid `null` in public-facing transcript APIs; use `Optional` internally where useful.

## Sample Shapes

Query:

```text
Step 0 QUERY
  RowsEvent ordinal=0 role=DIRECT_RESULT_SET rows=42
```

Update:

```text
Step 0 UPDATE
  UpdateCountEvent ordinal=0 count=3
```

Insert with generated keys:

```text
Step 0 UPDATE
  UpdateCountEvent ordinal=0 count=1
  GeneratedKeysEvent ordinal=1 rows=1
```

Stored procedure:

```text
Step 0 CALL
  RowsEvent ordinal=0 role=OUT_CURSOR name=rows
  OutParamsEvent ordinal=1 values=status
```

Batch:

```text
Step 0 BATCH
  BatchEvent ordinal=0 items=100 generatedKeys=100
```
