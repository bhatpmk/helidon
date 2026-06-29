# Declarative API and Annotation Rules

## Purpose

The JDBC provider supports Helidon Data declarative repositories. The provider must use existing shared `io.helidon.data.Data` API where possible and add public annotations only when necessary.

The Jakarta Persistence provider currently uses the same shared Data API. Its behavior must remain unchanged.

## Existing Shared API

Relevant existing annotations:

```java
@Data.Repository
@Data.Provider
@Data.PersistenceUnit
@Data.Query
```

For JDBC, `@Data.Query` text is SQL. For Jakarta Persistence, it remains provider-specific query text such as JPQL.

Provider selection:

```java
@Data.Repository
@Data.Provider("jdbc")
interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {
}
```

Do not reinterpret existing annotations in a way that changes Jakarta behavior.

The external declarative JDBC examples currently use additional experimental annotations, including `@Data.Param`,
`@Data.GeneratedKeys`, `@Data.Map`, `@Data.Mapper`, `@Data.MapWith`, `@Data.ReduceWith`, and `@Data.Key`. Treat these as
reference-only sample annotations, not final API or required use cases. The implementation does not need to preserve
invalid or obsolete annotation names.

## Public API Discipline

Every new `Data` annotation is public Helidon API.

Before adding one:

1. prove existing `Data.Query`, method-name parsing, repository interfaces, and provider configuration are insufficient;
2. define how the annotation behaves for each provider, or explicitly make it provider-specific without breaking others;
3. validate it at compile time;
4. document retention and target;
5. add tests for provider coexistence.

Prefer `RetentionPolicy.SOURCE` or `CLASS` for codegen-only annotations. Do not require runtime annotation lookup.

## Operation Intent

Operation intent must be known at codegen time. Use the Jdbi-inspired split documented in
`docs/codex/sql-parsing.md`: SQL parsing finds bind markers and supports diagnostics, while annotations, method-name
parsing, repository contracts, and `JdbcClient` terminals select the operation family.

Possible sources, in order:

1. an existing repository method contract, such as `save`, `findById`, or `deleteById`;
2. parsed method name from `data/codegen/parser`;
3. existing `@Data.Query`;
4. a new operation annotation if approved.

For JDBC, generated code should build an operation kind:

```text
QUERY
UPDATE
BATCH
CALL
SCRIPT
```

Do not infer operation kind at runtime by trying multiple JDBC paths.

Operation annotation and provider metadata decide the execution family; the declared return type only shapes the result
allowed for that family. Use Jdbi as inspiration for this user model, adapted to Helidon compile-time codegen:

- query-like operations map rows;
- update-like operations usually return affected row counts;
- update-like operations return generated keys only when generated-key metadata is explicitly present;
- invalid annotation/return combinations fail at compile time when detectable.

## `@Data.Query`

For the JDBC provider, `@Data.Query` means the string is SQL.

Examples:

```java
@Data.Query("SELECT COUNT(*) FROM POKEMON")
long countPokemon();
```

```java
@Data.Query("SELECT ID, NAME FROM POKEMON WHERE ID = :id")
Optional<PokemonRow> findById(long id);
```

Allowed return categories for row-result SQL:

```text
List<T>
Stream<T>
Optional<T>
T
scalar primitives and wrappers
String
void for update-count/DDL execution where the count is ignored
```

For ordinary SQL, `@Data.Query` is also the JDBC declarative annotation for DML and DDL. The provider must not classify
explicit SQL by the first executable keyword:

- row-result SQL maps rows to the declared return type;
- `INSERT`, `UPDATE`, `DELETE`, `MERGE`, and DDL with scalar count-like returns are reduced from update-count-only
  transcripts through a synthetic `updateCount` row;
- explicit `void @Data.Query` uses the `JdbcClient` update-count terminal and ignores the count;
- stored procedure calls require an approved call model before they are part of the supported contract.

Allowed return categories for update-count SQL:

```text
void
boolean / Boolean
int / Integer
long / Long
Number
```

Generated keys are not inferred from scalar or DTO returns. They require explicit generated-key metadata when that
declarative API is approved.

Common Table Expressions are allowed for query-like SQL:

```java
@Data.Query("""
        WITH rows AS (
            SELECT ID, NAME FROM POKEMON
        )
        SELECT ID, NAME FROM rows
        """)
List<PokemonRow> listRows();
```

A `WITH` clause is not enough by itself to infer the operation family because it can precede `SELECT`, `INSERT`,
`UPDATE`, `DELETE`, `MERGE`, or vendor-specific constructs. The JDBC provider should not inspect CTE structure for
operation selection; reduction handles either row results or update-count-only transcripts.

## Candidate New Operation Annotations

Only add these if approved as public API:

```java
@Data.Batch
@Data.Call
@Data.Script
```

Recommended meanings:

- `Batch`: repeated execution of one statement with many bindings;
- `Call`: stored procedure or function invocation;
- `Script`: advanced multi-statement script.

Do not add `@Data.Update` just to support ordinary DML or DDL. For the JDBC provider, ordinary DML and DDL use
`@Data.Query` and update-count return shaping.

Do not add `@Data.CreateStoredProcedure`. Stored procedure creation is DDL and should use `@Data.Query` or script
semantics.

Update return shaping should follow this model:

- no generated-key metadata: `void`, `boolean`, `int`, `Integer`, `long`, and `Long` return affected row-count semantics;
- no generated-key metadata: DTO, record, scalar key-as-value, list, and stream returns are invalid unless a separate
  approved update-returning-rows feature exists;
- generated-key metadata present: execute using generated-key support and map the generated-key result set to the declared
  return shape;
- `boolean` without generated keys means affected row count is greater than zero;
- `int`/`Integer` without generated keys mean exact affected row count;
- `long`/`Long` without generated keys mean large affected row count.

## Candidate Modifier Annotations

Only add these if necessary:

```java
@Data.GeneratedKeys
@Data.Out
@Data.InOut
@Data.OutCursor
@Data.FetchSize
@Data.Timeout
@Data.UseReducer
```

Rules:

- generated keys apply only to update and batch operations;
- OUT and cursor annotations apply only to call operations;
- fetch size applies to result-set-producing operations;
- timeout may apply to all operation kinds;
- reducer hooks must avoid reflection and use direct generated construction or service registry lookup.

## Mapper UX Principles

Do not add a large family of mapper annotations. The JDBC provider should be convention-first and require annotations only
for the small number of cases where names or construction are genuinely ambiguous.

Jdbi is useful inspiration here: common mappings work through conventions, constructor/bean-style mapping, prefixes,
nested mapping, and an explicit mapper escape hatch. Helidon JDBC must keep the same low-friction user experience while
using build-time code generation instead of runtime reflection.

Preferred order:

1. No annotation for simple scalar, flat record, DTO, and dotted relationship mapping when labels match model paths.
2. A single optional column-name override annotation on the target member or constructor/factory parameter when a SQL label
   does not match the Java name.
3. Automatic mapper service selection by return element type when exactly one compatible mapper is available.
4. A single optional mapper selector when the user wants a custom `JdbcRowMapper<T>` for one-row mapping and automatic
   selection would be ambiguous.
5. A single optional reducer selector only for advanced aggregation that built-in generated reducers cannot infer.

Avoid adding separate public annotations for every mapping variant such as method-level column arrays, map-with,
mapper-definition, nested, factory, and key annotations unless a concrete use case cannot be handled by the small surface
above.

Candidate capability, names not final:

```java
@Data.UseMapper
@Data.Column
```

The required capability is that a repository method or return projection can define or select mapping metadata at compile
time, so SQL result columns do not have to be aliased to Java field, parameter, or record component names.

Possible mapping sources:

- a `JdbcRowMapper<T>` service selected by the repository method;
- a unique `JdbcRowMapper<T>` service inferred from the repository method return element type;
- a generated mapper from the return projection type and optional member/parameter column-name overrides;
- a compile-time-known constructor or factory selected by normal type metadata before any annotation is considered.

Rules:

- generated code may use SQL labels by default for simple records;
- explicit column-name metadata must override label-by-component-name matching;
- dotted SQL labels may define nested object paths, and collection-valued paths require generated relationship reducers;
- explicit mappings must identify target fields, properties, constructor parameters, record components, or factory
  parameters at compile time;
- source columns should be selected by label/name; ordinal mapping should be avoided unless a later API review accepts it
  for a specific reason;
- mapper selection must be type-compatible with the repository method return shape;
- multi-row aggregation and multi-result composition belong to reducers, not ordinary row mappers.

## Candidate Result-Shaping Annotations

Only add these if composite returns are supported as public API:

```java
@Data.MetaCount
@Data.Result
@Data.GeneratedKey
@Data.OutParam
@Data.ResultSetParam
```

These annotations are read by codegen and must not require runtime reflection.

Example composite result:

```java
record InsertPokemonResult(
        @Data.MetaCount long affectedRows,
        @Data.GeneratedKey("ID") long id) {
}
```

Generated code should construct the record explicitly.

## Binding Rules

JDBC SQL may use named parameters:

```sql
SELECT * FROM POKEMON WHERE NAME = :name OR ALIAS = :name
```

Generated or runtime operation-building code must rewrite this to positional JDBC parameters and bind both occurrences.

Binding source order:

1. explicit binding annotations, if added;
2. Java parameter names available to codegen;
3. generated entity or record property binders.

Candidate binding annotations, only if required:

```java
@Data.Bind
@Data.BindBean
@Data.BindList
```

The reference sample currently uses `@Data.Param` for named and positional binding. This should not influence the final API
name.

Rules:

- every SQL named parameter must have a binding source;
- repeated named parameters bind every occurrence;
- ambiguous binding sources fail at compile time;
- do not parse parameters inside string literals or SQL comments.

## Repeatable Operations

Multi-step repository methods are advanced.

If operation annotations are repeatable:

1. every operation annotation must have a non-empty unique name;
2. generated code must build a `JdbcPlan`;
3. return type must be an approved composite result, raw transcript if public, or custom reducer if public;
4. unqualified DTO/scalar returns are invalid.

Example:

```java
@Data.Query(name = "insert", value = "INSERT INTO POKEMON(NAME) VALUES (:name)")
@Data.Query(name = "select", value = "SELECT ID, NAME FROM POKEMON WHERE NAME = :name")
PokemonCreateResult create(String name);
```

This requires explicit result shaping so the select and insert outcomes are not confused.

## Stored Procedures

Stored procedure invocation uses call semantics.

Stored procedure creation is DDL:

```java
@Data.Query("CREATE PROCEDURE ...")
void createProcedure();
```

or script semantics for multiple statements.

Cursor OUT parameters must become row events, never public `ResultSet` values.

## Validation Philosophy

Prefer compile-time validation in the JDBC codegen provider.

Runtime validation is still required for shapes only known after execution, such as row cardinality or generated-key availability.

Diagnostics should identify:

- repository method;
- provider;
- annotation;
- return type;
- expected alternatives;
- suggested fix.
