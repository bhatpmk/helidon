# Runtime and JdbcRunner

## Purpose

`JdbcRunner` is the JDBC provider component that executes JDBC operations and produces transcripts.

It is an internal architecture component unless an implementation has a specific reason to expose it.

## Core Contract

Conceptual methods:

```text
execute(JdbcOperation)
execute(JdbcPlan)
```

Rules:

- execute each operation exactly once;
- capture every observable JDBC outcome in the transcript;
- close JDBC resources reliably;
- preserve partial transcripts on failure;
- never expose JDBC resources through public APIs;
- never use Jakarta Persistence APIs.

## Connection Acquisition

The JDBC provider should work with `javax.sql.DataSource`.

Connection handling rules:

- read JDBC persistence units from application configuration under `data.persistence-units.jdbc`;
- resolve each JDBC persistence unit's `data-source` through configured sources under `data.sources`;
- get connections from the resolved `DataSource` or provider-specific connection service;
- do not close transaction-managed physical connections directly if the transaction module requires release semantics;
- prefer a narrow internal `ConnectionHandle` abstraction when different connection release policies are needed;
- do not duplicate transaction lifecycle logic from `transaction/jdbc`.

Configuration example:

```yaml
data:
  sources:
    sql:
      - name: "pokemon"
        provider.hikari:
          username: "user"
          password: "changeit"
          url: "${data.url}"
          jdbc-driver-class-name: "com.mysql.cj.jdbc.Driver"
  persistence-units:
    jdbc:
      - name: "pokemon"
        data-source: "pokemon"
        init-script: "init.sql"
```

The runtime must not log credentials from configured sources. If `init-script` is present, initialization must run before
repository operations use the persistence unit, and failures should identify the persistence unit and script without
logging secrets.

## Execution Overview

```text
resolve connection
create statement
apply statement options
apply bindings
execute
collect result sequence
collect generated keys if requested
collect OUT params if callable
collect warnings
close or release resources
return transcript
```

SQL parsing before statement creation must follow `docs/codex/sql-parsing.md`. The runtime parser rewrites bind markers and
records parameter metadata; it does not decide whether the operation is a query, update, call, batch, or script.

## Statement Execution Loop

Use JDBC `execute()` and `getMoreResults()` for operations that can produce mixed outcomes.

Conceptual loop:

```java
boolean hasResultSet = statement.execute();
int ordinal = 0;

while (true) {
    if (hasResultSet) {
        materializeCurrentResultSet();
    } else {
        long count = readUpdateCount(statement);
        if (count == -1) {
            break;
        }
        addUpdateCount(count);
    }
    hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
}
```

Rules:

- preserve JDBC result order;
- do not create an update event for `-1`;
- use large update counts when the operation requires them;
- close each result set after materialization unless streaming.

## Query Execution

Queries usually produce one `RowsEvent`.

Still use the generic result loop so the transcript can represent:

- multiple result sets;
- warnings;
- driver-specific mixed results.

If no result set is produced, reduction fails according to the declared return type.

## Update Execution

Updates usually produce one `UpdateCountEvent`.

DDL may produce no events or an explicit `NoResultEvent`.

DML returning rows may produce `RowsEvent(role = RETURNING_RESULT_SET)` depending on database and driver.

## Generated Keys

If generated keys are requested:

1. create the prepared statement with generated-key options;
2. execute the statement;
3. consume normal JDBC results;
4. call `getGeneratedKeys()`;
5. materialize keys into a generated-keys event.

Only call `getGeneratedKeys()` when requested.

## Callable Statements

For calls:

1. create `CallableStatement`;
2. bind IN parameters;
3. register OUT and INOUT parameters;
4. register cursor OUT parameters through a strategy;
5. execute;
6. consume direct result sequence;
7. materialize cursor OUT parameters as row events;
8. read scalar OUT parameters;
9. collect warnings;
10. close resources.

Some drivers require direct results to be consumed before OUT values are read. Structure the runtime to support that.

## Batch Execution

For batch:

1. create prepared statement;
2. apply generated-key mode if requested;
3. bind each batch item with generated binder code;
4. call `addBatch()`;
5. execute `executeBatch()` or `executeLargeBatch()`;
6. convert counts to batch item outcomes;
7. collect generated keys if requested;
8. return a batch event.

On `BatchUpdateException`, build a partial transcript and throw a transcript-carrying runtime exception.

## Named Parameter Rewriting

JDBC does not support named parameters directly.

The provider must rewrite:

```sql
SELECT * FROM POKEMON WHERE NAME = :name OR ALIAS = :name
```

to:

```sql
SELECT * FROM POKEMON WHERE NAME = ? OR ALIAS = ?
```

with binding order:

```text
name
name
```

Rules:

- ignore colons inside string literals;
- ignore colons inside comments;
- support repeated parameters;
- fail on missing bindings;
- optionally fail on unused bindings in strict mode.

## Materialization

For each result set, capture metadata:

- column label;
- column name;
- JDBC type;
- JDBC type name;
- nullability.

For rows:

- read values while the result set is open;
- store values by index and by label;
- handle duplicate labels deterministically;
- avoid mutable exposed collections.

## Warnings

Collect warning chains from statements and, if needed, result sets.

Warnings should not replace failures.

Do not log warnings with raw bind values unless redaction policy allows it.

## Error Handling

Wrap `SQLException` in runtime exceptions. Prefer `DataException` or a JDBC-provider-specific subclass that remains consistent with Helidon Data.

Execution exceptions should include:

- provider name;
- SQL kind;
- step reference;
- SQL state;
- vendor code;
- original cause;
- partial transcript if available;
- redacted SQL or SQL identifier when appropriate.

Reducers and generated mapping code should throw semantic runtime exceptions, not `SQLException`.

## Partial Transcript Construction

When failure occurs after some outcomes were observed:

1. preserve completed events;
2. attach failure metadata to the current step;
3. include completed prior steps;
4. throw an exception carrying the partial transcript.

For plan execution, preserve step order and include all completed steps before the failed step.

## Resource Management

Use try-with-resources where practical.

Rules:

- close result sets after materialization;
- close statements after execution unless streaming;
- close or release connections according to the configured connection policy;
- streaming results must close resources on stream close, exhaustion, and failure;
- generated repository code must not close JDBC resources.

## Logging

Allowed by default:

- provider;
- operation kind;
- step name;
- elapsed time;
- row count;
- update count;
- warning count.

Not allowed by default:

- raw bind values;
- passwords or credentials;
- generated key values if they could be sensitive.

## Runtime Tests

Use an embedded database where practical.

Test:

- select;
- scalar select;
- empty select;
- update;
- DDL;
- generated keys;
- configured source and JDBC persistence unit resolution;
- optional init script execution;
- named parameter rewriting;
- batch success;
- batch failure;
- warnings where practical;
- partial transcript on SQL exception;
- plain DataSource and transaction-aware DataSource paths.
