# JdbcClient API

## Purpose

The JDBC provider exposes one narrow public fluent API, `JdbcClient`.

Generated declarative repositories and future handwritten imperative applications should use this same client. Generated
code must not call a separate repository-only executor layer.

## Public Surface

Public entry point:

```java
@Service.Contract
public interface JdbcClient {
    Statement execute(String sql);
}
```

Current public companion types:

- `JdbcClient.Statement`
- `JdbcParameter`
- `JdbcRow`
- `JdbcRowMapper<T>`

Keep this contract narrow. Do not expose `ResultSet`, `Statement`, `PreparedStatement`, or `CallableStatement`.

## Fluent Shape

```java
client.execute("SELECT ID, NAME FROM POKEMON WHERE ID = :id")
        .param("id", id)
        .single(row -> new PokemonRow(row.value("ID", Long.class),
                                      row.value("NAME", String.class)));
```

Supported first terminals:

- `list(JdbcRowMapper<T>)`
- `stream(JdbcRowMapper<T>)`, currently materialized before returning;
- `single(JdbcRowMapper<T>)`, strict single-row terminal;
- `singleOrNull(JdbcRowMapper<T>)`, null-allowing single-row terminal;
- `optional(JdbcRowMapper<T>)`
- `updateCount()`
- `updateCountInt()`
- `updateCountLong()`
- `updateCountBoolean()`
- `generatedKeys(JdbcRowMapper<T>)`
- `generatedKey(JdbcRowMapper<T>)`
- `optionalGeneratedKey(JdbcRowMapper<T>)`

`execute(String)` itself does not classify SQL. The selected terminal method supplies the operation intent:

- row terminals create query operations;
- update-count terminals create update/DDL operations;
- generated-key terminals create update operations with generated-key collection enabled.

Supported first statement options:

- `param(Object)`
- `param(String, Object)`
- `params(List<JdbcParameter>)`
- `fetchSize(int)`
- `generatedKeyColumns(String...)`

## Generated Code Rule

Generated repository methods should use `JdbcClient` directly:

```java
return jdbcClient.execute(SQL_LIST)
        .params(bindings)
        .list(PokemonRowMapper.INSTANCE);
```

Generated JDBC repository fields should use standard Java naming, for example `jdbcClient`.

The JDBC provider owns explicit `@Data.Query` generation under `data/jdbc/codegen`. It must not use a private generated
repository executor adapter or route explicit SQL through shared `QueryByJpqlMethodsGenerator` wrappers.

Generated declarative code should choose the terminal/reducer from the repository method return shape. In particular,
query reference returns should use first-row/null-on-empty semantics, optional returns should use first-row optional
semantics, primitive returns should fail on no row, and explicit SQL returning update counts should reduce the
update-count transcript through the scalar row-mapping path unless generated-key metadata is explicitly present.

When generated-key metadata is present, generated declarative code should use the generated-key terminals directly:

```java
return jdbcClient.execute(SQL_INSERT)
        .params(bindings)
        .generatedKeyColumns("ID")
        .generatedKey(row -> row.value(1, Long.class));
```

## Internal Operation Model

Conceptual model:

```text
JdbcOperation
  QueryOperation
  UpdateOperation
  BatchOperation
  CallOperation
  ScriptOperation

JdbcPlan
  ordered named JdbcOperation steps
```

Default these types to package-private.

Each operation should carry:

- SQL string;
- operation kind;
- bindings;
- statement options;
- generated-key request;
- OUT parameter declarations;
- batch item binders or generated item binding metadata;
- result shape selected by codegen.

## Runtime Responsibilities

`JdbcClient` terminal methods should:

- create internal operation metadata;
- use `JdbcRunner` to execute JDBC once;
- reduce the transcript into the declared return type;
- wrap JDBC failures in Helidon runtime exceptions;
- hide all JDBC resources from user code.

## Binding Model

Conceptual binding forms:

```text
named binding
indexed binding
generated bean or record binding
batch item binding
```

Rules:

- named SQL parameters must be rewritten to positional JDBC parameters before statement execution;
- a named parameter appearing multiple times binds every occurrence;
- ordinal SQL parameters may use `$1` or `?1`, and must bind according to SQL placeholder order;
- positional `?` parameters bind according to encounter order;
- named, ordinal, and positional styles must not be mixed in one SQL statement;
- unused bindings should fail in strict mode;
- missing bindings always fail;
- generated binders must be explicit code, not reflection.

The internal parsed SQL model should preserve the rewritten SQL, parameter mode, ordered named occurrences, and ordered
ordinal indexes. This model stays internal; public API should continue to accept `JdbcParameter` and mapper contracts only.

## Statement Options

Conceptual options:

```text
fetch size
query timeout
generated key mode
generated key column names
large update mode
```

Apply options before execution, except generated-key mode, which must be applied when the statement is created.

## Streaming

Lazy JDBC streaming is not required in the first implementation.

Rules:

- `stream(JdbcRowMapper<T>)` returns a stream over already materialized rows;
- JDBC resources are opened and closed inside the terminal method before it returns;
- if true lazy streaming is added later, the stream must own and close JDBC resources explicitly.

## Raw Transcript

Raw transcript returns are useful for diagnostics but expand public API.

Do not expose `JdbcTranscript` as a user return type unless it is accepted as public API. Internally, every execution should still produce a transcript.

## Terminal Boundary

Every terminal method follows this model:

```text
terminal method
  -> execute operation once
  -> produce transcript
  -> reduce transcript
  -> return value
```

Every terminal method should be equivalent to:

```java
JdbcTranscript transcript = runner.execute(operation);
return reducer.reduce(transcript);
```
