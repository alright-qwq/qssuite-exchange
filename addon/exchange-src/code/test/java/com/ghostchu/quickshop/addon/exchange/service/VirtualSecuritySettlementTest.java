package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualSecuritySettlementTest {
  @TempDir
  Path temp;

  @Test
  void sellFreezeTradeConsumeCancelReleaseUseSecurityLedgerOnly() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules base = TestFixtures.rules();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());

    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, SettlementObserver.NONE,
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(1));

    UUID seller = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(seller, rules.marketId(), 100);
      tx.creditAvailableCurrency(buyer, rules.currencyId(), new BigDecimal("1000.00"));
      return null;
    });

    OrderReceipt placed = service.place(new OrderRequest(UUID.randomUUID(), seller,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 10));
    SecurityBalance frozen =
        repository.inTransaction(tx -> tx.securityBalance(seller, rules.marketId()));
    assertThat(frozen.availableQuantity()).isEqualTo(90);
    assertThat(frozen.frozenQuantity()).isEqualTo(10);

    OrderReceipt filled = service.place(new OrderRequest(UUID.randomUUID(), buyer,
        "concept_alpha", OrderSide.BUY, "LIMIT", new BigDecimal("10.00"), null, 10));
    assertThat(filled.trades()).hasSize(1);

    SecurityBalance sellerAfter =
        repository.inTransaction(tx -> tx.securityBalance(seller, rules.marketId()));
    SecurityBalance buyerAfter =
        repository.inTransaction(tx -> tx.securityBalance(buyer, rules.marketId()));
    assertThat(sellerAfter.availableQuantity()).isEqualTo(90);
    assertThat(sellerAfter.frozenQuantity()).isZero();
    assertThat(buyerAfter.availableQuantity()).isEqualTo(10);
    assertThat(buyerAfter.frozenQuantity()).isZero();

    OrderReceipt placedAgain = service.place(new OrderRequest(UUID.randomUUID(), buyer,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 5));
    service.cancel(buyer, UUID.randomUUID(), placedAgain.orderId());
    SecurityBalance afterCancel =
        repository.inTransaction(tx -> tx.securityBalance(buyer, rules.marketId()));
    assertThat(afterCancel.availableQuantity()).isEqualTo(10);
    assertThat(afterCancel.frozenQuantity()).isZero();
    java.util.Optional<com.ghostchu.quickshop.addon.exchange.repository.ItemBalance> sellerItems =
        repository.inTransaction(tx -> tx.existingInventory(seller, rules.marketId()));
    java.util.Optional<com.ghostchu.quickshop.addon.exchange.repository.ItemBalance> buyerItems =
        repository.inTransaction(tx -> tx.existingInventory(buyer, rules.marketId()));
    assertThat(sellerItems).isEmpty();
    assertThat(buyerItems).isEmpty();
  }

  @Test
  void rejectsQuantityThatIsNotAMultipleOfMinimumUnit() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-unit.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());
    UUID seller = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(seller, rules.marketId(), 100);
      return null;
    });

    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, SettlementObserver.NONE,
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(5));

    assertThatThrownBy(() -> service.place(new OrderRequest(UUID.randomUUID(), seller,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 3)))
        .hasMessageContaining("multiple of minimum unit");
  }

  @Test
  void tradesWriteImmutableSecurityLedgerEntries() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-ledger.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, SettlementObserver.NONE,
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(1));
    UUID seller = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(seller, rules.marketId(), 100);
      tx.creditAvailableCurrency(buyer, rules.currencyId(), new BigDecimal("1000.00"));
      return null;
    });

    service.place(new OrderRequest(UUID.randomUUID(), seller,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 10));
    OrderReceipt filled = service.place(new OrderRequest(UUID.randomUUID(), buyer,
        "concept_alpha", OrderSide.BUY, "LIMIT", new BigDecimal("10.00"), null, 10));

    java.util.List<com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry> ledger =
        repository.inTransaction(tx -> tx.securityLedger(rules.marketId(), null));
    assertThat(ledger).hasSize(2);
    long buyerNet = ledger.stream()
        .filter(entry -> entry.ownerId().equals(buyer))
        .mapToLong(com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry::signedQuantity)
        .sum();
    long sellerNet = ledger.stream()
        .filter(entry -> entry.ownerId().equals(seller))
        .mapToLong(com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry::signedQuantity)
        .sum();
    assertThat(buyerNet).isEqualTo(10);
    assertThat(sellerNet).isEqualTo(-10);
    assertThat(filled.trades()).hasSize(1);
  }

  @Test
  void reconciliationCountsSecurityBalancesAsCustodyLiabilities() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-reconcile.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());
    UUID holder = UUID.randomUUID();
    new com.ghostchu.quickshop.addon.exchange.security.SecurityService(repository).issue(
        UUID.randomUUID(), UUID.randomUUID(), rules.marketId(), holder, 30,
        "reconciliation fixture");
    repository.inTransaction(tx -> {
      tx.freezeSecurity(holder, rules.marketId(), 10);
      return null;
    });

    com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport report =
        new com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationService(repository).run();

    assertThat(report.ledgerDifferences()).isEmpty();
    assertThat(report.custodyDifferences()).isEmpty();
    assertThat(report.underReservedOrders()).isZero();
    assertThat(report.balanced()).isTrue();

    // A tampered security balance must surface as a custody difference.
    try (Connection connection = connections.open();
         PreparedStatement tamper = connection.prepareStatement(
             "UPDATE " + tables.securityBalances()
                 + " SET available=available+5,version=version+1 WHERE market_id=?")) {
      tamper.setString(1, rules.marketId());
      tamper.executeUpdate();
    }
    com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport detected =
        new com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationService(repository).run();
    assertThat(detected.custodyDifferences().get(rules.marketId())).isEqualByComparingTo("5");
    assertThat(detected.balanced()).isFalse();
  }

  @Test
  void issueTradeThenCloseRecoversBuyerHoldingIntoRecoveryAccount() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("virtual-e2e.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());
    com.ghostchu.quickshop.addon.exchange.security.SecurityService security =
        new com.ghostchu.quickshop.addon.exchange.security.SecurityService(repository);
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, SettlementObserver.NONE,
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(1));
    UUID actor = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    UUID recovery = UUID.randomUUID();
    security.issue(actor, UUID.randomUUID(), rules.marketId(), seller, 100, "initial grant");
    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(buyer, rules.currencyId(), new BigDecimal("1000.00"));
      return null;
    });

    service.place(new OrderRequest(UUID.randomUUID(), seller,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 10));
    OrderReceipt filled = service.place(new OrderRequest(UUID.randomUUID(), buyer,
        "concept_alpha", OrderSide.BUY, "LIMIT", new BigDecimal("10.00"), null, 10));
    assertThat(filled.trades()).hasSize(1);

    security.close(actor, UUID.randomUUID(), rules.marketId(), recovery, "end of concept");
    SecurityBalance buyerAfter =
        repository.inTransaction(tx -> tx.securityBalance(buyer, rules.marketId()));
    SecurityBalance recoveryAfter =
        repository.inTransaction(tx -> tx.securityBalance(recovery, rules.marketId()));
    assertThat(buyerAfter.availableQuantity()).isZero();
    assertThat(buyerAfter.frozenQuantity()).isZero();
    assertThat(recoveryAfter.availableQuantity()).isEqualTo(100);
    SecurityBalance sellerAfter =
        repository.inTransaction(tx -> tx.securityBalance(seller, rules.marketId()));
    assertThat(sellerAfter.availableQuantity()).isZero();
    assertThat(sellerAfter.frozenQuantity()).isZero();
    com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState definition =
        repository.inTransaction(tx -> tx.securityDefinition(rules.marketId()));
    assertThat(definition.status()).isEqualTo("CLOSED");
  }

  @ParameterizedTest
  @EnumSource(SettlementStage.class)
  void rollsBackSecurityBalancesAndLedgersOnEverySettlementStage(SettlementStage failingStage)
      throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(
        temp.resolve("virtual-fail-" + failingStage.name() + ".db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketRules rules = new MarketRules("concept_alpha", "USD", new BigDecimal("10.00"),
        new BigDecimal("1.00"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    seedMarket(connections, tables, rules);
    seedSecurity(connections, tables, rules.marketId());
    UUID seller = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(seller, rules.marketId(), 100);
      tx.creditAvailableCurrency(buyer, rules.currencyId(), new BigDecimal("1000.00"));
      return null;
    });
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, SettlementObserver.NONE,
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(1));
    service.place(new OrderRequest(UUID.randomUUID(), seller,
        "concept_alpha", OrderSide.SELL, "LIMIT", new BigDecimal("10.00"), null, 10));

    SecuritySnapshot before = snapshot(connections, tables, seller, buyer, rules.marketId());

    java.util.concurrent.atomic.AtomicInteger recoveryCalls = new java.util.concurrent.atomic.AtomicInteger();
    PersistentOrderService failing = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        (marketId, failure) -> recoveryCalls.incrementAndGet(), new SettlementObserver() {
          @Override
          public void reached(SettlementStage stage) {
            if (stage == failingStage) {
              throw new InjectedFailure(stage.name());
            }
          }
        },
        new com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator(
            System::currentTimeMillis, new java.util.Random()), java.time.Instant::now,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, new SecurityAssetCustody(1));

    assertThatThrownBy(() -> failing.place(new OrderRequest(UUID.randomUUID(), buyer,
        "concept_alpha", OrderSide.BUY, "LIMIT", new BigDecimal("10.00"), null, 10)))
        .isInstanceOf(InjectedFailure.class)
        .hasMessage(failingStage.name());

    SecuritySnapshot after = snapshot(connections, tables, seller, buyer, rules.marketId());
    assertThat(after).isEqualTo(before);
    assertThat(recoveryCalls).hasValue(1);
  }

  private record SecuritySnapshot(long sellerAvailable, long sellerFrozen,
                                  long buyerAvailable, long buyerFrozen,
                                  long securityLedgerRows, long itemLedgerRows) {}

  private static SecuritySnapshot snapshot(ConnectionProvider connections, TableNames tables,
                                           UUID seller, UUID buyer, String marketId)
      throws Exception {
    try (Connection connection = connections.open()) {
      long sellerAvailable = 0;
      long sellerFrozen = 0;
      long buyerAvailable = 0;
      long buyerFrozen = 0;
      try (PreparedStatement balance = connection.prepareStatement(
          "SELECT available,frozen FROM " + tables.securityBalances()
              + " WHERE market_id=? AND owner_id=?")) {
        balance.setString(1, marketId);
        balance.setString(2, seller.toString());
        try (ResultSet result = balance.executeQuery()) {
          if (result.next()) {
            sellerAvailable = result.getLong("available");
            sellerFrozen = result.getLong("frozen");
          }
        }
        balance.setString(2, buyer.toString());
        try (ResultSet result = balance.executeQuery()) {
          if (result.next()) {
            buyerAvailable = result.getLong("available");
            buyerFrozen = result.getLong("frozen");
          }
        }
      }
      long securityLedgerRows;
      long itemLedgerRows;
      try (Statement statement = connection.createStatement()) {
        try (ResultSet result = statement.executeQuery(
            "SELECT COUNT(*) AS count FROM " + tables.securityLedger())) {
          result.next();
          securityLedgerRows = result.getLong("count");
        }
        try (ResultSet result = statement.executeQuery(
            "SELECT COUNT(*) AS count FROM " + tables.journals())) {
          result.next();
          itemLedgerRows = result.getLong("count");
        }
      }
      return new SecuritySnapshot(sellerAvailable, sellerFrozen, buyerAvailable, buyerFrozen,
          securityLedgerRows, itemLedgerRows);
    }
  }

  private static void seedMarket(ConnectionProvider connections, TableNames tables,
                                 MarketRules rules) throws Exception {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try (PreparedStatement market = connection.prepareStatement(
          "INSERT INTO " + tables.markets()
              + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
              + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)");
           PreparedStatement state = connection.prepareStatement(
               "INSERT INTO " + tables.marketState()
                   + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                   + "last_price,halted_until,discovery_quantity,circuit_breaker_level,version)"
                   + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
        market.setString(1, rules.marketId());
        market.setString(2, rules.currencyId());
        market.setString(3, "");
        market.setString(4, "");
        market.setString(5, "{}");
        market.setString(6, "{\"makerFeeRate\":\"" + rules.makerFeeRate().toPlainString()
            + "\",\"takerFeeRate\":\"" + rules.takerFeeRate().toPlainString()
            + "\",\"currencyScale\":" + rules.priceScale() + "}");
        market.setString(7, "{}");
        market.setLong(8, 1);
        market.setLong(9, 1);
        market.setLong(10, System.currentTimeMillis());
        market.executeUpdate();
        state.setString(1, rules.marketId());
        state.setString(2, "OPEN");
        state.setLong(3, 0);
        state.setLong(4, 0);
        state.setString(5, rules.basePrice().toPlainString());
        state.setNull(6, java.sql.Types.DECIMAL);
        state.setNull(7, java.sql.Types.BIGINT);
        state.setLong(8, 0);
        state.setInt(9, 0);
        state.setLong(10, 0);
        state.executeUpdate();
        connection.commit();
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static void seedSecurity(ConnectionProvider connections, TableNames tables,
                                   String marketId) throws Exception {
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.securities()
          + " (market_id,symbol,name,description,currency_id,base_price,total_supply,"
          + "issued_supply,minimum_unit,status,created_at,updated_at,version)"
          + " VALUES ('" + marketId + "','ALPHA','Alpha','Concept stock','USD','10.00',1000000,"
          + "0,1,'OPEN',0,0,0)");
    }
  }
}
