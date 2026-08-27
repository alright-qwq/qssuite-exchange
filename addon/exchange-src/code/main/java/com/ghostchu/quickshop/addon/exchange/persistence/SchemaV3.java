package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

/** Append-only audit trail used by operator actions and transfer reviews. */
public final class SchemaV3 {
  private SchemaV3() {}

  public static List<String> statements(SqlDialect dialect, TableNames tables) {
    return List.of("CREATE TABLE IF NOT EXISTS " + tables.auditRecords()
        + " (audit_id " + dialect.uuidType() + " PRIMARY KEY, actor_id " + dialect.uuidType()
        + " NOT NULL, action VARCHAR(48) NOT NULL, target_id VARCHAR(160) NOT NULL,"
        + " reason TEXT NOT NULL, before_state TEXT NOT NULL, after_state TEXT NOT NULL,"
        + " created_at " + dialect.longType() + " NOT NULL)");
  }

  public static List<SchemaV1.TriggerDefinition> triggers(SqlDialect dialect, TableNames tables) {
    return List.of(immutable(dialect, tables.prefix() + "exchange_audit_records_no_update",
            "UPDATE", tables.auditRecords()),
        immutable(dialect, tables.prefix() + "exchange_audit_records_no_delete",
            "DELETE", tables.auditRecords()));
  }

  private static SchemaV1.TriggerDefinition immutable(
      SqlDialect dialect, String name, String operation, String table) {
    String sql = dialect == SqlDialect.SQLITE
        ? "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " BEGIN SELECT RAISE(ABORT,'immutable audit'); END"
        : "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable audit'";
    return new SchemaV1.TriggerDefinition(name, sql);
  }
}
