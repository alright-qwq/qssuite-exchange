package com.ghostchu.quickshop.addon.exchange.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;

public final class SqliteTestDatabase {
  private SqliteTestDatabase() {}
  public static ConnectionProvider at(Path file) {
    return new SqliteConnectionProvider(
        () -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()));
  }
}
