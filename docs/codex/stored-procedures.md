# Stored Procedures and CallableStatement

## Purpose

Stored procedure invocation is an advanced JDBC provider feature. It should be implemented after basic query and update support.

Procedure invocation uses call semantics and `CallableStatement`.

Procedure definition is DDL and should use update or script semantics. Do not add a create-procedure annotation.

## Public API Position

Do not add stored-procedure annotations until the shared Data API decision is made.

Candidate annotations, if approved:

```java
@Data.Call
@Data.Out
@Data.InOut
@Data.OutCursor
@Data.OutParam
@Data.ResultSetParam
```

These annotations must be consumed by codegen. Runtime annotation scanning is not allowed.

## Invocation Shape

Example, if call annotations are approved:

```java
record ProcedureResult(String status, int total) {
}

@Data.Call("{ call summarize_pokemon(:typeId, :status, :total) }")
@Data.Out(name = "status", type = JDBCType.VARCHAR)
@Data.Out(name = "total", type = JDBCType.INTEGER)
ProcedureResult summarize(long typeId);
```

Generated code must explicitly bind input parameters, register OUT parameters, and construct `ProcedureResult` from the transcript.

## Cursor OUT Parameters

Cursor OUT values must become row events.

They must not appear as public `ResultSet` values and must not be stored as live `ResultSet` objects in OUT parameter events.

Conceptual transcript:

```text
Step 0 CALL
  RowsEvent ordinal=0 role=OUT_CURSOR name=rows
  OutParamsEvent ordinal=1 values=status
```

## Runtime Sequence

Recommended sequence:

```text
prepare call
bind IN params
register OUT params
register INOUT params
register cursor OUT params
execute
consume direct result sequence with getMoreResults
read cursor OUT params and materialize rows
read scalar OUT params
collect warnings
return transcript
```

Some drivers require direct result sets to be consumed before OUT parameters are read.

## OUT Parameter Model

OUT metadata should preserve:

- name when declared;
- index when declared;
- JDBC type;
- optional database type name;
- value.

Type conversion happens in generated mapping/reduction code, not while reading the raw OUT value unless the driver requires normalization.

## Cursor Strategy

Cursor registration is not fully portable across databases.

Keep vendor-specific behavior behind an internal strategy, for example:

```text
CursorOutParamStrategy
```

The strategy may handle:

- JDBC type used for cursor registration;
- reading cursor values by name or index;
- driver-specific object-to-result-set conversion.

Do not make the whole runtime vendor-specific.

## Direct Results and OUT Params

Procedures may produce:

- direct result sets;
- update counts;
- cursor OUT result sets;
- scalar OUT values;
- warnings.

Preserve direct result-set and update-count order according to JDBC. Cursor and scalar OUT values may be appended after direct results if the driver requires late reading.

## Return Semantics

Allowed return categories, if call support is approved:

```text
void
single scalar OUT value
record constructed from OUT params and result sets
approved generic out-params object
approved raw transcript
custom reducer if public custom reducers exist
```

Scalar return is valid only when one non-cursor OUT parameter is selected.

Records must be constructed by generated code without reflection.

## Validation

Compile-time:

- call annotations only on call operations;
- OUT names/indexes unique;
- INOUT parameters have input source;
- result records are constructible;
- cursor result mappings target row-compatible types;
- scalar returns are unambiguous.

Runtime:

- missing OUT parameter -> semantic failure;
- missing cursor rows -> semantic failure;
- unexpected multiple result sets without selector -> shape failure;
- conversion failure -> mapping failure.

## Tests

Stored procedure tests should cover:

- call with no OUT params;
- one OUT param;
- multiple OUT params;
- INOUT param;
- cursor OUT param when supported by test database;
- direct result set;
- mixed update count and result set;
- missing OUT param;
- cursor materialization;
- provider coexistence with Jakarta.
