# JDBC Provider Open Items

## Purpose

This file tracks deferred JDBC provider work discovered during Codex implementation sessions.

Use this as a backlog for future Codex sessions. It is not end-user documentation and it is not a substitute for the
architecture documents. Each item should record the current state, pending decisions, constraints, and acceptance
criteria so implementation can continue without relying on chat history.

## Rules For Updating

- Add one item per deferred feature or design decision.
- Keep status current: `identified`, `designed`, `in-progress`, `implemented`, or `deferred`.
- Link related source and design files.
- Do not remove an item just because partial code exists; update the current state and remaining acceptance criteria.
- Keep public API decisions explicit.

## JDBC-REDUCERS-001: Client-Provided Reducers

Status: identified, not implemented

Why deferred:
Custom reducers require public API decisions. The first implementation keeps reducers package-private and chooses built-in
reduction from repository method return types.

Current implementation:

- `JdbcReducers` is package-private.
- `JdbcTranscript` and event types are package-private.
- Generated `@Data.Query` code calls `JdbcClient.execute(...).list(...)`, `single(...)`, `singleOrNull(...)`,
  or `stream(...)`. Explicit SQL that produces update counts is reduced through the same row-mapping path using a
  synthetic `updateCount` row.
- Repository interfaces cannot currently select a custom reducer.

Needed decisions:

- Should there be a public `JdbcReducer<T>` contract?
- Should custom reducers receive public `JdbcTranscript`, or a smaller read-only public result view?
- Should reducer instances be injected from the Helidon Service Registry, directly constructed by generated code, or both?
- What annotation selects a reducer, for example `@Data.UseReducer`?
- Should the reducer annotation live in shared `io.helidon.data.Data`, or in a JDBC-specific API?

Constraints:

- Do not use Java reflection or runtime annotation scanning.
- Reducers must not execute SQL.
- Reducers must not use JDBC APIs or own JDBC resources.
- Reducers must not throw `SQLException`.
- Codegen must validate reducer result type compatibility where possible.
- Generated repository code must not contain raw JDBC logic.

Likely design:

- Add a minimal public reducer interface only after the result-view API is approved.
- Codegen reads reducer selection at compile time.
- Generated code injects reducer services where possible and emits direct calls to `reduce(...)`.
- For non-service reducers, generated direct construction is acceptable only if the reducer has an accessible no-arg
  constructor and this policy is approved.

Acceptance criteria:

- A repository method can select a custom reducer without reflection.
- Generated code delegates transcript/result-view reduction to the reducer.
- Compile-time validation rejects incompatible reducer result types.
- Runtime preserves transcript-carrying exceptions on JDBC failure.
- Tests cover rows, scalar, update count, multi-result transcripts, and multi-step transcripts.

Related files:

- `docs/codex/reducer.md`
- `docs/codex/annotations.md`
- `docs/codex/transcript.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcReducers.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcTranscript.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-MAPPERS-001: Declarative Row Mappers And Explicit Column Mapping

Status: identified, not implemented

Why deferred:
The first implementation maps scalar results from column 1 and record projections from column labels matching record
component names. That is useful for simple SQL, but it is too brittle for production declarative repositories because
applications should not be forced to alias every selected column to Java component names.

Current implementation:

- Public imperative API accepts `JdbcRowMapper<T>` at runtime through `JdbcClient` terminals.
- Generated declarative code currently emits inline mapper lambdas.
- Generated record mapping reads `row.value("<componentName>", <ComponentType>.class)`.
- Repository interfaces cannot currently select a mapper service, static mapper method, or explicit column-to-component
  mapping metadata.

Needed decisions:

- Whether one mapper selector annotation is needed for custom `JdbcRowMapper<T>` services, and whether it belongs in shared
  `Data` or a JDBC-specific API.
- Whether a unique mapper service can be inferred by return element type before requiring a mapper selector annotation.
- Whether one column-name override annotation is enough for mismatched SQL labels and Java names.
- How generated constructor/factory selection should work from normal compile-time type metadata without mapper-specific
  annotations.
- Explicit column mapping model for records and DTOs: target member/parameter, source column label/name, conversion type,
  and null policy.
- Whether mapping metadata belongs on target members/parameters first, avoiding verbose repository-method mapping arrays.
- How mapper selection composes with reducers for multi-row aggregation and multi-result transcripts.

Constraints:

- Do not use Java reflection, runtime annotation scanning, or runtime record introspection.
- Do not expose `ResultSet` to user mappers used by declarative repositories.
- Generated code must validate mapper result type compatibility with the repository method return type.
- Explicit mapping metadata must be read by codegen and emitted as direct Java calls.
- Keep the default label-based record mapping for simple cases, but do not make label matching the only supported model.
- Keep mapper API surface small. Do not add separate public annotations for every mapper variant when convention,
  component-level column names, dotted labels, or a mapper selector can handle the use case.

Likely design:

- Keep `JdbcRowMapper<T>` as the runtime mapper contract for one materialized row.
- Prefer automatic mapper service selection by return element type when exactly one compatible mapper exists.
- Add compile-time declarative mapper selection only as an escape hatch so generated repositories can inject or call an
  approved mapper without reflection.
- Add a minimal explicit column-name override so generated code can construct records or DTOs using configured column
  labels instead of assuming SQL labels match Java component names.
- Prefer conventions inspired by mature JDBC libraries: constructor/bean-style mapping, optional prefixes/dotted labels,
  and a custom mapper escape hatch, but implemented with Helidon codegen instead of runtime reflection.
- Treat multi-row folding, join aggregation, generated keys plus update counts, OUT params, and multi-result shaping as
  reducer concerns, not plain row-mapper concerns.

Acceptance criteria:

- A repository method can return a DTO/record whose component names do not match SQL column labels when explicit mapping
  metadata or an approved mapper is supplied.
- Generated code can call a user mapper or generated mapper without reflection and without exposing JDBC resources.
- Compile-time diagnostics reject missing mappings, duplicate target mappings, invalid source columns when detectable,
  unsupported conversions, and mapper return type mismatches.
- Tests cover label-based mapping, explicit label mapping, mapper service injection, nullable values, scalar values, and
  type conversion failures.

Related files:

- `docs/codex/annotations.md`
- `docs/codex/validation.md`
- `docs/codex/reducer.md`
- `docs/codex/examples.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcRowMapper.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcRow.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-RELATIONSHIPS-001: Automatic Relationship Reducers From Dotted Labels

Status: identified, not implemented

Why deferred:
Joined SQL queries can return object graphs, not just one DTO per row. The provider needs generated aggregating reducers
that fold repeated parent rows into root objects and populate nested collections. This should be automatic when codegen
can infer the graph from dotted SQL labels and the Java model.

Current implementation:

- Generated declarative code maps each row independently.
- Dotted SQL labels are not parsed as relationship paths.
- Collection-valued paths do not trigger generated aggregation.
- There is no generated identity/key model for de-duplicating root or nested relationship objects.

Required behavior:

- Dotted SQL labels define object graph paths, for example `phones.tags.name`.
- The repository method return element type is the root type, for example `List<Contact>` means root type `Contact`.
- Codegen resolves each path segment against compile-time type metadata.
- A collection segment such as `phones` means the target property is a collection and its generic element type is the
  nested object type, for example `List<Phone>`.
- Nested collection paths such as `phones.tags` recurse using the collection element type, for example `Phone.tags` is a
  collection of `Tag`.
- Scalar leaf labels such as `id`, `name`, `phones.type`, and `phones.tags.name` map to fields, properties, constructor
  parameters, or factory parameters using generated access, never reflection.
- Any collection-valued path requires an aggregating reducer rather than a plain row mapper.

Example:

```java
@Data.Query("""
        SELECT c.ID    AS "id",
               c.NAME  AS "name",
               p.ID    AS "phones.id",
               p.TYPE  AS "phones.type",
               p.PHONE AS "phones.phone",
               t.ID    AS "phones.tags.id",
               t.NAME  AS "phones.tags.name"
        FROM CONTACT c
        LEFT JOIN PHONE p ON p.CONTACT_ID = c.ID
        LEFT JOIN TAG t   ON t.PHONE_ID = p.ID
        ORDER BY c.ID, p.ID, t.ID
        """)
List<Contact> findContacts();
```

Codegen inference for this example:

- root type is `Contact`;
- root key defaults to label path `id`;
- `phones` is a collection property on `Contact`;
- `phones.id` is the default key for each `Phone`;
- `phones.tags` is a collection property on `Phone`;
- `phones.tags.id` is the default key for each `Tag`;
- `name`, `phones.type`, `phones.phone`, and `phones.tags.name` map to scalar members.

Needed decisions:

- Default key policy: whether `id` is the default identity column for every graph node, and what annotation or metadata
  supports non-`id` and composite keys.
- Supported target models: records, mutable classes with accessible fields, JavaBean setters, collection getters,
  add-methods, builders, or generated factories.
- Collection initialization policy when a collection property is `null`.
- Whether generated reducers require SQL ordering or preserve encounter order with internal linked maps.
- How explicit mapping metadata from `JDBC-MAPPERS-001` composes with dotted path inference.

Constraints:

- Do not use Java reflection, runtime annotation scanning, or runtime generic inspection.
- Do not expose `ResultSet` or transcript internals to generated relationship reducers.
- Dotted labels must be read from JDBC column labels; SQL aliases containing dots usually need database-specific quoting.
- Left-joined child rows with all key columns `null` must not create empty child objects.
- Duplicate rows for the same root or nested key must merge into the existing object.
- The generated reducer must preserve stable encounter order for roots and collections unless a different order is
  explicitly declared.

Likely runtime algorithm:

- Materialize the result set into the internal row model.
- Iterate rows in order.
- Use a `LinkedHashMap` keyed by root identity to create or reuse root objects.
- For each collection path, use a nested identity map keyed by the collection element identity.
- Skip a collection element when its identity columns are all `null`.
- Assign scalar leaves when the containing object is created, or validate repeated scalar values if the same object is
  encountered again.
- Return roots in encounter order after converting mutable accumulation state to the declared return shape.

Acceptance criteria:

- `List<Contact>` style joined queries can return roots with nested `List<Phone>` and `List<Tag>` collections populated
  from dotted SQL labels.
- The reducer de-duplicates repeated root, child, and grandchild rows by generated identity keys.
- Left joins do not create child objects for missing relationships.
- Compile-time diagnostics reject unresolved path segments, unsupported collection element types, missing identity keys,
  ambiguous scalar mappings, inaccessible write targets, and unsupported constructors/factories.
- Tests cover no children, one child, multiple children, nested children, duplicate join rows, null child keys, out-of-order
  rows if ordering is not required, explicit non-`id` keys if approved, and immutable/mutable target variants that are
  supported.

Related files:

- `docs/codex/reducer.md`
- `docs/codex/validation.md`
- `docs/codex/examples.md`
- `docs/codex/annotations.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcReducers.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-TRANSCRIPT-001: Public Reducer Result View

Status: identified, not implemented

Why deferred:
The internal transcript model exists, but making it public would make transcript/event shapes supported API.

Current implementation:

- `JdbcTranscript`, `StepTranscript`, `JdbcEvent`, `RowsEvent`, `UpdateCountEvent`, `GeneratedKeysEvent`,
  `OutParamsEvent`, and `BatchEvent` are package-private.
- Public API exposes only `JdbcClient`, `JdbcParameter`, `JdbcRow`, and `JdbcRowMapper`.

Needed decisions:

- Whether to expose the full transcript model or a narrower read-only reducer result view.
- Naming and package for public result-view types.
- Whether public events should be sealed interfaces, records, or immutable interfaces.
- How much vendor-specific metadata should be exposed.

Acceptance criteria:

- Public reducer APIs can inspect all required JDBC outcomes without exposing `ResultSet`, `Statement`,
  `PreparedStatement`, or `CallableStatement`.
- Public result-view objects are immutable.
- Sensitive bind values are not exposed by default.
- Backward compatibility impact is documented.

Related files:

- `docs/codex/transcript.md`
- `docs/codex/reducer.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcTranscript.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcEvent.java`

## JDBC-REDUCERS-002: Multi-Result Transcript Traversal

Status: identified, not implemented

Why deferred:
The first JDBC provider implementation supports the common single-result query/update path. Advanced JDBC operations can
produce multiple result sets, interleaved update counts, generated keys, OUT parameters, batch outcomes, or multiple
named steps, and need explicit result selection rules before they are exposed through declarative repositories or the
imperative API.

Current implementation:

- `JdbcRunner` can record multiple JDBC outcomes from one statement execution as ordered transcript events.
- Built-in query reducers currently require exactly one `RowsEvent` in the selected step.
- Built-in single/list/optional terminals traverse only that one row event.
- Built-in update reduction sums `UpdateCountEvent` values in the only step.
- There is no built-in selector for row event role, ordinal, name, generated keys, OUT parameters, batch events, or
  multi-step plan results.

Needed decisions:

- Result-selection metadata for generated code: step name/index, event name, event ordinal, row role, generated-key
  selection, OUT parameter selection, and batch result selection.
- Whether any selector belongs in public `JdbcClient` fluent API, repository annotations, generated-only metadata, or a
  future custom reducer API.
- Ambiguity policy for repository return types when a transcript has more than one compatible event.
- Composite return shaping for methods that intentionally combine rows, counts, keys, OUT params, or batch outcomes.

Constraints:

- Do not expose `ResultSet`, `Statement`, `PreparedStatement`, or `CallableStatement`.
- Do not make `JdbcTranscript` public only to avoid implementing selection/reducer support.
- Keep single-result query behavior backward compatible.
- Prefer compile-time validation for declarative repositories whenever a selector is required.

Acceptance criteria:

- Built-in reducers can select transcript events by step, ordinal, name, and role where the operation metadata declares
  that shape.
- Query reducers support both the existing single-result default and explicit selection from multi-result transcripts.
- Generated-key, OUT parameter, batch, stored-procedure, pagination, and multi-step reducers reuse the same selection
  model instead of adding one-off traversal logic.
- Ambiguous multi-result repository methods fail at compile time when detectable, otherwise with a semantic `DataException`
  that summarizes the transcript shape.
- Tests cover multiple result sets, interleaved result sets and update counts, generated keys plus update count, OUT
  cursor plus OUT params, batch results, and multi-step transcripts.

Related files:

- `docs/codex/reducer.md`
- `docs/codex/transcript.md`
- `docs/codex/runtime.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcReducers.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcRunner.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-GENERATED-KEYS-001: Generated Keys

Status: in-progress; runtime and imperative terminals implemented, declarative/codegen selection deferred

Current implementation:

- `GeneratedKeysEvent` exists as a package-private transcript event.
- `JdbcRunner` requests generated keys only when the internal operation asks for them, then materializes
  `getGeneratedKeys()` into a `GeneratedKeysEvent`.
- `JdbcClient.Statement` has generated-key terminal methods and optional generated-key column-name selection.
- Package-private reducers handle list, required single, and optional generated-key rows.
- Codegen does not expose generated-key selection yet.
- Declarative update methods currently use affected-count terminals unless generated-key support is added to codegen.

Needed decisions:

- Public annotation shape, if any, for requesting generated keys.
- Return-type rules for scalar keys, optional keys, key lists, and key projection records.
- How generated keys interact with DML currently expressed through `@Data.Query`.
- How `@Data.Query` DML requests generated keys without introducing a separate update annotation.

Acceptance criteria:

- Runtime requests generated keys only when explicitly requested by generated operation metadata. Implemented for
  imperative terminals.
- Generated keys are materialized into transcript events. Implemented.
- Reducers handle scalar, optional, list, and record-shaped keys. Implemented where a `JdbcRowMapper` is supplied;
  generated record/key mapping is deferred to codegen.
- Without generated-key metadata, update-like operations return affected counts for `int`, `Integer`, `long`, `Long`,
  `boolean`, `Boolean`, and `void`; DTO/key/list/stream returns fail validation.
- Tests cover missing keys, multiple keys, update count plus keys, and driver-specific column labels.

Related files:

- `docs/codex/runtime.md`
- `docs/codex/reducer.md`
- `docs/codex/annotations.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/GeneratedKeysEvent.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcRunner.java`

## JDBC-BATCH-001: Batch Execution

Status: identified, not implemented

Current implementation:

- `BatchEvent` exists as a package-private transcript event.
- Runtime does not execute JDBC batches.
- Codegen does not generate batch operation metadata.

Needed decisions:

- Whether `@Data.Batch` is approved as shared API.
- Binding model for collections, records, entities, and named parameters.
- Public rich batch result type, if any.
- Generated keys behavior for batch operations.

Acceptance criteria:

- Supports `executeBatch()` and `executeLargeBatch()` where appropriate.
- Preserves per-item update status: updated, success-no-info, failed, and not-attempted.
- Partial failures preserve transcript data.
- Tests cover success, success-no-info, partial failure, generated keys, and empty batches.

Related files:

- `docs/codex/batch.md`
- `docs/codex/reducer.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/BatchEvent.java`

## JDBC-CALL-001: Stored Procedures And Functions

Status: identified, not implemented

Current implementation:

- `OutParamsEvent` exists as a package-private transcript event.
- `RowsEvent.RowRole.OUT_CURSOR` exists.
- Runtime does not execute `CallableStatement`.
- No OUT, INOUT, or OUT cursor annotations are implemented.

Needed decisions:

- Whether `@Data.Call`, `@Data.Out`, `@Data.InOut`, and `@Data.OutCursor` are approved public API.
- Whether OUT params are selected by name, position, or both.
- Public result-shaping model for OUT params and cursors.

Acceptance criteria:

- Runtime supports IN, OUT, INOUT, return values, and cursor OUT parameters without exposing live `ResultSet`.
- OUT cursors are materialized as row events.
- Reducers support scalar OUT values, records, cursor row sets, and composite results.
- Tests cover simple calls, multiple OUT params, cursor OUT params, and interleaved result sets/update counts.

Related files:

- `docs/codex/stored-procedures.md`
- `docs/codex/runtime.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/OutParamsEvent.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/RowsEvent.java`

## JDBC-PLAN-001: Multi-Step Plans And Repeatable Operations

Status: identified, not implemented

Current implementation:

- `JdbcTranscript` supports multiple `StepTranscript` entries.
- `StepRef` supports index and optional name.
- Runtime executes one operation at a time.
- Codegen does not build or execute `JdbcPlan`.

Needed decisions:

- Operation annotation repeatability and naming rules.
- Public or internal `JdbcPlan` API shape.
- Result-shaping annotations for composite records.
- Transaction behavior for multi-step methods.

Acceptance criteria:

- Multiple named operations can execute in one repository method.
- Each operation produces one named transcript step.
- Generated code rejects ambiguous unqualified return types.
- Composite records can select values from named steps without reflection.
- Tests cover step names, duplicate names, partial failure, and composite reductions.

Related files:

- `docs/codex/annotations.md`
- `docs/codex/transcript.md`
- `docs/codex/reducer.md`

## JDBC-PAGINATION-001: Page And Slice Results

Status: identified, partially implemented in codegen only

Current implementation:

- Codegen has a simple SQL-standard page query path using `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY`.
- There is no complete runtime or reducer support for `Page<T>` and `Slice<T>`.
- Count handling for `Page<T>` is not fully implemented or tested.

Needed decisions:

- Supported SQL pagination dialect policy for the first version.
- Whether dialect customization belongs in config or operation metadata.
- Whether page count executes as a second step in a `JdbcPlan`.

Acceptance criteria:

- `Slice<T>` works with deterministic ordering rules.
- `Page<T>` executes rows plus count and constructs the public page result.
- Tests cover empty, first, middle, and final pages.
- Diagnostics reject pagination without deterministic ordering if that policy is adopted.

Related files:

- `docs/codex/validation.md`
- `docs/codex/reducer.md`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-IMPERATIVE-001: Public Fluent Imperative API

Status: implemented for query/update and generated-key terminal set; remaining advanced terminals deferred

Current implementation:

- Generated `@Data.Query` code uses the public `JdbcClient` contract.
- `JdbcClient.execute(String)` returns a statement object with parameter binding, `fetchSize`, row terminals, optional
  terminal, update-count terminals, generated-key column-name selection, and generated-key terminals.
- Terminal methods open JDBC resources, execute through `JdbcRunner`, reduce an internal transcript, and close resources
  before returning.
- Lazy streaming is not implemented; `stream(...)` is materialized before returning.

Needed decisions:

- How to extend `JdbcClient` for batch, stored procedures, multiple statements, and custom reducers without exposing JDBC
  resources.
- Whether advanced options belong on `JdbcClient.Statement` or specialized statement subtypes.

Acceptance criteria:

- Generated declarative repositories and handwritten imperative code use the same runtime path. Implemented for explicit
  `@Data.Query` query/update methods.
- Public API does not expose JDBC resources.
- API can attach generated or custom reducers without reflection. Deferred.
- Tests cover the imperative API separately from generated repositories. Runtime reducer/factory and generated repository
  tests exist; standalone imperative-client tests should be expanded.

Related files:

- `docs/codex/imperative-api.md`
- `docs/codex/runtime.md`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcClient.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcClientImpl.java`

## JDBC-CODEGEN-004: Declarative Query Return Shaping

Status: identified, not implemented

Current implementation:

- Generated query methods use `JdbcClient` row terminals directly.
- Plain item returns currently use a strict single-row terminal.
- Optional returns use a null-allowing strict single-row terminal and wrap the result.
- List returns map all rows.

Target behavior:

- Operation intent comes from annotation/provider metadata first; return type shapes the result within that operation
  family.
- Query reference return, for example `User findUser(long id)`, maps the first row and returns `null` when no row exists.
- `Optional<T>` returns `Optional.empty()` when no row exists.
- Collection/list returns an empty collection when no rows exist.
- Primitive scalar returns the first row value and fails when no row exists because `null` cannot be returned.
- Default single-value query shapes use first-row semantics. Exact-one semantics require a separate explicit API decision.

Acceptance criteria:

- Codegen selects a null-allowing query reducer for non-primitive reference returns.
- Codegen selects an optional reducer for `Optional<T>`.
- Codegen selects list/stream reducers for collection-like returns.
- Codegen selects strict primitive/scalar reducers for primitive returns.
- Built-in reducers or `JdbcClient` terminals provide first-row/null-on-empty semantics without requiring generated code to
  inspect rows directly.
- Tests cover no-row and multi-row behavior for reference, optional, list, primitive scalar, boxed scalar, and record returns.

Related files:

- `docs/codex/validation.md`
- `docs/codex/reducer.md`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcReducers.java`

## JDBC-CODEGEN-003: Direct JdbcClient Support Outside Explicit @Data.Query

Status: implemented for explicit `@Data.Query`; remaining work is method-name and repository-interface support

Current implementation:

- Generated JDBC repositories have a standard `jdbcClient` field.
- Explicit `@Data.Query` methods are generated directly under `data/jdbc/codegen` and call
  `jdbcClient.execute(...).<terminal>(...)`.
- JDBC codegen does not call shared `RepositoryGenerator.generateInterfaces` or
  `RepositoryGenerator.generateQueryMethods` for explicit SQL methods.
- JDBC currently accepts `Data.GenericRepository` plus explicit `@Data.Query` methods. Basic/Crud/Pageable repository
  interfaces fail at codegen time with provider-specific diagnostics until implemented directly.

Needed decisions:

- Which method-name query subset JDBC should support first.
- Whether JDBC Basic/Crud repository methods should be implemented from entity metadata or rejected until an ORM-like SQL
  model exists.

Acceptance criteria:

- JDBC generated source for explicit `@Data.Query` never calls `executor.call(...)`, `JdbcClient.call(...)`, or
  `JdbcClient.run(...)`.
- Supported method-name queries compile and execute through `JdbcClient`.
- Unsupported Basic/Crud/Pageable methods fail at compile time with provider-specific diagnostics.
- Tests cover generated source shape for every supported repository interface family.

Related files:

- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcRepositoryClassGenerator.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcQueryMethodsGenerator.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`

## JDBC-TX-001: Transaction Integration

Status: identified, not implemented

Current implementation:

- `data/jdbc` works against plain `DataSource`.
- No dependency on `transaction/jdbc` exists.
- Generated repository code is not transaction-aware.

Needed decisions:

- Transaction-aware `DataSource` or connection-provider boundary.
- Whether `transaction/jdbc` should provide a service consumed by `data/jdbc`.
- Connection release semantics for transaction-managed connections.

Acceptance criteria:

- Plain `DataSource` path remains supported.
- Transaction-aware path participates in Helidon transactions without duplicating transaction lifecycle code in `data/jdbc`.
- Tests cover commit, rollback, nested calls if supported, and non-transactional fallback.

Related files:

- `docs/codex/architecture.md`
- `docs/codex/runtime.md`
- `transaction/jdbc`

## JDBC-CODEGEN-001: SQL Parameter Parsing Alignment

Status: implemented for current explicit `@Data.Query` binding modes; continue expanding tests as SQL support grows

Current implementation:

- `@Data.Query` methods use a JDBC-local codegen scanner to discover SQL parameters at code generation time.
- The scanner handles punctuation-adjacent named parameters such as `VALUES (:name, :typeId)`.
- The scanner skips common SQL comments, quoted strings, quoted identifiers, PostgreSQL dollar-quoted strings, Oracle
  `q'...'` strings, and dialect operators that resemble bind markers.
- The JDBC runtime parsed model now preserves parameter mode and ordered ordinal indexes, rejects mixed bind marker styles,
  supports `$1` and `?1`, and fails unused named bindings by default.
- Codegen emits named or positional `JdbcParameter` entries according to the SQL marker mode. Runtime remains the final
  authority for exact execution-time validation.

Acceptance criteria:

- Codegen discovers named parameters regardless of adjacency to SQL punctuation.
- Codegen and runtime parameter parsing rules are aligned for JDBC SQL.
- Tests cover parameters after `(`, `,`, `=`, comparison operators, comments, string literals, quoted identifiers, `$1`,
  `?1`, repeated named parameters, mixed-style rejection, and unused binding rejection.

Related files:

- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/NamedSqlParser.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcSqlParameters.java`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcQueryMethodsGenerator.java`

## JDBC-CODEGEN-005: `@Data.Query` Ordinary SQL Operation Selection

Status: implemented without SQL keyword classification; stored procedures and generated-key declarative metadata remain
open

Current implementation:

- Generated code and imperative code both use `JdbcClient.execute(String)` plus terminal methods.
- Runtime operation family is selected by the terminal method, not by first SQL keyword.
- Method-name delete/update operations still use update terminals because the operation intent comes from Helidon Data
  method parsing.
- Explicit `@Data.Query` SQL is accepted as ordinary SQL without classifying the first executable statement keyword.
- Explicit `@Data.Query` methods generate row-result terminal calls according to the declared return shape.
- Explicit `void @Data.Query` generates an update-count terminal and ignores the count.
- If JDBC execution produces update counts and no result set, the reducer exposes a synthetic one-column row named
  `updateCount` so scalar count-like returns can be mapped.
- Stored procedure call SQL is outside the approved `@Data.Query` contract but is not reliably rejected by SQL keyword
  scanning.
- Comment-leading SQL and CTE SQL require no special operation-selection code.

Needed decisions:

- Whether `@Data.Batch`, `@Data.Call`, and `@Data.Script` are approved public API.
- How generated-key metadata is declared for declarative update methods.
- Whether update-returning-rows is supported separately from generated keys.

Acceptance criteria:

- `@Data.Query("DELETE ...")` and `@Data.Query("/* comment */ DELETE ...")` compile for scalar count-like return types
  without SQL keyword classification.
- `void @Data.Query("CREATE TABLE ...")` compiles and executes through the update-count terminal.
- `@Data.Query("WITH ... SELECT ...")` and `@Data.Query("WITH ... DELETE ...")` require no CTE classifier.
- Update-count-only transcripts map through `row.value(1, Integer.class)`, `row.value(1, Long.class)`, and
  `row.value("updateCount", Long.class)`.
- Method-name delete/update methods still generate update terminal calls.
- Future call/batch annotations select operation families that cannot be represented as ordinary SQL.

Related files:

- `docs/codex/sql-parsing.md`
- `docs/codex/annotations.md`
- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcQueryBuilder.java`
- `data/jdbc/jdbc/src/main/java/io/helidon/data/jdbc/JdbcReducers.java`

## JDBC-CODEGEN-002: DML Return Conversion For Long

Status: identified, not implemented

Current implementation:

- JDBC update execution returns `Number`.
- DML methods returning `int` compile through `.intValue()`.
- DML methods returning primitive or boxed `long` can generate a raw `Number` return instead of `.longValue()`.
- The pokemon JDBC sample uses `int deleteById(int id)` as a temporary workaround.

Acceptance criteria:

- DML methods returning primitive `long` or boxed `Long` compile and return `.longValue()`.
- DML methods returning `Number` still return the raw `Number`.
- Codegen tests cover `int`, `long`, `Integer`, `Long`, `Number`, `boolean`, `Boolean`, `void`, and `Void`.

Related files:

- `data/jdbc/codegen/src/main/java/io/helidon/data/jdbc/codegen/JdbcStatementGenerator.java`
- `data/jdbc/codegen/src/test/java/io/helidon/data/jdbc/codegen/JdbcCodegenTest.java`
