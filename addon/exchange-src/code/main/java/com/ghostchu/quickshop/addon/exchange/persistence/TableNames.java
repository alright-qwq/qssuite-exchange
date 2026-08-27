package com.ghostchu.quickshop.addon.exchange.persistence;

public record TableNames(String prefix) {
  public TableNames {
    if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("invalid table prefix");
    }
  }
  public String schemaVersion() { return prefix + "exchange_schema_version"; }
  public String markets() { return prefix + "exchange_markets"; }
  public String marketState() { return prefix + "exchange_market_state"; }
  public String accounts() { return prefix + "exchange_accounts"; }
  public String inventory() { return prefix + "exchange_inventory"; }
  public String orders() { return prefix + "exchange_orders"; }
  public String trades() { return prefix + "exchange_trades"; }
  public String journals() { return prefix + "exchange_ledger_journals"; }
  public String entries() { return prefix + "exchange_ledger_entries"; }
  public String transfers() { return prefix + "exchange_transfers"; }
  public String requestResults() { return prefix + "exchange_request_results"; }
  public String candles1m() { return prefix + "exchange_candles_1m"; }
  public String auditAlerts() { return prefix + "exchange_audit_alerts"; }
  public String auditRecords() { return prefix + "exchange_audit_records"; }
  public String securities() { return prefix + "exchange_securities"; }
  public String securityBalances() { return prefix + "exchange_security_balances"; }
  public String securityLedger() { return prefix + "exchange_security_ledger"; }
  public String securityAudit() { return prefix + "exchange_security_audit"; }
}
