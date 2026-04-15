# Conventions

- In general, classes and interfaces that begin with `Jdbc` throw `java.sql.SQLException`s.
- In general, any class suffixed with `Impl` is a package-private implementation of a corresponding `public` interface.
- This module is (deliberately) heavily compositional. Convenience methods and constructors exist only to adapt certain
  JDBC constructs to this module's constructs.
- In general, given a `JdbcX` interface, there exists an `X` interface that extends it and overrides its methods to
  remove the `throws java.sql.SQLException` clause.
- Some classes and interfaces in this module are <dfn>views</dfn> on underlying JDBC-native constructs. For example, an
  `io.helidon.data.jdbc.ResultSetRowView` is an unmodifiable <dfn>view</dfn> of a `java.sql.ResultSet`.
- Some classes and interfaces in this module represent <dfn>capabilities</dfn> of an underlying, too-broad JDBC-native
  construct. For example, `io.helidon.data.jdbc.StatementFactory` represents the <dfn>capability</dfn> of a
  `java.sql.Connection` to perform `java.sql.Statement`-related work, and no other kind of work.

