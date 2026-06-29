# SQL Parsing and Operation Selection

## Purpose

This document captures the JDBC provider SQL parsing rules and operation-selection policy.

The JDBC provider must not use a full SQL grammar parser. SQL parsing is deliberately narrow:

- find and rewrite supported bind markers;
- protect comments, quoted strings, quoted identifiers, and common dialect quoting forms;
- split initialization scripts into executable statements;
- provide diagnostics for parameter-binding problems and unsupported annotation/return-shape combinations.

Operation selection for imperative code belongs to the public `JdbcClient` terminal method. Operation selection for
declarative `@Data.Query` must not depend on first-keyword SQL classification. The JDBC provider accepts ordinary SQL
text and lets the declared return shape plus runtime transcript decide how the result is reduced.

## Jdbi Reference Model

Jdbi separates statement operation selection from SQL parameter parsing:

- `@SqlQuery` creates a query;
- `@SqlUpdate` creates an update;
- `@SqlCall` creates a call;
- `@SqlBatch` creates a prepared batch;
- `@GetGeneratedKeys` changes an update into generated-key mapping.

Jdbi does not inspect the first SQL keyword to choose query versus update because its annotations already carry that
operation intent. Its SQL parser renders templates, rewrites bind markers to JDBC `?`, and records ordered parameter
metadata. Comments and quoted text are preserved so markers inside them are not treated as parameters.

Helidon JDBC borrows the bind-marker discipline from this model, but not the annotation split. For the declarative JDBC
provider, `@Data.Query` is the single annotation for ordinary SQL. Codegen should not classify ordinary SQL by keyword;
stored procedures, batches, scripts, and generated-key intent need explicit future metadata instead of inference.

## Helidon JDBC Rule

`JdbcClient.execute(String)` is intentionally neutral. The terminal method selects the operation family:

```java
jdbcClient.execute(sql).list(mapper);                  // query intent
jdbcClient.execute(sql).single(mapper);                // query intent
jdbcClient.execute(sql).updateCountInt();              // update/DDL intent
jdbcClient.execute(sql).generatedKey(mapper);          // update + generated-key intent
```

Generated declarative repository code must use the same public API that imperative applications use. It must not call an
intermediate repository-only executor.

For declarative repositories:

- method-name parsing provides operation intent for generated select/delete/update methods;
- `@Data.Query` accepts ordinary SQL for the JDBC provider, including row-result SQL, DML, and DDL;
- stored procedure calls are not part of the approved `@Data.Query` contract, but they are not reliably detected by SQL
  keyword scanning;
- future `@Data.Batch`, `@Data.Call`, or script annotations, if approved, should provide operation families that cannot
  be expressed cleanly as one ordinary statement;
- generated keys require explicit generated-key metadata and must not be inferred from a scalar return type.

## Explicit SQL In `@Data.Query`

`@Data.Query` should be simple for application authors: use SQL and let the method return type describe the result shape.
The JDBC codegen provider does not classify explicit SQL as select/update/DDL:

- row-result SQL maps rows through `list`, `single`, `singleOrNull`, or `optional`;
- DML and DDL with scalar return types run through the same row-shaped terminal; if the transcript contains update counts
  and no result set, the reducer exposes a synthetic one-column row named `updateCount`;
- explicit `void @Data.Query` uses an update-count terminal and ignores the returned count;
- stored procedure calls require an approved call model before they are part of the supported contract;
- generated-key return mapping requires explicit generated-key metadata and is not inferred from `int`, `long`, or DTO
  return types.

Supported query-like examples:

```java
@Data.Query("SELECT ID, NAME FROM POKEMON WHERE ID = :id")
Optional<PokemonRow> find(long id);
```

```java
@Data.Query("""
        -- comment before the CTE
        WITH rows AS (
            SELECT ID, NAME FROM POKEMON
        )
        SELECT ID, NAME FROM rows
        """)
List<PokemonRow> list();
```

Supported update-count example:

```java
@Data.Query("DELETE FROM POKEMON WHERE ID = :id")
int delete(long id);
```

The same rule applies to comment-leading SQL and CTE SQL. The provider does not need to know whether a `WITH` statement
ultimately selects or updates; JDBC execution produces a transcript, and reduction handles either rows or update counts.

## Comments and CTEs

Leading comments must not affect parameter parsing. They should be preserved and ignored only for bind-marker discovery.

`WITH` is not enough information by itself because a CTE can precede `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or
vendor-specific constructs. The JDBC provider should not inspect CTE structure for operation selection. CTE support is
therefore a natural result of avoiding statement-family classification.

Do not attempt a full SQL parser unless a later feature requires it.

## Bind Marker Parsing

The JDBC runtime parser rewrites bind markers to JDBC `?` and records ordered parameter metadata. The internal parsed model
must capture:

```text
rewritten SQL
parameter mode: NONE | NAMED | POSITIONAL | ORDINAL
ordered named parameter occurrences
ordered ordinal indexes
positional count
```

Rules:

- named parameters use `:name`;
- named parameters may appear more than once and each occurrence is bound;
- ordinal parameters may use `$1` or `?1`;
- ordinal placeholders bind by the index declared in SQL, not by encounter order;
- positional `?` placeholders bind in encounter order;
- named, ordinal, and positional styles must not be mixed in one SQL statement;
- missing bindings fail;
- unused named bindings fail by default;
- raw JDBC objects are never exposed.

The parser must ignore marker-looking text inside:

- single-quoted strings;
- double-quoted identifiers/text;
- backtick identifiers;
- SQL Server bracket identifiers when they are simple identifiers;
- PostgreSQL dollar-quoted strings;
- Oracle `q'...'` strings;
- line and block comments.

The parser must preserve dialect constructs that look like bind markers, including PostgreSQL `::` casts, PL/SQL `:=`,
and common JSON operators such as `?|`, `?&`, and `??`.

## Codegen Parser Alignment

JDBC codegen has a provider-local parameter scanner under `data/jdbc/codegen`; runtime has the execution parser under
`data/jdbc/jdbc`. They intentionally follow the same marker rules. Codegen uses the scanner only to validate method
bindings and decide whether generated `JdbcParameter` entries are named or positional. It does not select operation
family from SQL text.

Future acceptance criteria:

- codegen discovers named parameters adjacent to punctuation, such as `VALUES (:name, :typeId)`;
- codegen ignores parameters inside SQL comments and quoted text;
- codegen and runtime agree on `$1`, `?1`, and named parameter syntax;
- compile-time diagnostics report missing or ambiguous bindings before generated code is emitted.

## Script Splitting

Script parsing is separate from bind marker parsing and operation selection.

Initialization scripts may be split on semicolons while respecting comments and string literals. This logic should remain
internal and should not be used to classify ordinary repository SQL statements.

If public script execution is added later, it should be represented as an explicit script operation or plan, not as a
single inferred query/update statement.
