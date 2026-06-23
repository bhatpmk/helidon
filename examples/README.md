<p align="center">
    <img src="../etc/images/Primary_logo_blue.png" height="180">
</p>

# Helidon Examples

Helidon examples have moved to the [helidon-examples](https://github.com/helidon-io/helidon-examples/tree/helidon-4.x) repository.

Helidon Data JDBC repository examples should use the repository model described
in [data/README.md](../data/README.md): annotate repository interfaces with
`@Data.Repository`, use `@Data.Query` for SQL methods, configure JDBC
persistence units under `data.persistence-units.jdbc`, and use
`@Data.PersistenceUnit` when more than one JDBC persistence unit is present.
