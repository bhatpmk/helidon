Helidon Data
----

Repository based database access.

# Glossary

| Name             | Description                                                                                                          |
|------------------|----------------------------------------------------------------------------------------------------------------------|
| Repository       | Annotated interface with methods named according to a specification; implementation is code generated                |
| Provider         | A persistence provider that provides a codegen to generate repository implementations, and runtime to handle methods |
| Data Source      | A named service implementing a data source contract (such as `javax.sql.DataSource`)                                 |
| Persistence unit | A named configurable component used by a Provider, such as JPA Persistence Unit                                      |

# Implementation notes

Summary for SQL based:
- (1 - n) Databases (not part of Helidon code or configuration)
- (1 - n) Data Sources - each represents a connection (or connection pool) to a Database (m : n)
- (1 - n) Persistence Units - each represents an approach of invoking statements on the connection, and uses a Data Source (m : n)
- (1 - n) Entities - each represents a table in a Database (0 - 1 : 1) / there may be a table that does not have an entity
- (1 - n) Repository interfaces - each represents operations on an Entity (0 - 1 : 1) / there may be an entity that does not have a repository
- (1 - n) Repository implementations - for each persistence unit provider that is on codegen path

## Data Source

Each configured data source is available in `ServiceRegistry` under its configured name. There can be one data source that is
unnamed of each data source type (such as `javax.sql.DataSource`).

Data sources are expected to be used by `Persistence units`.

## Provider and Repository

A `Provider` codegen module generates implementations of `Repository` interfaces.
For each repository, there is a named instance with the provider name (i.e. `jakarta-persistence`), and an unnamed instance
with the weight of provider.
`Repository` interface may have a `Data.Provider` annotation with the `Provider` name, to limit code generation only to that 
provider.

User chooses at injection time which instance they desire (if there is more than one option). If an unnamed instance is injected,
the one with the highest weight will be used. They can also use `@Service.Named("...")` with the provider name to inject
the specific provider based instance.

### JDBC Repository Provider

The JDBC repository provider generates repository implementations for methods
annotated with `@Data.Query`. SQL is supplied explicitly in the annotation and
is executed through Helidon JDBC runtime contracts. The provider supports named
parameters such as `:name` and positional markers such as `?`; named parameters
are resolved from method arguments or readable argument properties at build
time. Unresolved SQL parameters and unused repository method parameters fail
code generation with method-specific diagnostics.

Generated JDBC repositories are provider-specific services named `jdbc` and use
`JdbcOperations` injection. The JDBC provider does not depend on Jakarta
Persistence and can be present with the Jakarta provider in the same build. Use
provider selection or service names when an application includes multiple
repository providers.

#### JDBC Joined Result Reduction

JDBC repositories can reduce joined rows into aggregate object graphs when SQL
aliases use dotted result labels. The root object uses labels such as `id` and
`name`; collection elements use paths such as `phones.id`, `phones.phone`, and
`phones.tags.name`. The reducer uses `id` by convention for the root and each
collection path, suppresses duplicate child rows, and ignores left-join child
rows whose key columns are all `NULL`.

Use `@Data.Key` when the identity label is not the conventional `id`, and use
`@Data.ReduceWith` with a `@Data.Mapper` contract when aliases need explicit
source-to-target mapping. Reducers are generated code and do not use runtime
reflection.

#### JDBC Data-Changing Methods and Generated Keys

JDBC repository methods whose SQL starts with data-changing statements such as
`insert`, `update`, or `delete` are generated as update operations. Supported
return shapes are `void`, `long`/`Long`, `int`/`Integer`, and
`boolean`/`Boolean`; boolean methods return `true` when the update count is
greater than zero.

Annotate an insert or other data-changing method with `@Data.GeneratedKeys` to
read a generated key. Empty `@Data.GeneratedKeys` requests the JDBC driver's
default generated-key behavior, while explicit values such as
`@Data.GeneratedKeys("id")` pass column names to JDBC. Generated-key
annotations on query methods fail during code generation.

## Persistence Unit and Repository

Each provider may require additional configuration. This is achieved through a persistence unit, which is expected to 
reference a `Data Source` to be used, and possible additional configuration (such as JPA properties).

`Repository` interface may have a `Data.Pu` annotation with the persistence unit name, to require a named PU at runtime.
Note that the PU must be of the same type as the Provider

## Configuration

There are following new nodes in the configuration:

- `data.sources.sql` - a list of SQL data sources (implement `javax.sql.DataSource`)
- `data.persistence-units` - a section for persistence units
- `data.persistence-units.jakarta` - a list of Jakarta Persistence units configurations
- `data.persistence-units.jdbc` - a list of JDBC repository persistence unit configurations

### Persistence unit with custom connection

An example of Helidon Data persistence unit with custom database connection. 

```yaml
data:
  persistence-units:
    jakarta:
      - connection:
          username: "user"
          password: "changeit"
          url: "jdbc:mysql://localhost:3306/pets"
          jdbc-driver-class-name: "com.mysql.cj.jdbc.Driver"
        init-script: "init.sql"
        properties:
          eclipselink.target-database: "MySQL"
          eclipselink.target-server: "None"
          jakarta.persistence.schema-generation.database.action: "drop-and-create"
```

### JDBC persistence unit

JDBC repositories use `data.persistence-units.jdbc`. A JDBC persistence unit
can either reference a named SQL `DataSource` from the service registry or
define an inline JDBC connection. A single configured JDBC persistence unit is
also registered as the default `JdbcOperations` service; multiple units must be
selected with `@Data.PersistenceUnit`.

```yaml
data:
  sources:
    sql:
      - name: "example"
        provider.hikari:
          username: "user"
          password: "changeit"
          url: "jdbc:mysql://localhost:3306/pets"
          jdbc-driver-class-name: "com.mysql.cj.jdbc.Driver"
  persistence-units:
    jdbc:
      - name: "example"
        data-source: "example"
        init-script: "init.sql"
        drop-script: "drop.sql"
```

`init-script` and `drop-script` may point to classpath resources or file-system
paths. The JDBC runner executes semicolon-delimited SQL statements and supports
line comments, block comments, and quoted semicolons. It is intended for simple
schema setup and test data, not for vendor migration tooling.

### Persistence unit with DataSource

An example of Helidon Data persistence unit with DataSource. In this configuration, DataSource is defined in a separate node
and persistence unit references just the name of this DataSource. Configured DataSource is available in the `ServiceRegistry`
and also trough JNDI to be available for Jakarta Persistence provider, such as EclipseLink and Hibernate.

```yaml
data:
  sources:
    sql:
      - name: "example"
        provider.hikari:
          username: "user"
          password: "changeit"
          url: "jdbc:mysql://localhost:3306/pets"
          jdbc-driver-class-name: "com.mysql.cj.jdbc.Driver"
  persistence-units:
    jakarta:
      - data-source: "example"
        init-script: "init.sql"
        drop-script: "drop.sql"
        properties:
          jakarta.persistence.schema-generation.database.action: "drop-and-create"
```

## Services in Service Registry

Always
- Repository instance annotated with `@Data.Repository`, named with provider name, or unnamed

When `helidon-data-sql-datasource` is on classpath (and at least one provider of it, such as `hikari` or `ucp`)
- `javax.sql.DataSource` for each `data.sources.sql` configuration (named or unnamed) `io.helidon.data.sql.datasource.DataSourceConfigFactory.SQL_DATA_SOURCES_CONFIG_KEY`

When `helidon-data-jakarta-persistence` is on classpath
- `jakarta.persistence.EntityManagerFactory` for each `persistence-untis.jakarta` configuration (named or unnamed) `io.helidon.data.jakarta.persistence.PersistenceUnitFactory.JPA_PU_CONFIG_KEY`
- `jakarta.persistence.EntityManager` dtto., this should be injected as a `Supplier<EntityManager>` for cases that require it being closed; note that `@PersistenceContext` annotation is NOT supported in Helidon Data, only `@Service.Inject`

# Testing

We need to support test containers running on random ports.

This will be supported by defining a single SQL data source, such as:

```yaml
# this section is required for our testcontainer support
test.database:
  username: "test"
  password: "changeit"
  url: "jdbc:mysql://localhost:3306/testdb"

# this section is the usual Helidon Data setup of data sources and persistence units
data:
  sources:
    sql:
      - name: "test"
        provider.hikari:
          username: "${test.database.username}"
          password: "${test.database.password}"
          url: "${test.database.url}"
  persistence-units:
    jakarta:
      - data-source: "test"
```

This information will be read by `io.helidon.data.sql.testing.SqlTestContainerConfig.configureContainer(io.helidon.config.Config, org.testcontainers.containers.JdbcDatabaseContainer<?>)` and a container will be initialized with it.
The method returns a `TestContainerHandler` that can be used to start and stop the container, and to get the new mapped port. Its method `setConfig()` can be called to register the config instance with updated port numbers in ServiceRegistry
