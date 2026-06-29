# Validation

## Purpose

Validation keeps the declarative API predictable and prevents generated JDBC code from reaching unsupported runtime shapes.

Prefer compile-time validation in the JDBC codegen provider. Runtime validation remains necessary for data-dependent outcomes such as row count and generated-key availability.

Validation and diagnostics should follow the style already used in `data/codegen` and provider modules. Before adding new
diagnostic structures or helpers, inspect existing Data codegen validation patterns and reuse them where appropriate.

## Validation Layers

Compile-time validation checks:

- provider selection;
- annotation combinations;
- method signatures;
- return types;
- parameter bindings;
- mapper generation feasibility;
- generated key declarations;
- batch source shape;
- stored procedure OUT declarations;
- multi-step operation names.

Runtime validation checks:

- actual result-set presence;
- actual row cardinality;
- generated-key rows and columns;
- OUT parameter availability;
- batch sentinel values;
- transcript shape.

## Provider Selection

The JDBC provider should generate an implementation only when selected by:

- `@Data.Provider("jdbc")`;
- provider configuration under `data.persistence-units.jdbc`;
- an unambiguous default provider rule.

The Jakarta provider must continue to generate for its selected repositories. Provider coexistence tests are required once JDBC codegen exists.

Configuration-selected JDBC persistence units are required. Validate:

- every configured JDBC persistence unit has a non-empty `name`;
- every configured JDBC persistence unit has a `data-source` reference;
- every `data-source` reference resolves to a configured source under `data.sources`;
- repository `@Data.PersistenceUnit` values resolve to a JDBC persistence unit when provider `jdbc` is selected;
- default persistence unit selection is unambiguous;
- `init-script`, when present, is treated as an optional provider initialization resource and failures are reported clearly.

## Reflection Ban

Validation must reject cases that would require runtime reflection to make work.

Examples:

- DTO return type with no generated mapper path;
- record result requiring runtime component inspection;
- custom reducer requiring reflective construction;
- bean binding that cannot be generated from compile-time metadata.

## `@Data.Query` Validation

For JDBC SQL row-result operations, allowed return categories:

```text
List<T>
Stream<T>
Optional<T>
T
scalar primitive/wrapper/String
void for update-count/DDL execution where the count is ignored
```

Compile-time checks:

- row type is mappable by generated code;
- scalar type is convertible;
- named SQL parameters have binding sources;
- stream return is supported by the runtime;
- unsupported generic shapes fail;
- explicit `void @Data.Query` emits an update-count terminal and ignores the count;
- stored procedure calls require an approved call model before they are supported.

Runtime checks:

- optional returns `Optional.empty()` on zero rows and maps the first row when rows exist;
- collection/list returns an empty result on zero rows;
- single reference return maps the first row, or returns `null` on zero rows;
- boxed/reference scalar returns map the first row, or return `null` on zero rows;
- primitive scalar returns map the first row and fail on zero rows because `null` cannot be returned;
- missing result set fails unless the transcript contains update counts, in which case scalar row mapping receives a
  synthetic one-column row named `updateCount`.

## Update Validation

Update operations may come from repository method parsing or from imperative update-count terminals.

Follow an annotation-first model inspired by mature SQL object APIs: update-like operations return affected row counts by
default; generated keys are a separate explicit opt-in. Do not infer generated-key behavior from a scalar or DTO return
type.

Current JDBC codegen should treat method-name delete/update intent as update-like because that intent comes from Helidon
Data method parsing. Explicit SQL in `@Data.Query` must not become update-like by keyword classification; it uses
row-shaped terminals and relies on transcript reduction to map update counts for scalar count-like return types.

Allowed return categories:

```text
void
int / Integer
long / Long
boolean / Boolean
approved rich update result
generated-key return when generated keys are declared
```

Compile-time checks:

- DTO/list/stream returns are invalid unless generated keys, returning rows, or custom reduction is explicitly declared;
- scalar key/DTO returns from an update require generated-key metadata;
- without generated-key metadata, `int`/`Integer`, `long`/`Long`, `boolean`/`Boolean`, and `void` have affected-count
  semantics;
- generated-key columns are non-empty when provided;
- generated-key return type is compatible.

Runtime checks:

- `int` count must not overflow;
- `boolean` returns whether the affected count is greater than zero;
- missing count fails when count is required;
- missing generated key fails when key is required.

## Batch Validation

Batch operations require:

- one SQL statement;
- a batch source parameter;
- a generated item binder.

Supported source patterns can start with:

```text
Iterable<T>
List<T>
T[]
```

Add `Stream<T>` only when safe consumption and resource handling are defined.

Allowed return categories:

```text
void
int[]
long[]
List<Integer>
List<Long>
approved rich batch result
generated-key list when generated keys are declared
```

Runtime checks:

- item outcome count matches input size where the driver reports enough detail;
- numeric reducers handle `SUCCESS_NO_INFO` according to documented policy;
- partial failures carry transcript.

## Stored Procedure Validation

Call operations require declared OUT metadata for OUT values.

Compile-time checks:

- OUT names or indexes are unique;
- INOUT parameters have input bindings;
- cursor OUT parameters are mapped as rows;
- scalar return has exactly one selected non-cursor OUT parameter;
- record returns can be constructed by generated code.

Runtime checks:

- OUT parameter exists;
- cursor rows were materialized;
- ambiguous result-set selection fails;
- conversion failures are semantic mapping errors.

## Multi-Step Validation

If repeatable operation annotations or generated multi-step methods are supported:

1. every step must have a non-empty unique name;
2. generated code must build a plan;
3. return type must identify how each component maps to steps;
4. unqualified DTO and scalar returns are invalid;
5. step names in result-shaping metadata must match actual step names.

## Parameter Binding Validation

Rules:

- every named SQL parameter has exactly one binding source after generated bean expansion;
- duplicate SQL parameter occurrences are allowed;
- ambiguous sources fail;
- missing method parameter names require explicit binding metadata;
- unused parameters should fail unless explicitly allowed;
- `null` binding behavior must be explicit for primitive and non-nullable contexts.
- JDBC SQL must not mix named, ordinal, and positional bind marker styles;
- ordinal bind markers must preserve the SQL placeholder order, so `$2, $1` binds method parameter 2 before method
  parameter 1;
- parser behavior must ignore bind-like text inside SQL comments and quoted text.

## Mapper Validation

Generated row mappers require:

- accessible constructor or factory pattern known at compile time;
- record canonical constructor metadata from source model;
- matching column labels or explicit mapping metadata;
- supported type conversions.

Do not fall back to reflective field or method access.

Label matching is the simple default, not the only supported target model. When explicit mapping metadata or an approved
mapper is present, codegen must use that metadata to map SQL columns to constructor parameters, record components, or
factory parameters without requiring SQL aliases to match Java names.

Explicit mapping validation should reject:

- missing target components or constructor/factory parameters;
- duplicate mappings for the same target;
- source columns that cannot be selected by the approved label/name model;
- mapper return type mismatches with the repository method element type;
- ordinary row mappers used for multi-row aggregation or multi-result transcript shaping.

Keep mapper validation aligned with a small annotation surface: prefer convention and target member/parameter column-name
overrides, and avoid repository-method mapping arrays or ordinal mapping unless a later API review explicitly approves
them.

Dotted SQL labels for relationship aggregation are a required future capability. When a label path crosses a
collection-valued member, codegen must select a generated relationship reducer and validate:

- root type from the repository method return element type;
- each dotted path segment resolves to a known member, constructor parameter, factory parameter, or explicit mapping;
- collection members expose a compile-time-known element type;
- each root and collection element has an identity key, defaulting to `id` only if that policy is approved;
- left-join null-key handling is defined for optional relationships;
- target objects can be constructed and populated without reflection;
- plain row-mapper return shaping is rejected when collection paths require aggregation.

Validate only the final supported form at compile time. Obsolete sample annotations should be rejected with actionable
diagnostics if users try to use them.

## Diagnostics

Diagnostics should include:

- repository interface;
- method name;
- provider name;
- annotation involved;
- return type;
- invalid part;
- suggested fix.

Example:

```text
JDBC provider cannot map method PokemonRepository.findAllNames to List<NameDto>.
No generated row mapper is available for NameDto. Use a record projection, add an approved mapper annotation, or change the return type.
```

Example:

```text
JDBC provider found SQL parameter :name in method findByName but no method parameter or binding source named name.
```

## Runtime Exceptions

Public runtime failures should align with Helidon Data exception policy.

Use semantic provider exceptions only when they are worth exposing. Otherwise wrap with `DataException` and include a clear message.

Every transcript-shape exception should include a concise transcript summary.
