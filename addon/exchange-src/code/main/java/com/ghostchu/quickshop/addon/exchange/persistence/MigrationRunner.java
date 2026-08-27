package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Applies the version-one schema and records the version only after every table and index exists.
 *
 * <p>SQLite executes the migration as one transaction. MySQL implicitly commits DDL, so recovery is
 * forward-only: every DDL statement plus index and trigger check is idempotent, and a retry resumes
 * a partially applied migration before inserting the version row. MySQL installations with binary
 * logging must allow trigger creation, for example with
 * {@code log_bin_trust_function_creators=1}, or grant the migration user equivalent administrative
 * privileges.</p>
 */
public final class MigrationRunner {
  private final ConnectionProvider connections;
  private final SqlDialect dialect;
  private final TableNames tables;

  public MigrationRunner(ConnectionProvider connections, SqlDialect dialect, TableNames tables) {
    this.connections = connections;
    this.dialect = dialect;
    this.tables = tables;
  }

  public void migrate() throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
        for (String sql : SchemaV1.statements(dialect, tables)) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
          }
        }
        for (SchemaV1.IndexDefinition index : SchemaV1.indexes(tables)) {
          ensureIndex(connection, index);
        }
        for (SchemaV1.TriggerDefinition trigger : SchemaV1.triggers(dialect, tables)) {
          ensureTrigger(connection, trigger);
        }
        recordVersion(connection, 1);
        for (SchemaV2.ColumnDefinition column : SchemaV2.columns(dialect, tables)) {
          ensureColumn(connection, column);
        }
        recordVersion(connection, 2);
        for (String sql : SchemaV3.statements(dialect, tables)) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
          }
        }
        for (SchemaV1.TriggerDefinition trigger : SchemaV3.triggers(dialect, tables)) {
          ensureTrigger(connection, trigger);
        }
        recordVersion(connection, 3);
        for (String sql : SchemaV4.statements(dialect, tables)) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
          }
        }
        for (SchemaV1.IndexDefinition index : SchemaV4.indexes(tables)) {
          ensureIndex(connection, index);
        }
        for (SchemaV1.TriggerDefinition trigger : SchemaV4.triggers(dialect, tables)) {
          ensureTrigger(connection, trigger);
        }
        ensureColumn(connection, new SchemaV2.ColumnDefinition(
            tables.markets(), "asset_type", "VARCHAR(24) NOT NULL DEFAULT 'PHYSICAL_ITEM'"));
        recordVersion(connection, 4);
        connection.commit();
      } catch (SQLException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private void recordVersion(Connection connection, int version) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO " + tables.schemaVersion()
            + " (version,applied_at) SELECT ?,? WHERE NOT EXISTS "
            + "(SELECT 1 FROM " + tables.schemaVersion() + " WHERE version=?)")) {
      insert.setInt(1, version);
      insert.setLong(2, Instant.now().toEpochMilli());
      insert.setInt(3, version);
      insert.executeUpdate();
    }
  }

  private static void ensureColumn(Connection connection, SchemaV2.ColumnDefinition column)
      throws SQLException {
    boolean exists;
    try (ResultSet result = connection.getMetaData()
        .getColumns(null, null, column.table(), column.name())) {
      exists = result.next();
    }
    if (!exists) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE " + column.table() + " ADD COLUMN "
            + column.name() + " " + column.type());
      }
    }
  }

  private static void ensureIndex(Connection connection, SchemaV1.IndexDefinition index)
      throws SQLException {
    boolean exists = false;
    try (ResultSet result = connection.getMetaData()
        .getIndexInfo(null, null, index.table(), false, false)) {
      while (result.next()) {
        if (index.name().equalsIgnoreCase(result.getString("INDEX_NAME"))) {
          exists = true;
          break;
        }
      }
    }
    if (!exists) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE INDEX " + index.name() + " ON "
            + index.table() + " (" + index.columns() + ")");
      }
    }
  }

  private void ensureTrigger(Connection connection, SchemaV1.TriggerDefinition trigger)
      throws SQLException {
    String existsSql = dialect == SqlDialect.SQLITE
        ? "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?"
        : "SELECT 1 FROM INFORMATION_SCHEMA.TRIGGERS"
            + " WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?";
    boolean exists;
    try (PreparedStatement query = connection.prepareStatement(existsSql)) {
      query.setString(1, trigger.name());
      try (ResultSet result = query.executeQuery()) {
        exists = result.next();
      }
    }
    if (!exists) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(trigger.sql());
      }
    }
  }
}
