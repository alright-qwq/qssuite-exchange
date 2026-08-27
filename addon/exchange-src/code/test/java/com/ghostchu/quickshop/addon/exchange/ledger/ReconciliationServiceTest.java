package com.ghostchu.quickshop.addon.exchange.ledger;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationServiceTest {
  @Test
  void reportsBalancedLedgerCustodyAndReservations() throws Exception {
    TestDatabase database = database();
    UUID account = UUID.randomUUID();
    database.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("100.00"));
      tx.creditAvailableItems(account, "diamond-usd", 2);
      tx.appendJournal(journal("deposit-currency", List.of(
          entry("custody:currency:USD", "USD", "-100.00"),
          entry("liability:currency:" + account, "USD", "100.00"))));
      tx.appendJournal(journal("deposit-item", List.of(
          entry("custody:item:diamond-usd", "diamond-usd", "-2"),
          entry("liability:item:" + account, "diamond-usd", "2"))));
      return null;
    });

    ReconciliationReport report = new ReconciliationService(database.repository()).run();

    assertThat(report.ledgerDifferences()).isEmpty();
    assertThat(report.custodyDifferences()).isEmpty();
    assertThat(report.underReservedOrders()).isZero();
    assertThat(report.balanced()).isTrue();
  }

  @Test
  void findsLedgerCustodyAndBothBuyAndSellUnderReservations() throws Exception {
    TestDatabase database = database();
    UUID account = UUID.randomUUID();
    Instant now = Instant.now();
    database.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("100.00"));
      tx.creditAvailableItems(account, "diamond-usd", 2);
      tx.appendJournal(journal("deposit-currency", List.of(
          entry("custody:currency:USD", "USD", "-100.00"),
          entry("liability:currency:" + account, "USD", "100.00"))));
      tx.appendJournal(journal("deposit-item", List.of(
          entry("custody:item:diamond-usd", "diamond-usd", "-2"),
          entry("liability:item:" + account, "diamond-usd", "2"))));
      tx.insertOrder(order(account, OrderSide.BUY, now, 1), new BigDecimal("20.00"), 0);
      tx.insertOrder(order(account, OrderSide.SELL, now, 2), BigDecimal.ZERO, 1);
      tx.creditAvailableCurrency(account, "USD", BigDecimal.ONE);
      return null;
    });
    insertUnbalancedEntry(database, "USD", "1.00");

    ReconciliationReport report = new ReconciliationService(database.repository()).run();

    assertThat(report.ledgerDifferences().get("USD")).isEqualByComparingTo("1.00");
    assertThat(report.custodyDifferences().get("USD")).isEqualByComparingTo("1.00");
    assertThat(report.underReservedOrders()).isEqualTo(2);
    assertThat(report.balanced()).isFalse();
  }

  @Test
  void preservesExactDecimalDifferencesInSqlite() throws Exception {
    TestDatabase database = database();
    insertUnbalancedEntry(database, "USD", "9007199254740993.01");
    insertUnbalancedEntry(database, "USD", "-9007199254740993.00");

    ReconciliationReport report = new ReconciliationService(database.repository()).run();

    assertThat(report.ledgerDifferences().get("USD")).isEqualByComparingTo("0.01");
  }

  private static TestDatabase database() throws Exception {
    Path file = Files.createTempFile("quickshop-exchange-reconciliation-", ".sqlite");
    file.toFile().deleteOnExit();
    ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
    TableNames tables = new TableNames("reconcile_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    try (Connection connection = connections.open();
         PreparedStatement market = connection.prepareStatement(
             "INSERT INTO " + tables.markets() + " (market_id,currency_id,item_fingerprint,"
                 + "item_template,structural_payload,fee_schedule_payload,risk_payload,"
                 + "structural_version,risk_version,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)");
         PreparedStatement state = connection.prepareStatement(
             "INSERT INTO " + tables.marketState() + " (market_id,status,priority_sequence,"
                 + "match_sequence,reference_price,last_price,halted_until,discovery_quantity,"
                 + "circuit_breaker_level,version) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      market.setString(1, "diamond-usd");
      market.setString(2, "USD");
      market.setString(3, "diamond");
      market.setString(4, "{}");
      market.setString(5, "{}");
      market.setString(6,
          "{\"makerFeeRate\":\"0.00001\",\"takerFeeRate\":\"0.0001\",\"currencyScale\":2}");
      market.setString(7, "{}");
      market.setLong(8, 1);
      market.setLong(9, 1);
      market.setLong(10, 1);
      market.executeUpdate();
      state.setString(1, "diamond-usd");
      state.setString(2, "OPEN");
      state.setLong(3, 0);
      state.setLong(4, 0);
      state.setString(5, "10.00");
      state.setNull(6, java.sql.Types.DECIMAL);
      state.setNull(7, java.sql.Types.BIGINT);
      state.setLong(8, 0);
      state.setInt(9, 0);
      state.setLong(10, 0);
      state.executeUpdate();
    }
    return new TestDatabase(connections, tables,
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables));
  }

  private static Order order(UUID account, OrderSide side, Instant at, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", account, side,
        OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("10.00"), null, 2, 2,
        OrderStatus.OPEN, sequence, 1, 1, at, at);
  }

  private static LedgerJournal journal(String reference, List<LedgerEntry> entries) {
    return new LedgerJournal(UUID.randomUUID(), "TEST_CUSTODY", UUID.nameUUIDFromBytes(
        reference.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Instant.now(), null, entries);
  }

  private static LedgerEntry entry(String account, String asset, String amount) {
    return new LedgerEntry(UUID.randomUUID(), account, asset, new BigDecimal(amount), Instant.now());
  }

  private static void insertUnbalancedEntry(TestDatabase database, String asset, String amount)
      throws Exception {
    UUID journal = UUID.randomUUID();
    try (Connection connection = database.connections().open();
         PreparedStatement insertJournal = connection.prepareStatement(
             "INSERT INTO " + database.tables().journals()
                 + " (journal_id,journal_type,reference_id,created_at,reversal_of) VALUES (?,?,?,?,?)");
         PreparedStatement insertEntry = connection.prepareStatement(
             "INSERT INTO " + database.tables().entries()
                 + " (entry_id,journal_id,account_code,asset_id,amount,created_at) VALUES (?,?,?,?,?,?)")) {
      insertJournal.setString(1, journal.toString());
      insertJournal.setString(2, "TEST_TAMPER");
      insertJournal.setString(3, UUID.randomUUID().toString());
      insertJournal.setLong(4, Instant.now().toEpochMilli());
      insertJournal.setNull(5, java.sql.Types.VARCHAR);
      insertJournal.executeUpdate();
      insertEntry.setString(1, UUID.randomUUID().toString());
      insertEntry.setString(2, journal.toString());
      insertEntry.setString(3, "tamper");
      insertEntry.setString(4, asset);
      insertEntry.setString(5, amount);
      insertEntry.setLong(6, Instant.now().toEpochMilli());
      insertEntry.executeUpdate();
    }
  }

  private record TestDatabase(ConnectionProvider connections, TableNames tables,
                              JdbcExchangeRepository repository) {}
}
