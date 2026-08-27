package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

public final class SchemaV1 {
  private SchemaV1() {}

  public static List<String> statements(SqlDialect d, TableNames t) {
    String id = d.uuidType();
    String amount = d.decimalType();
    String number = d.longType();
    String availableNonNegative = d == SqlDialect.SQLITE
        ? "CAST(available AS NUMERIC) >= 0" : "available >= 0";
    String frozenNonNegative = d == SqlDialect.SQLITE
        ? "CAST(frozen AS NUMERIC) >= 0" : "frozen >= 0";
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + t.schemaVersion()
            + " (version INTEGER PRIMARY KEY, applied_at " + number + " NOT NULL)",
        "CREATE TABLE IF NOT EXISTS " + t.markets()
            + " (market_id VARCHAR(128) PRIMARY KEY, currency_id VARCHAR(64) NOT NULL,"
            + " item_fingerprint TEXT NOT NULL, item_template TEXT NOT NULL,"
            + " structural_payload TEXT NOT NULL, fee_schedule_payload TEXT NOT NULL,"
            + " risk_payload TEXT NOT NULL,"
            + " structural_version " + number + " NOT NULL, risk_version " + number + " NOT NULL,"
            + " created_at " + number + " NOT NULL)",
        "CREATE TABLE IF NOT EXISTS " + t.marketState()
            + " (market_id VARCHAR(128) PRIMARY KEY, status VARCHAR(16) NOT NULL,"
            + " priority_sequence " + number + " NOT NULL, match_sequence " + number + " NOT NULL,"
            + " reference_price " + amount + " NOT NULL, last_price " + amount + ","
            + " halted_until " + number + ", version " + number + " NOT NULL,"
            + " FOREIGN KEY (market_id) REFERENCES " + t.markets() + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.accounts()
            + " (account_id " + id + " NOT NULL, currency_id VARCHAR(64) NOT NULL,"
            + " available " + amount + " NOT NULL CHECK (" + availableNonNegative + "),"
            + " frozen " + amount + " NOT NULL CHECK (" + frozenNonNegative + "),"
            + " version " + number + " NOT NULL, PRIMARY KEY (account_id,currency_id))",
        "CREATE TABLE IF NOT EXISTS " + t.inventory()
            + " (account_id " + id + " NOT NULL, market_id VARCHAR(128) NOT NULL,"
            + " available_quantity " + number + " NOT NULL CHECK (available_quantity >= 0),"
            + " frozen_quantity " + number + " NOT NULL CHECK (frozen_quantity >= 0),"
            + " version " + number + " NOT NULL, PRIMARY KEY (account_id,market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.orders()
            + " (order_id " + id + " PRIMARY KEY, request_id " + id + " NOT NULL,"
            + " market_id VARCHAR(128) NOT NULL, account_id " + id + " NOT NULL,"
            + " side VARCHAR(4) NOT NULL, order_type VARCHAR(8) NOT NULL,"
            + " time_in_force VARCHAR(3) NOT NULL, limit_price " + amount + ","
            + " slippage_boundary " + amount + ", original_quantity " + number + " NOT NULL,"
            + " remaining_quantity " + number + " NOT NULL,"
            + " status VARCHAR(24) NOT NULL, priority_sequence " + number + " NOT NULL,"
            + " config_version " + number + " NOT NULL, fee_version " + number + " NOT NULL,"
            + " reserved_currency " + amount + " NOT NULL, reserved_quantity " + number
            + " NOT NULL CHECK (reserved_quantity >= 0),"
            + " created_at " + number + " NOT NULL, updated_at " + number + " NOT NULL,"
            + " version " + number + " NOT NULL,"
            + " CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),"
            + " UNIQUE (market_id,priority_sequence), UNIQUE (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.trades()
            + " (trade_id " + id + " PRIMARY KEY, market_id VARCHAR(128) NOT NULL,"
            + " maker_order_id " + id + " NOT NULL, taker_order_id " + id + " NOT NULL,"
            + " buyer_account_id " + id + " NOT NULL, seller_account_id " + id + " NOT NULL,"
            + " price " + amount + " NOT NULL, quantity " + number
            + " NOT NULL CHECK (quantity >= 0),"
            + " maker_fee " + amount + " NOT NULL, taker_fee " + amount + " NOT NULL,"
            + " match_sequence " + number + " NOT NULL, executed_at " + number + " NOT NULL,"
            + " UNIQUE (market_id,match_sequence))",
        "CREATE TABLE IF NOT EXISTS " + t.journals()
            + " (journal_id " + id + " PRIMARY KEY, journal_type VARCHAR(32) NOT NULL,"
            + " reference_id " + id + " NOT NULL, created_at " + number + " NOT NULL,"
            + " reversal_of " + id + ", UNIQUE (journal_type,reference_id))",
        "CREATE TABLE IF NOT EXISTS " + t.entries()
            + " (entry_id " + id + " PRIMARY KEY, journal_id " + id + " NOT NULL,"
            + " account_code VARCHAR(160) NOT NULL, asset_id VARCHAR(160) NOT NULL,"
            + " amount " + amount + " NOT NULL, created_at " + number + " NOT NULL,"
            + " FOREIGN KEY (journal_id) REFERENCES " + t.journals() + "(journal_id))",
        "CREATE TABLE IF NOT EXISTS " + t.transfers()
            + " (transfer_id " + id + " PRIMARY KEY, request_id " + id + " NOT NULL,"
            + " account_id " + id + " NOT NULL, transfer_type VARCHAR(32) NOT NULL,"
            + " asset_id VARCHAR(160) NOT NULL, amount " + amount + " NOT NULL,"
            + " status VARCHAR(24) NOT NULL, external_marker VARCHAR(128),"
            + " failure_reason TEXT, created_at " + number + " NOT NULL,"
            + " updated_at " + number + " NOT NULL, version " + number + " NOT NULL,"
            + " UNIQUE (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.requestResults()
            + " (account_id " + id + " NOT NULL, request_id " + id + " NOT NULL,"
            + " operation VARCHAR(32) NOT NULL, result_payload TEXT NOT NULL,"
            + " created_at " + number + " NOT NULL, PRIMARY KEY (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.candles1m()
            + " (market_id VARCHAR(128) NOT NULL, bucket_start " + number + " NOT NULL,"
            + " open_price " + amount + " NOT NULL, high_price " + amount + " NOT NULL,"
            + " low_price " + amount + " NOT NULL, close_price " + amount + " NOT NULL,"
            + " volume " + number + " NOT NULL CHECK (volume >= 0), notional " + amount + " NOT NULL,"
            + " PRIMARY KEY (market_id,bucket_start))",
        "CREATE TABLE IF NOT EXISTS " + t.auditAlerts()
            + " (alert_id " + id + " PRIMARY KEY, market_id VARCHAR(128),"
            + " account_id " + id + ", alert_type VARCHAR(48) NOT NULL,"
            + " severity VARCHAR(16) NOT NULL, payload TEXT NOT NULL,"
            + " created_at " + number + " NOT NULL, acknowledged_at " + number + ")");
  }

  public static List<IndexDefinition> indexes(TableNames t) {
    return List.of(
        new IndexDefinition(t.prefix() + "exchange_orders_book_idx", t.orders(),
            "market_id,status,side,limit_price,priority_sequence"),
        new IndexDefinition(t.prefix() + "exchange_orders_account_idx", t.orders(),
            "account_id,status,updated_at"),
        new IndexDefinition(t.prefix() + "exchange_orders_created_idx", t.orders(),
            "created_at"),
        new IndexDefinition(t.prefix() + "exchange_trades_time_idx", t.trades(),
            "market_id,executed_at"),
        new IndexDefinition(t.prefix() + "exchange_trades_executed_idx", t.trades(),
            "executed_at"),
        new IndexDefinition(t.prefix() + "exchange_trades_buyer_idx", t.trades(),
            "buyer_account_id,executed_at"),
        new IndexDefinition(t.prefix() + "exchange_trades_seller_idx", t.trades(),
            "seller_account_id,executed_at"),
        new IndexDefinition(t.prefix() + "exchange_audit_alerts_time_idx", t.auditAlerts(),
            "created_at,acknowledged_at"));
  }

  public static List<TriggerDefinition> triggers(SqlDialect dialect, TableNames tables) {
    return List.of(
        immutableTrigger(dialect, tables.prefix() + "exchange_ledger_journals_no_update",
            "UPDATE", tables.journals()),
        immutableTrigger(dialect, tables.prefix() + "exchange_ledger_journals_no_delete",
            "DELETE", tables.journals()),
        immutableTrigger(dialect, tables.prefix() + "exchange_ledger_entries_no_update",
            "UPDATE", tables.entries()),
        immutableTrigger(dialect, tables.prefix() + "exchange_ledger_entries_no_delete",
            "DELETE", tables.entries()));
  }

  private static TriggerDefinition immutableTrigger(
      SqlDialect dialect, String name, String operation, String table) {
    String sql = dialect == SqlDialect.SQLITE
        ? "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " BEGIN SELECT RAISE(ABORT,'immutable ledger'); END"
        : "CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
            + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='immutable ledger'";
    return new TriggerDefinition(name, sql);
  }

  public record IndexDefinition(String name, String table, String columns) {}
  public record TriggerDefinition(String name, String sql) {}
}
