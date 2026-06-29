# Examples

## External Reference Samples

The samples at:

```text
/Users/pabhat/go/src/github.com/bhatpmk/helidon-examples/examples/declarative/data-jdbc
```

are reference material only. They are not an implementation contract and should not drive the provider design, public API,
or implementation order.

They can be useful to understand possible application shapes:

- `pokemon`: SQL repositories, named and positional binding, generated keys, DML through repository methods, nested row labels, and invalid SQL failure propagation;
- `mappers`: generated row mapping, aggregate reduction from joined rows, explicit mapping contracts, and summary projections.

Do not preserve sample-only annotation names for compatibility. In particular, annotations such as `@Data.Map`,
`@Data.Mapper`, `@Data.MapWith`, `@Data.ReduceWith`, and `@Data.Key` may be invalid in the final API.

Do not run these examples for every JDBC provider change. Run them only when a task explicitly asks for example validation
or when the implementation is mature enough for manual end-to-end checks.

## Select Provider

```java
@Data.Repository
@Data.Provider("jdbc")
interface PokemonRepository extends Data.GenericRepository<Pokemon, Long> {
}
```

`@Data.Provider("jdbc")` selects the JDBC provider. Jakarta repositories continue to use their existing provider selection.

## SQL Query

```java
@Data.Query("""
SELECT ID, NAME, TYPE_ID
FROM POKEMON
ORDER BY NAME
""")
List<PokemonRow> listOrderByName();
```

Generated code should delegate to `JdbcClient` and generated mapper code. It must not contain `ResultSet` loops.

Conceptual generated shape:

```java
return jdbcClient.execute(SQL_LIST_ORDER_BY_NAME)
        .params(bindings)
        .list(PokemonRowMapper.INSTANCE);
```

Provider selection by configuration is required. A JDBC persistence unit is configured under `data.persistence-units.jdbc`
and references a DataSource configured under `data.sources`:

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

Generated repositories should select the matching configured JDBC persistence unit by provider/default rules and resolve
the `data-source` before execution.

## Scalar Query

```java
@Data.Query("SELECT COUNT(*) FROM POKEMON")
long countPokemon();
```

Reduction:

```text
RowsEvent -> one row -> first column -> long
```

## Optional Row

```java
@Data.Query("SELECT ID, NAME FROM POKEMON WHERE ID = :id")
Optional<PokemonRow> findById(long id);
```

Generated code binds `:id` from the method parameter and maps zero or one row.

## Named Parameter Reuse

```java
@Data.Query("""
SELECT ID, NAME
FROM POKEMON
WHERE NAME = :name OR ALIAS = :name
""")
List<PokemonRow> findByNameOrAlias(String name);
```

Runtime SQL:

```sql
SELECT ID, NAME
FROM POKEMON
WHERE NAME = ? OR ALIAS = ?
```

Binding order:

```text
name
name
```

Explicit parameter binding may be supported when Java parameter names are not sufficient. The external sample uses both
named and positional parameter annotations, but those exact annotation names are not fixed by this document.

## Query Return Shaping

For query-like methods, return type shapes row reduction:

```java
@Data.Query("SELECT ID, NAME FROM USERS WHERE ID = :id")
User findUser(long id);              // first row, or null when no row exists

@Data.Query("SELECT ID, NAME FROM USERS WHERE ID = :id")
Optional<User> findOptional(long id); // Optional.empty() when no row exists

@Data.Query("SELECT ID, NAME FROM USERS")
List<User> findUsers();              // empty list when no rows exist

@Data.Query("SELECT COUNT(*) FROM USERS WHERE ACTIVE = TRUE")
int countActive();                   // primitive scalar; no row is an error

@Data.Query("SELECT AGE FROM USERS WHERE ID = :id")
Integer findAge(long id);            // boxed scalar; first row, or null when no row exists
```

## Update Count SQL

```java
@Data.Query("UPDATE POKEMON SET NAME = :name WHERE ID = :id")
int rename(long id, String name);
```

Reduction:

```text
UpdateCountEvent -> synthetic row value "updateCount" -> exact int
```

By default update-like methods return affected row counts:

```text
void       -> execute and ignore affected count
boolean    -> affected count > 0
int        -> exact affected count
long       -> large affected count
```

A row/DTO return from an update is invalid unless generated keys or another explicit returning-rows feature is declared:

```java
// Invalid without generated-key metadata.
@Data.Query("UPDATE USERS SET NAME = :name WHERE ID = :id")
User update(long id, String name);
```

## Generated Key, If Approved

```java
@Data.Query("INSERT INTO POKEMON(NAME) VALUES (:name)")
@Data.GeneratedKeys("ID")
long insert(String name);
```

Reduction:

```text
GeneratedKeysEvent -> one row -> ID column -> long
```

With generated-key metadata, generated code should execute using generated-key support and map the generated-key result set
to the declared return type.

## Batch, If Approved

```java
@Data.Batch("INSERT INTO POKEMON(NAME, TYPE_ID) VALUES (:name, :typeId)")
int[] insertAll(List<PokemonCreate> rows);
```

Generated code creates an item binder for `PokemonCreate` at compile time.

## Nested Row Mapping

The JDBC provider should support automatic relationship reducers from dotted SQL labels.

Example model:

```java
class Contact {
    Long id;
    String name;
    List<Phone> phones = new ArrayList<>();
}

class Phone {
    Long id;
    String type;
    String phone;
    List<Tag> tags = new ArrayList<>();
}
```

Example query:

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

Codegen should infer:

- root type `Contact` from `List<Contact>`;
- `phones` is a collection of `Phone`;
- `phones.tags` is a collection of `Tag`;
- leaf labels map to scalar fields, properties, constructor parameters, or factory parameters;
- a generated reducer is required because collection paths exist.

Reduction should de-duplicate roots and nested collection elements by generated identity keys, skip null left-joined child
keys, preserve encounter order, and construct/populate the object graph without reflection. Dotted labels remain useful
even if explicit mapper metadata is also supported for non-path aliases.

## Explicit Row Mapping, If Approved

SQL column labels should not be the only way to map rows to Java values.

Conceptual shape:

```java
record PokemonRow(int id,
                  @Data.Column("NAME") String pokemonName,
                  @Data.Column("TYPE_ID") int typeId) {
}

@Data.Query("SELECT ID, NAME, TYPE_ID FROM POKEMON")
List<PokemonRow> list();
```

Generated code should use the mapping metadata to emit direct row access:

```java
return jdbcClient.execute(SQL)
        .list(row -> new PokemonRow(row.value("ID", Integer.class),
                                    row.value("NAME", String.class),
                                    row.value("TYPE_ID", Integer.class)));
```

The annotation names above are illustrative only. The requirement is compile-time, reflection-free mapping metadata or an
approved `JdbcRowMapper<T>` selection path with a small annotation surface.

## Stored Procedure, If Approved

```java
record ProcedureResult(String status, int total) {
}

@Data.Call("{ call summarize_pokemon(:typeId, :status, :total) }")
@Data.Out(name = "status", type = JDBCType.VARCHAR)
@Data.Out(name = "total", type = JDBCType.INTEGER)
ProcedureResult summarize(long typeId);
```

Generated code registers OUT parameters and constructs `ProcedureResult` without reflection.

## Multi-Step, If Approved

```java
record PokemonCreateResult(long inserted, PokemonRow pokemon) {
}

@Data.Query(name = "insert", value = "INSERT INTO POKEMON(NAME) VALUES (:name)")
@Data.Query(name = "select", value = "SELECT ID, NAME FROM POKEMON WHERE NAME = :name")
PokemonCreateResult create(String name);
```

This requires explicit generated result shaping. Do not allow unqualified DTO or scalar return types for multi-step methods.
