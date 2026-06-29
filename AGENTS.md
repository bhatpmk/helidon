# AGENTS.md

# Helidon Data JDBC Provider - Codex Implementation Contract

## Status

Engineering guidance for implementing a JDBC-backed Helidon Data provider.

This file is for Codex and other coding agents working in this repository. It is not end-user documentation.

## Goal

Build a Helidon Data provider under `data/jdbc` that supports the existing Helidon Data declarative repository model using plain JDBC.

The provider must:

- use JDBC APIs directly for database access;
- use Helidon codegen, especially the existing `data/codegen` provider extension model, to generate repository implementations;
- read application configuration under `data.sources` and `data.persistence-units` to resolve JDBC persistence units;
- avoid Java reflection;
- keep public API additions minimal;
- preserve the current Jakarta Persistence provider behavior in `data/jakarta-persistence`;
- integrate with `transaction/jdbc` only through explicit, minimal boundaries.

## Required Reading

Read these documents before implementing the corresponding area:

```text
AGENTS.md                             Project-wide JDBC provider contract.
DEV-GUIDELINES.md                     Helidon API, module, builder, testing, and Maven rules.
docs/codex/architecture.md            Helidon module boundaries and provider architecture.
docs/codex/transcript.md              Internal transcript and row model.
docs/codex/reducer.md                 Internal reduction and mapping rules.
docs/codex/annotations.md             Declarative API rules and annotation constraints.
docs/codex/imperative-api.md          Internal operation model used by generated code.
docs/codex/sql-parsing.md             SQL parsing, bind markers, comments, CTEs, and operation selection.
docs/codex/runtime.md                 JdbcRunner and JDBC execution rules.
docs/codex/validation.md              Compile-time and runtime validation rules.
docs/codex/stored-procedures.md       CallableStatement, OUT params, INOUT, cursors.
docs/codex/batch.md                   Batch execution, generated keys, partial failures.
docs/codex/examples.md                Expected generated-code shapes and user examples.
docs/codex/open-items.md              Deferred features and decisions discovered during implementation.
```

Use the current source as the final authority when a doc is stale. Key reference areas:

```text
data/data                              Shared Helidon Data API.
data/codegen                           Shared repository codegen model and SPI.
data/jakarta-persistence               Existing provider that must keep working unchanged.
data/sql                               SQL/DataSource support already present in Helidon Data.
transaction/jdbc                       JDBC transaction support that may need focused integration changes.
/Users/pabhat/go/src/github.com/bhatpmk/helidon-examples/examples/declarative/data-jdbc
                                      External reference samples only; not an implementation contract.
```

Before adding or changing JDBC provider code, inspect the nearby Helidon modules and follow their layout, naming, service
registration, builder/configuration, testing, and package-private visibility patterns. Prefer `data/data`,
`data/codegen`, `data/jakarta-persistence`, `data/sql`, and similar Helidon modules as concrete examples over inventing a
new structure.

## Hard Boundaries

1. Do not modify `data/jakarta-persistence` behavior while implementing the JDBC provider.
2. Do not replace, fork, or bypass `data/codegen`; add a JDBC persistence generator provider using the existing extension pattern.
3. Do not use `java.lang.reflect`, `MethodHandles`, runtime record introspection, or runtime annotation scanning to implement mapping or repository dispatch.
4. Do not put raw JDBC logic in generated repository classes.
5. Do not expose `ResultSet`, `Statement`, `PreparedStatement`, or `CallableStatement` in public APIs.
6. Do not add public API classes unless generated code, user-declared signatures, or Helidon service integration genuinely require them.
7. Keep generated repository implementations package-private, service-registry managed, and consistent with the Jakarta generator style.
8. Keep JDBC runtime code in `data/jdbc`; transaction helpers belong in `transaction/jdbc`.
9. Avoid a dependency from `data/jdbc` to `transaction/jdbc` unless the module graph has been checked and approved. Prefer accepting ordinary `DataSource` instances.
10. Every JDBC execution path must produce an internal transcript or a transcript-carrying exception.

## Provider Shape

The JDBC provider should follow this runtime flow:

```text
Helidon Data repository method
  -> generated repository implementation
  -> JdbcClient
  -> JdbcOperation or JdbcPlan
  -> JdbcRunner
  -> JdbcTranscript
  -> JdbcReducer<T> or generated reduction code
  -> user return value
```

The terms `JdbcOperation`, `JdbcPlan`, `JdbcRunner`, `JdbcTranscript`, and `JdbcReducer<T>` describe the architecture. They do not automatically imply public Java APIs. Default them to package-private or module-internal types unless a document explicitly requires public access.

## Codegen Requirements

Use `data/codegen` as the reference pattern.

Follow the style and class layout already used by `data/codegen` and `data/jakarta-persistence/codegen` unless the JDBC
provider has a concrete reason to differ.

The JDBC codegen module should:

- provide `io.helidon.data.codegen.common.spi.PersistenceGeneratorProvider`;
- use provider name `jdbc`;
- generate repository classes named consistently with existing providers, for example `RepositoryName__Jdbc`;
- generate package-private repository implementation classes;
- inject `JdbcClient`, using standard Java naming such as a `jdbcClient` field and constructor parameter;
- emit explicit row mapping, parameter binding, and reducer selection code from compile-time type metadata;
- keep mapper APIs simple and convention-first; support an approved declarative mapper or minimal explicit mapping metadata
  path so users are not forced to alias SQL columns to Java field, parameter, or record component names;
- support generated relationship reducers for dotted SQL labels such as `phones.tags.name`, where collection paths require
  aggregating rows into object graphs instead of mapping each row independently;
- fail compilation for unsupported signatures instead of deferring obvious errors to runtime.

Generated code may build operation objects or call `JdbcClient` fluent terminal methods. It must not open connections, prepare statements, loop over result sets, inspect `SQLException`, or use reflection.

## Public API Policy

Helidon treats public classes and methods as maintained API. Before adding public API:

1. Check whether generated code can use an existing public API.
2. Check whether a package-private implementation plus a single public service contract is enough.
3. Keep public contracts narrow and provider-oriented.
4. Prefer existing `io.helidon.data.Data` annotations when possible.
5. Add new `Data` annotations only when the declarative API cannot express the operation cleanly without them.
6. Do not make transcript, reducer, row, or operation types public only for implementation convenience.

If an advanced user-facing diagnostic return such as `JdbcTranscript` is approved, keep it immutable and small.

## Declarative API Policy

Existing Helidon Data declarative APIs must continue to work. Today, `@Data.Query` is provider-specific query text: JPQL for the Jakarta provider and SQL for the JDBC provider.

For JDBC:

- `@Data.Provider("jdbc")` or provider configuration should select the JDBC provider.
- JDBC persistence units must be read from application configuration under `data.persistence-units.jdbc`.
- JDBC persistence units must resolve their configured `data-source` through `data.sources`.
- Repository method signatures decide return shaping where unambiguous.
- `@Data.Query` is the JDBC declarative annotation for ordinary SQL, including SELECT, DML, and DDL.
- JDBC codegen must not classify explicit `@Data.Query` SQL by the first executable keyword. Generated explicit SQL uses
  row-shaped terminals, and the runtime reducer maps row results or update-count-only transcripts to the declared return
  type.
- JDBC explicit `@Data.Query` methods are generated directly under `data/jdbc/codegen`; they must not route through the
  shared query-method generator or a private repository executor adapter. `void @Data.Query` uses an update-count
  terminal and ignores the count.
- New operation annotations such as batch, call, or script are allowed only if they are accepted as shared Helidon Data API additions and are validated at compile time.
- Update-like operations return affected row counts by default. Generated keys are returned only when an approved generated-key
  annotation or operation metadata explicitly requests them.
- Do not add a stored-procedure-creation annotation. Stored procedure creation is DDL.

Configuration example:

```yaml
data:
  url: "jdbc:mysql://localhost:3306/pokemons"
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

The JDBC provider should treat `data.persistence-units.jdbc[*].name` as the persistence unit name, `data-source` as a
reference to a configured source under `data.sources`, and `init-script` as an optional initialization script for that
persistence unit.

The external samples under `/Users/pabhat/go/src/github.com/bhatpmk/helidon-examples/examples/declarative/data-jdbc`
are reference material only. They must not drive the provider design, public API, validation rules, or implementation
order. Do not treat their annotation names or use cases as requirements. Annotations such as `@Data.Map`,
`@Data.Mapper`, `@Data.MapWith`, `@Data.ReduceWith`, `@Data.Key`, and related mapper contracts may be invalid, obsolete,
or removed from the final design.

## Runtime Invariants

1. `JdbcRunner` is the only JDBC runtime component that directly executes statements.
2. A single SQL operation produces one transcript step.
3. A multi-operation method produces a plan with multiple named steps.
4. Reducers and generated mapping code read transcripts; they do not execute SQL.
5. Reducers must not throw `SQLException`.
6. JDBC exceptions must be wrapped in Helidon runtime exceptions, preferably `DataException` or a JDBC-provider-specific subclass.
7. Partial failures should carry a partial transcript whenever possible.
8. Transcript objects returned from any API must be immutable.
9. Sensitive bind values must not be logged by default.
10. Row data must be detached from live JDBC resources unless a streaming API explicitly owns and closes those resources.

## Required Transcript Events

The transcript model must represent at least:

- rows from result sets;
- update counts;
- generated keys;
- OUT and INOUT parameters;
- batch item outcomes;
- warnings;
- explicit no-result outcomes when useful;
- failure metadata for partial transcripts.

The model must remain open to vendor-specific data without making the common path vendor-specific.

## Transaction Boundary

`transaction/jdbc` is the JDBC transaction support module added for this work. It may need focused changes so JDBC Data can participate in Helidon transactions.

Use these rules:

- `data/jdbc` should work with a plain `javax.sql.DataSource`.
- configured JDBC persistence units must resolve to a `DataSource` before repository execution.
- transaction participation should happen through a transaction-aware `DataSource`, connection resolver, or service boundary.
- do not duplicate transaction lifecycle code in `data/jdbc`;
- do not make generated repository code transaction-aware;
- tests must cover both plain JDBC use and transaction-aware `DataSource` use when the integration exists.

## Implementation Order

Recommended order:

1. Add the `data/jdbc` Maven/module skeleton and wire it into the reactor only when implementation starts.
2. Add minimal JDBC runtime services, keeping public API small.
3. Implement immutable internal transcript and row materialization.
4. Implement internal reducers or generated reduction helpers.
5. Implement `JdbcRunner` for query and update.
6. Implement JDBC provider configuration for `data.sources` and `data.persistence-units.jdbc`.
7. Add a JDBC `PersistenceGeneratorProvider` using `data/codegen` patterns.
8. Support existing repository methods and `@Data.Query` with SQL.
9. Add generated keys, batch, stored procedures, and multi-step plans in separate increments.
10. Integrate with `transaction/jdbc` only after the basic plain-DataSource path works.
11. Add tests at transcript, runtime, codegen, and repository levels.

## Testing Expectations

Every feature needs tests at the right layer:

- unit tests for SQL parameter parsing, transcript reducers, and row mapping;
- runtime tests using an embedded database where practical;
- configuration tests for `data.sources.sql` and `data.persistence-units.jdbc`;
- codegen tests for generated source shape and diagnostics;
- repository-level tests for user-visible behavior;
- transaction integration tests when using `transaction/jdbc`.

Tests should cover:

- empty, one-row, and multi-row result sets;
- scalar query returns;
- update counts and large update counts;
- generated keys;
- batch success and partial failure;
- stored procedure OUT parameters where the test database supports them;
- cursor OUT parameters when a supported database/driver is available;
- multiple result sets and interleaved update counts where practical;
- configured persistence unit resolution by name and default selection;
- optional persistence unit initialization script handling;
- provider selection so JDBC and Jakarta providers do not interfere.

Do not run the external `helidon-examples/examples/declarative/data-jdbc` samples for every JDBC provider change. They may
be checked manually as reference applications after the feature stabilizes, but they are not part of the normal validation
contract.

## Naming Guidance

Prefer names that reflect the JDBC provider architecture:

```text
JdbcClient
JdbcOperation
JdbcPlan
JdbcRunner
JdbcTranscript
StepTranscript
JdbcEvent
RowsEvent
UpdateCountEvent
GeneratedKeysEvent
OutParamsEvent
BatchEvent
JdbcReducer
```

Names are architecture guidance, not a mandate to create public types.
