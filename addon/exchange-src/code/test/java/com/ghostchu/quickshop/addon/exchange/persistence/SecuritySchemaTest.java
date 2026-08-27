package com.ghostchu.quickshop.addon.exchange.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecuritySchemaTest {
  @Test
  void migrationAddsVirtualSecurityTablesAndIsIdempotent(@TempDir Path temp) throws Exception {
    ConnectionProvider provider = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames names = new TableNames("qs_");
    MigrationRunner runner = new MigrationRunner(provider, SqlDialect.SQLITE, names);

    runner.migrate();
    runner.migrate();

    try (Connection connection = provider.open()) {
      assertThat(tableExists(connection, names.securities())).isTrue();
      assertThat(tableExists(connection, names.securityBalances())).isTrue();
      assertThat(tableExists(connection, names.securityLedger())).isTrue();
      assertThat(tableExists(connection, names.securityAudit())).isTrue();
      assertThat(columnExists(connection, names.markets(), "asset_type")).isTrue();
      assertThat(triggerExists(connection, names.prefix() + "exchange_security_ledger_no_update"))
          .isTrue();
      assertThat(triggerExists(connection, names.prefix() + "exchange_security_ledger_no_delete"))
          .isTrue();
      assertThat(triggerExists(connection, names.prefix() + "exchange_security_audit_no_update"))
          .isTrue();
      assertThat(triggerExists(connection, names.prefix() + "exchange_security_audit_no_delete"))
          .isTrue();
      assertThat(indexExists(connection, names.securityLedger(),
          names.prefix() + "exchange_security_ledger_account_idx")).isTrue();
      assertThat(connection.createStatement().executeQuery(
          "SELECT COUNT(*) FROM " + names.schemaVersion() + " WHERE version=4").next()).isTrue();
    }
  }

  @Test
  void existingMarketRowsDefaultToPhysicalItemAndVirtualColumnsStayNullable(
      @TempDir Path temp) throws Exception {
    ConnectionProvider provider = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(provider, SqlDialect.SQLITE, names).migrate();

    try (Connection connection = provider.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + names.markets()
          + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
          + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
          + " VALUES ('legacy','default','DIAMOND','','{}','{}','{}',1,1,0)");
      try (ResultSet result = statement.executeQuery(
          "SELECT asset_type FROM " + names.markets() + " WHERE market_id='legacy'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("asset_type")).isEqualTo("PHYSICAL_ITEM");
      }
      assertThat(columnNullable(connection, names.securities(), "recovery_account")).isTrue();
      assertThat(columnNullable(connection, names.securityLedger(), "owner_id")).isTrue();
    }
  }

  @Test
  void securityLedgerAndAuditAreImmutable(@TempDir Path temp) throws Exception {
    ConnectionProvider provider = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(provider, SqlDialect.SQLITE, names).migrate();

    try (Connection connection = provider.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + names.markets()
          + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
          + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
          + " VALUES ('alpha','default','','','{}','{}','{}',1,1,0)");
      statement.executeUpdate("INSERT INTO " + names.securities()
          + " (market_id,symbol,name,description,currency_id,base_price,total_supply,"
          + "issued_supply,minimum_unit,status,created_at,updated_at,version)"
          + " VALUES ('alpha','ALPHA','Alpha','Concept stock','default','10.00',1000,0,1,"
          + "'OPEN',0,0,0)");
      statement.executeUpdate("INSERT INTO " + names.securityAudit()
          + " (audit_id,request_id,market_id,action,actor_id,payload,outcome,created_at)"
          + " VALUES ('a','req','alpha','CREATE','actor','{}','SUCCESS',0)");
      statement.executeUpdate("INSERT INTO " + names.securityLedger()
          + " (event_id,idempotency_key,market_id,owner_id,event_type,signed_quantity,"
          + "available_delta,frozen_delta,created_at)"
          + " VALUES ('e','idem','alpha','owner','ISSUE',10,10,0,0)");

      assertThatThrownBy(() -> statement.executeUpdate(
          "UPDATE " + names.securityLedger() + " SET signed_quantity=99 WHERE event_id='e'"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate(
          "DELETE FROM " + names.securityLedger() + " WHERE event_id='e'"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate(
          "UPDATE " + names.securityAudit() + " SET outcome='FAILED' WHERE audit_id='a'"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate(
          "DELETE FROM " + names.securityAudit() + " WHERE audit_id='a'"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }

  private static boolean tableExists(Connection connection, String table) throws Exception {
    try (var rs = connection.getMetaData().getTables(null, null, table, null)) {
      return rs.next();
    }
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws Exception {
    try (var rs = connection.getMetaData().getColumns(null, null, table, column)) {
      return rs.next();
    }
  }

  private static boolean columnNullable(Connection connection, String table, String column)
      throws Exception {
    try (var rs = connection.getMetaData().getColumns(null, null, table, column)) {
      return rs.next() && rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
    }
  }

  private static boolean triggerExists(Connection connection, String trigger) throws Exception {
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?")) {
      query.setString(1, trigger);
      try (ResultSet result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static boolean indexExists(Connection connection, String table, String index)
      throws Exception {
    try (ResultSet result = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
      while (result.next()) {
        if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }
}
