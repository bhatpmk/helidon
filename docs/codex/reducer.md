# Reducer and Mapping Model

## Purpose

Reducers turn a `JdbcTranscript` into the Java value declared by a repository method.

The reducer concept is part of the architecture. It does not require a public reducer API in the first implementation.

## Core Rules

1. Reducers never execute SQL.
2. Reducers never use JDBC APIs.
3. Reducers never own connections, statements, or result sets.
4. Reducers are deterministic for a given transcript.
5. Reducers throw semantic runtime exceptions for shape and mapping errors.
6. Reducers must not throw `SQLException`.
7. Reducers must not use reflection.

## Visibility

Default reducer implementations to package-private in `data/jdbc`.

Expose a public reducer interface only if custom user reducers are accepted as a public feature. Generated code can usually call
`JdbcClient` terminal methods or generated package-local helpers without exposing a broad reducer API.

## Generated Mapping

Do not implement generic runtime record mapping with reflection.

Instead, JDBC codegen should generate mapping code for:

- entity-like return types known from repository generic parameters;
- records used as row projections;
- records used as composite results;
- scalar columns;
- generated key records;
- OUT parameter result records.

Compile-time metadata comes from Helidon codegen (`TypeInfo`, `TypedElementInfo`, `TypeName`, and source annotations), not runtime reflection.

## Common Reducer Categories

The provider needs internal reduction behavior for:

- ignore result for `void`;
- list result;
- stream result;
- optional result;
- single row result;
- scalar result;
- update count;
- generated key;
- batch counts;
- rich batch result;
- stored procedure OUT parameters;
- relationship graph aggregation from dotted row labels;
- composite record construction;
- raw transcript only if approved as user-facing API;
- custom reducer only if approved as user-facing API.

## Row Cardinality

List:

- zero rows -> empty list;
- one or more rows -> mapped list.

Optional:

- zero rows -> `Optional.empty()`;
- one or more rows -> `Optional.of(first mapped row)`.

Single:

- zero rows -> `null` for reference returns;
- one or more rows -> first mapped row.

Scalar:

- select first column of first row;
- zero rows -> `null` for boxed/reference scalar returns, and an error for primitive return types;
- more than one column is allowed only when first-column semantics are documented for the return type.

Declarative generated code should choose terminals/reducers that match the repository method shape. For example, a
reference return may use a null-allowing reducer, while a primitive scalar return must use a reducer that fails when the
query has no row.

Default item query semantics are first-row semantics. Add a stricter exact-one result shape only if an explicit API is
approved.

## Update Counts

Internal update counts are `long`.

Return behavior:

- `int` and `Integer` require exact conversion;
- `long` and `Long` return the full count;
- `boolean` and `Boolean` return whether the selected count is greater than zero;
- `void` ignores the count but still executes and records the transcript.
- update operations must not map generated keys unless generated-key metadata was declared.

Default selected-count policy for a single step:

```text
sum all update count events in the selected step
```

Offer stricter behavior internally when a method requires exactly one count.

## Generated Keys

Generated key reducers read `GeneratedKeysEvent`.

Rules:

- generated keys are produced only for operations that explicitly request them;
- scalar key -> first selected column;
- optional key -> zero or one key row;
- key list -> all key rows;
- key record -> generated mapping by column labels;
- missing required key -> semantic missing-generated-key exception.

Batch generated keys must preserve driver-returned order. Do not claim item correlation unless the runtime can verify it.

## Batch Results

Batch reductions must handle:

- numeric counts;
- `SUCCESS_NO_INFO`;
- `EXECUTE_FAILED`;
- `NOT_ATTEMPTED`;
- generated keys;
- partial failure transcripts.

For JDBC-compatible `int[]`, returning JDBC sentinel values is acceptable.

For non-lossy reporting, prefer an internal or approved public rich result type that preserves item status.

## OUT Parameters

OUT parameter reductions read `OutParamsEvent`.

Rules:

- scalar return is valid only when the call has exactly one selected non-cursor OUT parameter;
- record results use generated component mapping;
- cursor OUT parameters are rows events and map like result sets;
- missing OUT parameter -> semantic missing-out-param exception.

## Composite Records

Composite records are useful for methods that return more than one JDBC outcome, such as update count plus generated key.

No runtime record introspection is allowed. Codegen must generate constructor calls and component extraction.

Example:

```java
record InsertPokemonResult(long affectedRows, long id) {
}
```

Generated reduction should be equivalent to:

```java
return new InsertPokemonResult(
        updateCount(transcript, "insert"),
        generatedKey(transcript, "insert", "ID", Long.class));
```

If component annotations are added to `Data`, validate and read them at compile time.

## Relationship Graph Aggregation

Relationship graph aggregation is a generated reducer category, not an ordinary one-row mapper.

It is required when SQL labels describe collection-valued paths, for example:

```text
id
name
phones.id
phones.type
phones.tags.id
phones.tags.name
```

Codegen should infer:

- the root type from the repository method return element type;
- each path segment from compile-time type metadata;
- collection element types from declared generic collection types;
- scalar leaves from the final path segment;
- identity keys for root and collection elements, defaulting to `id` only if that policy is approved.

Generated relationship reducers should:

- iterate materialized rows once;
- create or reuse root objects by root identity;
- create or reuse nested collection elements by path identity;
- skip left-joined collection elements when all key columns for that element are `null`;
- preserve encounter order for root and collection results;
- construct or mutate objects only through compile-time-known constructors, factories, setters, fields, collection getters,
  or add-methods;
- never use reflection or expose JDBC resources.

Relationship aggregation should be selected automatically when any mapped path crosses a collection-valued member. A
plain row mapper remains valid for flat DTO/record projections.

## Result Selection

Reducers need a shared selection model, even if implemented internally.

Current built-in query reducers support the first single-step/single-result path: one selected step with exactly one
`RowsEvent`. Multi-result traversal is deferred under `JDBC-REDUCERS-002` in `docs/codex/open-items.md` and must use the
selection model below rather than ad hoc per-feature traversal.

Selection attributes:

- step name;
- step index;
- result name;
- ordinal;
- role.

Selection order:

1. select step by explicit name;
2. select step by explicit index;
3. use the only step;
4. fail if multiple steps remain;
5. select event by explicit name, ordinal, or role;
6. use reducer default;
7. fail if ambiguous.

## Conversion

Use explicit conversion logic, not reflection.

Rules:

- `null` into primitive is an error;
- numeric narrowing must be exact unless a documented policy says otherwise;
- enum conversion should be opt-in or generated from known target type;
- temporal conversions must be explicit and tested;
- JDBC driver-specific values should be normalized before user mapping where practical.

## Exceptions

Prefer existing Helidon `DataException` for public failures, with JDBC-provider-specific subclasses only when they add value.

Semantic failures should include:

- repository method or operation when known;
- reducer or return kind;
- expected transcript shape;
- actual transcript summary;
- selected step or result name.

Suggested internal names:

```text
NoSuchResultException
TooManyRowsException
MissingResultSetException
MissingGeneratedKeyException
MissingOutParamException
InvalidTranscriptShapeException
DataMappingException
```

Do not expose all of these publicly unless they are intended API.

## Custom Reducers

Custom user reducers are an advanced feature and should not be added before the basic declarative provider works.

If custom reducers are added:

- define a minimal public interface;
- instantiate through generated direct construction or Helidon service registry;
- do not use reflection to create reducer instances;
- validate compatibility at compile time where possible.
