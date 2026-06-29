# Batch Operations

## Purpose

Batch support represents repeated execution of one JDBC statement, including generated keys and partial failures.

Batch is an advanced feature. Implement it after basic query, update, generated mapping, and named parameter support.

## Public API Position

Do not add public batch annotations until the shared Data API decision is made.

Candidate annotations, if approved:

```java
@Data.Batch
@Data.GeneratedKeys
```

Generated code must consume these annotations at compile time.

## Operation Shape

Conceptual method:

```java
@Data.Batch("INSERT INTO POKEMON(NAME, TYPE_ID) VALUES (:name, :typeId)")
int[] insertAll(List<PokemonCreate> rows);
```

Generated code should:

- build one batch operation;
- generate an item binder for `PokemonCreate`;
- delegate execution through `JdbcClient`;
- reduce the batch transcript to `int[]`.

Generated code must not loop over `PreparedStatement` directly.

## Batch Sources

Initial supported source types:

```text
Iterable<T>
List<T>
T[]
```

Add `Stream<T>` only after resource and exception behavior is specified.

Every item type must have a generated binder. Do not bind fields or methods reflectively.

## Transcript Representation

Batch event conceptual payload:

```text
items
generatedKeys
```

Item status:

```text
UPDATED
SUCCESS_NO_INFO
EXECUTE_FAILED
NOT_ATTEMPTED
```

Status mapping:

| JDBC count value | Status | Update count |
|---|---|---|
| `>= 0` | `UPDATED` | count |
| `Statement.SUCCESS_NO_INFO` | `SUCCESS_NO_INFO` | empty |
| `Statement.EXECUTE_FAILED` | `EXECUTE_FAILED` | empty |

Use `NOT_ATTEMPTED` for known input items after a failure when the driver does not report outcomes for them.

## Return Semantics

Allowed return categories, if batch support is approved:

```text
void
int[]
long[]
List<Integer>
List<Long>
approved rich batch result
generated-key list when generated keys are declared
approved raw transcript
custom reducer if public custom reducers exist
```

Ambiguity rule:

- `List<Long>` without generated keys means large update counts;
- `List<Long>` with generated keys may mean generated key values only if validation can make that unambiguous;
- otherwise require explicit result shaping or a custom reducer.

## Generated Keys

JDBC generated keys for batch inserts are driver-dependent.

Rules:

- preserve key rows in returned order;
- do not guarantee item-to-key correlation unless verified;
- missing keys fail only when the return type requires keys;
- rich result should preserve both item outcomes and key rows.

## Partial Failures

On `BatchUpdateException`:

1. read partial counts using `getLargeUpdateCounts()` or `getUpdateCounts()`;
2. convert counts to item outcomes;
3. mark explicitly failed items;
4. mark unreported remaining items as `NOT_ATTEMPTED` unless driver behavior says otherwise;
5. build a batch event;
6. build a partial transcript;
7. throw a transcript-carrying runtime exception.

## Numeric Reducers

`int[]` may return JDBC-compatible sentinel values:

- `Statement.SUCCESS_NO_INFO`;
- `Statement.EXECUTE_FAILED`.

`long[]` needs a documented policy for sentinel values. Prefer failing if a special status cannot be represented without loss.

An approved rich batch result is the preferred non-lossy API.

## Runtime Sequence

Conceptual sequence:

```text
prepare statement with generated-key options if needed
for each input item:
  bind item
  addBatch
execute batch
convert counts
materialize generated keys if requested
return transcript
```

## Validation

Compile-time:

- exactly one batch source;
- item binder can be generated;
- named SQL parameters have item property sources;
- generated-key returns require generated-key declaration;
- unsupported source or return shapes fail.

Runtime:

- item outcomes match input size where possible;
- special statuses handled by selected reducer;
- partial failures preserve transcript.

## Tests

Required tests:

- empty batch;
- one-item batch;
- multi-item batch;
- update counts;
- large update counts;
- `SUCCESS_NO_INFO`;
- `EXECUTE_FAILED`;
- partial failure transcript;
- generated keys;
- generated-key scalar list;
- rich result if approved;
- missing item binding.
