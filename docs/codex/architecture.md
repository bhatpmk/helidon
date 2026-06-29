# Architecture

## Purpose

The JDBC provider adds a plain-JDBC runtime for Helidon Data repositories. It lives under `data/jdbc` and supports the Helidon Data declarative repository model without changing the existing Jakarta Persistence provider behavior.

The first design target is declarative repositories, generated against the same public `JdbcClient` fluent API that
handwritten imperative code will use.

## Module Boundaries

Before creating new packages, classes, or module structure, inspect existing Helidon Data modules and mirror their
patterns where they fit. The JDBC provider should feel like a native Helidon Data provider, not a standalone library
embedded in the repository.

### `data/data`

Shared Helidon Data API:

- `Data.Repository`
- `Data.Provider`
- `Data.PersistenceUnit`
- `Data.Query`
- repository interfaces such as `BasicRepository`, `CrudRepository`, and `PageableRepository`

Keep changes here minimal. Any new annotation is public shared API and must be provider-neutral enough to coexist with Jakarta Persistence.

### `data/codegen`

Shared Helidon Data repository codegen infrastructure.

The JDBC provider must use this infrastructure by adding a `PersistenceGeneratorProvider`, similar to `data/jakarta-persistence/codegen`. Do not create a separate annotation processor outside the Helidon codegen model.

### `data/jakarta-persistence`

Existing Jakarta Persistence provider.

Do not change its generated code shape, return semantics, provider name, or runtime behavior for this work unless a separate task explicitly asks for it.

### `data/jdbc`

New JDBC provider.

Expected submodules, if implementation chooses to split them:

```text
data/jdbc/jdbc             Runtime provider and minimal public service contract.
data/jdbc/codegen          JDBC PersistenceGeneratorProvider and generated-code helpers.
data/jdbc/tests            Provider-specific tests, if not colocated.
```

Use the repository's module naming conventions when creating actual modules.

The implementation should follow existing Helidon conventions for:

- flat package structure;
- package-private implementation classes;
- `module-info.java` service declarations;
- generated builder/config classes where applicable;
- test placement and Hamcrest/JUnit style;
- Maven artifact names and module names.

### `data/sql`

Existing SQL and DataSource support.

Reuse configuration and DataSource-related building blocks where they fit. Do not duplicate generic SQL driver or DataSource configuration code in `data/jdbc`.

The JDBC provider must read configured sources from `data.sources`, including SQL data sources such as:

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
```

### `transaction/jdbc`

JDBC transaction integration.

This module may need focused changes so the JDBC provider can use transaction-aware connections. Keep the boundary at `DataSource` or a narrow service contract; generated repositories should not contain transaction logic.

## Provider Selection

The JDBC provider should use provider name:

```text
jdbc
```

Selection mechanisms:

- `@Data.Provider("jdbc")` on a repository;
- provider configuration for a JDBC persistence unit under `data.persistence-units.jdbc`;
- default provider selection only when unambiguous.

Jakarta provider selection must continue to work exactly as it does now.

JDBC persistence unit configuration shape:

```yaml
data:
  persistence-units:
    jdbc:
      - name: "pokemon"
        data-source: "pokemon"
        init-script: "init.sql"
```

Rules:

- `name` identifies the JDBC persistence unit and is used by repository provider/persistence-unit selection.
- `data-source` references a source configured under `data.sources`.
- `init-script` is optional and should be executed according to the provider initialization policy.
- missing or ambiguous persistence unit selection must fail with a clear diagnostic.

## Runtime Flow

Generated repositories and runtime code should follow this shape:

```text
repository method
  -> generated package-private implementation
  -> JdbcClient
  -> JdbcOperation / JdbcPlan
  -> JdbcRunner
  -> JdbcTranscript
  -> reducer or generated reduction helper
  -> return value
```

Generated repository code is allowed to:

- call the public `JdbcClient` fluent contract;
- bind method arguments;
- select an operation kind;
- select or invoke generated mapping/reduction helpers;
- pass compile-time generated metadata.

Generated repository code must not:

- acquire connections;
- prepare statements;
- call `ResultSet.next()`;
- call `Statement.execute*()`;
- catch or interpret `SQLException`;
- use reflection.

Operation kind selection must follow `docs/codex/sql-parsing.md`: generated code or `JdbcClient` terminals choose the
operation family, while SQL parsing is limited to bind markers, comments/quoting, script splitting, and diagnostics.

## Codegen Integration

Follow the existing provider pattern:

- implement `PersistenceGeneratorProvider`;
- return a JDBC `PersistenceGenerator`;
- expose the provider through `module-info.java` with `provides`;
- use shared `BasePersistenceGenerator`/`RepositoryGenerator` only for provider discovery and `RepositoryInfo` creation;
- keep generated repository classes package-private;
- use Helidon service annotations consistently with the Jakarta generator.

JDBC explicit `@Data.Query` methods are generated directly in `data/jdbc/codegen`. Do not route them through shared
`RepositoryGenerator.generateQueryMethods`, `QueryByJpqlMethodsGenerator`, or a private generated executor adapter. Until
JDBC Basic/Crud/Pageable support is implemented directly, fail unsupported repository interfaces at codegen time with
provider-specific diagnostics.

Generated class naming should be predictable, for example:

```text
PokemonRepository__Jdbc
```

Use actual naming conventions from the surrounding code when implementing.

## Public API Minimization

Default visibility guidance:

| Type kind | Default visibility |
|---|---|
| generated repository implementation | package-private |
| runtime implementation classes | package-private |
| operation and plan model | package-private |
| transcript and event model | package-private unless explicitly user-facing |
| reducers | package-private unless custom user reducers are approved |
| `JdbcClient` service contract used by generated code and imperative code | public narrow API |
| annotations in `Data` | public only after API review |

Do not make a type public because tests or generated code in the same module are inconvenient. Use package-local tests and generated helpers where possible.

## No Reflection

Mapping and reduction must be generated or explicit.

Do not use:

- `java.lang.reflect.*`;
- runtime record component inspection;
- runtime annotation scanning;
- reflective constructor calls;
- dynamic proxies for repository implementation.

Use Helidon codegen metadata (`TypeInfo`, `TypedElementInfo`, `TypeName`, annotations known at compile time) to generate:

- row mappers;
- record constructors;
- parameter binders;
- reducer selection;
- diagnostics.

## JDBC-Only Runtime

The JDBC provider runtime must not depend on Jakarta Persistence APIs, Criteria APIs, entity managers, or ORM behavior.

Allowed runtime dependencies include:

- `java.sql`;
- `javax.sql.DataSource`;
- existing Helidon common, service, data, and SQL support modules as needed;
- transaction interfaces only through approved module boundaries.

## Transcript Model

The transcript is the provider's internal source of truth for:

- rows;
- update counts;
- generated keys;
- OUT parameters;
- batch item outcomes;
- warnings;
- partial failures.

Common user returns are produced from the transcript by reducers or generated reduction code. Do not create separate execution paths for individual return types.

## Declarative Scope

Initial JDBC provider support should prioritize:

1. repositories selected with provider `jdbc`;
2. SQL supplied by `@Data.Query`;
3. generated queries from existing repository method parsing where practical;
4. list, stream, optional, single, scalar, update-count, and boolean returns;
5. configured `data.sources` and `data.persistence-units.jdbc` resolution;
6. plain `DataSource` execution.

Advanced features should be added incrementally:

- generated keys;
- batch;
- stored procedure calls;
- multi-step plans;
- transaction-aware DataSource integration.

## Non-Goals

The JDBC provider is not:

- an ORM;
- an entity tracking or dirty checking system;
- a JPQL provider;
- a schema migration tool;
- a reflection-based mapper;
- a replacement for the Jakarta Persistence provider.

## Observability

The runtime may record or log:

- provider name;
- SQL kind;
- step name;
- elapsed time;
- row count;
- update count;
- warning count.

Do not log raw bind values by default. If diagnostic bind capture is added, make redaction the default.
