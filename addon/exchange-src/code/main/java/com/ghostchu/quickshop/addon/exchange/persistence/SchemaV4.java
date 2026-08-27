package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

/** Schema for ledger-only virtual securities. */
public final class SchemaV4 {
  private SchemaV4() {}

  public static List<String> statements(SqlDialect d, TableNames t) {
    String id = d.uuidType();
    String num = d.longType();
    String amount = d.decimalType();
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + t.securities()
            + " (market_id VARCHAR(128) PRIMARY KEY, symbol VARCHAR(16) NOT NULL UNIQUE,"
            + " name VARCHAR(128) NOT NULL, description TEXT NOT NULL, currency_id VARCHAR(64) NOT NULL,"
            + " base_price " + amount + " NOT NULL, total_supply " + num + " NOT NULL CHECK (total_supply > 0),"
            + " issued_supply " + num + " NOT NULL CHECK (issued_supply >= 0 AND issued_supply <= total_supply),"
            + " minimum_unit " + num + " NOT NULL CHECK (minimum_unit > 0),"
            + " status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN','PAUSED','HALTED','CLOSED')),"
            + " recovery_account " + id + ", created_at " + num + " NOT NULL, updated_at " + num
            + " NOT NULL, version " + num + " NOT NULL,"
            + " FOREIGN KEY (market_id) REFERENCES " + t.markets() + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.securityBalances()
            + " (market_id VARCHAR(128) NOT NULL, owner_id " + id + " NOT NULL, available " + num
            + " NOT NULL CHECK (available >= 0), frozen " + num + " NOT NULL CHECK (frozen >= 0),"
            + " version " + num + " NOT NULL, updated_at " + num + " NOT NULL,"
            + " PRIMARY KEY (market_id,owner_id), FOREIGN KEY (market_id) REFERENCES " + t.markets()
            + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.securityLedger()
            + " (event_id " + id + " PRIMARY KEY, idempotency_key VARCHAR(160) NOT NULL UNIQUE,"
            + " market_id VARCHAR(128) NOT NULL, owner_id " + id + ", event_type VARCHAR(32) NOT NULL,"
            + " signed_quantity " + num + " NOT NULL, available_delta " + num + " NOT NULL,"
            + " frozen_delta " + num + " NOT NULL, reference_type VARCHAR(32), reference_id VARCHAR(160),"
            + " actor_id " + id + ", reason TEXT, created_at " + num + " NOT NULL,"
            + " FOREIGN KEY (market_id) REFERENCES " + t.markets() + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.securityAudit()
            + " (audit_id " + id + " PRIMARY KEY, request_id VARCHAR(160) NOT NULL UNIQUE,"
            + " market_id VARCHAR(128) NOT NULL, action VARCHAR(32) NOT NULL, actor_id " + id
            + " NOT NULL, payload TEXT NOT NULL, outcome VARCHAR(16) NOT NULL, created_at " + num
            + " NOT NULL, FOREIGN KEY (market_id) REFERENCES " + t.markets() + "(market_id))");
  }

  public static List<SchemaV1.IndexDefinition> indexes(TableNames tables) {
    return List.of(new SchemaV1.IndexDefinition(
        tables.prefix() + "exchange_security_ledger_account_idx",
        tables.securityLedger(), "market_id,owner_id"));
  }

  public static List<SchemaV1.TriggerDefinition> triggers(SqlDialect dialect, TableNames tables) {
    return List.of(
        immutable(dialect, tables.prefix() + "exchange_security_ledger_no_update",
            "UPDATE", tables.securityLedger()),
        immutable(dialect, tables.prefix() + "exchange_security_ledger_no_delete",
            "DELETE", tables.securityLedger()),
        immutable(dialect, tables.prefix() + "exchange_security_audit_no_update",
            "UPDATE", tables.securityAudit()),
        immutable(dialect, tables.prefix() + "exchange_security_audit_no_delete",
            "DELETE", tables.securityAudit()));
  }

  private static SchemaV1.TriggerDefinition immutable(
      SqlDialect dialect, String name, String operation, String table) {
    String sql = dialect == SqlDialect.SQLITE
        ? "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " BEGIN SELECT RAISE(ABORT,'immutable security'); END"
        : "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable security'";
    return new SchemaV1.TriggerDefinition(name, sql);
  }
}
