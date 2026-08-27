package com.ghostchu.quickshop.addon.exchange.security;

import com.ghostchu.quickshop.addon.exchange.config.AssetType;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.SecurityDefinition;
import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSecurityRecoveryTest {
  @TempDir
  Path temp;

  @Test
  void insertMarketWritesVirtualSecurityDefinitionAndClosedStateForDisabledMarket()
      throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-market.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketDefinition definition = new MarketDefinition("concept_alpha", "Concept Alpha", false,
        null, structural(), risk(), false, AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition("ALPHA", "Alpha Holdings", "Pure ledger concept stock",
            "default", new BigDecimal("10.00"), 1000, 1));

    insertMarket(connections, tables, definition);

    try (Connection connection = connections.open();
         PreparedStatement market = connection.prepareStatement(
             "SELECT asset_type,item_fingerprint,item_template FROM " + tables.markets()
                 + " WHERE market_id=?");
         PreparedStatement security = connection.prepareStatement(
             "SELECT symbol,issued_supply,status,total_supply FROM " + tables.securities()
                 + " WHERE market_id=?");
         PreparedStatement state = connection.prepareStatement(
             "SELECT status FROM " + tables.marketState() + " WHERE market_id=?")) {
      market.setString(1, "concept_alpha");
      try (ResultSet result = market.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("asset_type")).isEqualTo("VIRTUAL_SECURITY");
        assertThat(result.getString("item_fingerprint")).isEmpty();
        assertThat(result.getString("item_template")).isEmpty();
      }
      security.setString(1, "concept_alpha");
      try (ResultSet result = security.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("symbol")).isEqualTo("ALPHA");
        assertThat(result.getLong("issued_supply")).isZero();
        assertThat(result.getString("status")).isEqualTo("CLOSED");
        assertThat(result.getLong("total_supply")).isEqualTo(1000);
      }
      state.setString(1, "concept_alpha");
      try (ResultSet result = state.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("status")).isEqualTo("CLOSED");
      }
    }
  }

  @Test
  void reconciliationSurfacesIssuedSecurityBalanceTampering() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-reconcile.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketDefinition definition = new MarketDefinition("concept_alpha", "Concept Alpha", true,
        null, structural(), risk(), false, AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition("ALPHA", "Alpha Holdings", "Pure ledger concept stock",
            "default", new BigDecimal("10.00"), 1000, 1));
    insertMarket(connections, tables, definition);
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    UUID holder = UUID.randomUUID();
    new SecurityService(repository).issue(UUID.randomUUID(), UUID.randomUUID(),
        "concept_alpha", holder, 30, "recovery fixture");
    repository.inTransaction(tx -> {
      tx.freezeSecurity(holder, "concept_alpha", 10);
      return null;
    });

    assertThat(new ReconciliationService(repository).run().balanced()).isTrue();

    try (Connection connection = connections.open();
         PreparedStatement tamper = connection.prepareStatement(
             "UPDATE " + tables.securityBalances()
                 + " SET available=available+5,version=version+1 WHERE market_id=?")) {
      tamper.setString(1, "concept_alpha");
      tamper.executeUpdate();
    }

    var report = new ReconciliationService(repository).run();
    assertThat(report.custodyDifferences().get("concept_alpha")).isEqualByComparingTo("5");
    assertThat(report.balanced()).isFalse();
  }

  @Test
  void issuedSupplyStaysWithinTotalSupplyAndBalancesRemainNonNegative() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-bound.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketDefinition definition = new MarketDefinition("concept_alpha", "Concept Alpha", true,
        null, structural(), risk(), false, AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition("ALPHA", "Alpha Holdings", "Pure ledger concept stock",
            "default", new BigDecimal("10.00"), 1000, 1));
    insertMarket(connections, tables, definition);
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    SecurityService service = new SecurityService(repository);
    UUID holder = UUID.randomUUID();
    service.issue(UUID.randomUUID(), UUID.randomUUID(), "concept_alpha", holder, 1000,
        "full issue");

    SecurityDefinitionState state =
        repository.inTransaction(tx -> tx.securityDefinition("concept_alpha"));
    SecurityBalance balance =
        repository.inTransaction(tx -> tx.securityBalance(holder, "concept_alpha"));
    assertThat(state.issuedSupply()).isEqualTo(1000);
    assertThat(balance.availableQuantity()).isEqualTo(1000);
    assertThat(balance.frozenQuantity()).isZero();
  }

  private static MarketDefinition.StructuralRules structural() {
    return new MarketDefinition.StructuralRules("default", new BigDecimal("10.00"),
        BigDecimal.ONE, new BigDecimal("100.00"), new BigDecimal("0.01"), 2, 2,
        1, 1000, 100);
  }

  private static MarketDefinition.RiskRules risk() {
    return new MarketDefinition.RiskRules(new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
        new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
        new BigDecimal("10000000.00"), 100, 5, 60);
  }

  private static void insertMarket(ConnectionProvider connections, TableNames tables,
                                   MarketDefinition definition) throws Exception {
    Method insert = Class.forName(
        "com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory")
        .getDeclaredMethod("insertMarket", Connection.class, TableNames.class,
            MarketDefinition.class);
    insert.setAccessible(true);
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
        insert.invoke(null, connection, tables, definition);
        connection.commit();
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
}
