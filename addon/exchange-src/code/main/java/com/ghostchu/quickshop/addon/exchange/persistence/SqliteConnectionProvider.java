package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Enables SQLite connection-scoped integrity settings on every opened connection. */
public final class SqliteConnectionProvider implements ConnectionProvider {
  private final ConnectionProvider delegate;

  public SqliteConnectionProvider(ConnectionProvider delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public Connection open() throws SQLException {
    Connection connection = delegate.open();
    try {
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys = ON");
        try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
          if (!result.next() || result.getInt(1) != 1) {
            throw new SQLException("failed to enable SQLite foreign keys");
          }
        }
      }
      return connection;
    } catch (SQLException | RuntimeException failure) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }
}
