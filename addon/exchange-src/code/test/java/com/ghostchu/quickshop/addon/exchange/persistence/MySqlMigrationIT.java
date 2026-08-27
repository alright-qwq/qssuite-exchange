package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.MarketSnapshot;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationIT {
  @Container
  private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
      .withCommand("--log-bin-trust-function-creators=1");

  @Test
  void migratesMysqlSchemaIdempotentlyAndRejectsNegativeBalance() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("qs_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.MYSQL, names);
    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(tableCount(connection, "qs_exchange_%")).isEqualTo(18);
      assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(4);
      assertThat(columnExists(connection, names.marketState(), "discovery_quantity")).isTrue();
      assertThat(columnExists(connection, names.marketState(), "circuit_breaker_level")).isTrue();
      assertThat(indexExists(connection, names.orders(), names.prefix() + "exchange_orders_book_idx"))
          .isTrue();
      assertThat(indexExists(connection, names.trades(), names.prefix() + "exchange_trades_time_idx"))
          .isTrue();
      assertImmutableLedgerTriggers(connection, names);
      assertImmutableLedgerBehavior(connection, names);
      assertThatThrownBy(() -> connection.createStatement().executeUpdate(
          "INSERT INTO " + names.accounts()
              + " (account_id,currency_id,available,frozen,version) VALUES "
              + "('a','USD','-1.00','0.00',0)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void resumesPartiallyAppliedMysqlDdlBeforeRecordingVersion() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("recover_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.MYSQL, names);

    try (Connection connection = connections.open();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + names.markets() + " (market_id VARCHAR(128))");
    }

    assertThatThrownBy(runner::migrate).isInstanceOf(SQLException.class);
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      assertThat(tableCount(connection, "recover_exchange_%")).isEqualTo(2);
      assertThat(rowCount(connection, names.schemaVersion())).isZero();
      statement.execute("DROP TABLE " + names.markets());
    }

    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(tableCount(connection, "recover_exchange_%")).isEqualTo(18);
      assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(4);
      assertThat(indexExists(connection, names.orders(), names.prefix() + "exchange_orders_book_idx"))
          .isTrue();
      assertThat(indexExists(connection, names.trades(), names.prefix() + "exchange_trades_time_idx"))
          .isTrue();
      assertImmutableLedgerTriggers(connection, names);
    }
  }

  @Test
  void resumesPartiallyAppliedV2DdlBeforeRecordingVersion() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("v2_partial_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.MYSQL, names);
    runner.migrate();

    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM " + names.schemaVersion() + " WHERE version=2");
      statement.execute("ALTER TABLE " + names.marketState()
          + " DROP COLUMN circuit_breaker_level");
    }

    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(columnExists(connection, names.marketState(), "discovery_quantity")).isTrue();
      assertThat(columnExists(connection, names.marketState(), "circuit_breaker_level")).isTrue();
      assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(4);
      assertThat(versionRowCount(connection, names.schemaVersion(), 2)).isEqualTo(1);
    }
  }

  @Test
  void streamsLargeV1TradeHistoryWithoutBufferingTheWholeResult() throws Exception {
    ConnectionProvider setupConnections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("streaming_");
    new MigrationRunner(setupConnections, SqlDialect.MYSQL, names).migrate();
    seedMarket(setupConnections, names);
    seedTrades(setupConnections, names, 512);

    AtomicReference<Connection> activeConnection = new AtomicReference<>();
    ConnectionProvider observedConnections = () -> {
      Connection connection = setupConnections.open();
      activeConnection.set(connection);
      return connection;
    };
    ExchangeRepository repository =
        new JdbcExchangeRepository(observedConnections, SqlDialect.MYSQL, names);
    AtomicBoolean observedStreamingResult = new AtomicBoolean();

    repository.inTransaction(tx -> {
      tx.visitTradeHistory("diamond-usd", sample -> {
        if (observedStreamingResult.compareAndSet(false, true)) {
          assertThatThrownBy(() -> activeConnection.get().createStatement()
              .executeQuery("SELECT 1"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("Streaming result set");
        }
      });
      return null;
    });

    assertThat(observedStreamingResult).isTrue();
  }

  @Test
  void lockingOpenOrderReadSeesCommitAfterRepeatableReadSnapshot() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("snapshot_");
    new MigrationRunner(connections, SqlDialect.MYSQL, names).migrate();
    seedMarket(connections, names);
    ExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.MYSQL, names);
    Instant createdAt = Instant.ofEpochMilli(1_000);
    Order committedOrder = new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("100.00"), null,
        1, 1, OrderStatus.OPEN, 1, 1, 1, createdAt, createdAt);
    com.ghostchu.quickshop.addon.exchange.core.model.Trade committedTrade =
        new com.ghostchu.quickshop.addon.exchange.core.model.Trade(
            UUID.randomUUID(), "diamond-usd", committedOrder.orderId(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, 1, createdAt);
    CountDownLatch writerLockedMarket = new CountDownLatch(1);
    CountDownLatch snapshotEstablished = new CountDownLatch(1);

    try (Connection connection = connections.open()) {
      assertThat(connection.getTransactionIsolation())
          .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> writer = executor.submit(() -> repository.inTransaction(tx -> {
        MarketState before = tx.marketState("diamond-usd");
        writerLockedMarket.countDown();
        await(snapshotEstablished);
        tx.insertOrder(committedOrder, BigDecimal.ZERO, 1);
        tx.insertTrade(committedTrade);
        tx.updateMarketState(new MarketState(
            "diamond-usd", com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus.OPEN,
            1, 1, new BigDecimal("100.00"), new BigDecimal("100.00"), null,
            1L, 0, before.version() + 1), before.version());
        return null;
      }));
      assertThat(writerLockedMarket.await(5, TimeUnit.SECONDS)).isTrue();
      Future<MarketSnapshot> reader = executor.submit(() -> repository.inTransaction(tx -> {
        assertThat(tx.requestResult(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
        snapshotEstablished.countDown();
        MarketState state = tx.marketState("diamond-usd");
        return tx.marketSnapshot(state, Instant.EPOCH);
      }));
      writer.get();

      MarketSnapshot observed = reader.get();
      assertThat(observed.openOrders()).hasSize(1);
      assertThat(observed.openOrders().getFirst().order().orderId())
          .isEqualTo(committedOrder.orderId());
      assertThat(observed.openOrders().getFirst().order().limitPrice())
          .isEqualByComparingTo(committedOrder.limitPrice());
      assertThat(observed.recentTrades()).hasSize(1);
      assertThat(observed.recentTrades().getFirst().matchSequence()).isEqualTo(1);
      assertThat(observed.maximumPrioritySequence()).isEqualTo(1);
      assertThat(observed.maximumMatchSequence()).isEqualTo(1);
      assertThat(observed.state().discoveryQuantity()).isEqualTo(1);
      assertThat(observed.state().circuitBreakerLevel()).isZero();
      assertThat(observed.state().version()).isEqualTo(1);
    }
  }

  private static int tableCount(Connection connection, String pattern) throws SQLException {
    int count = 0;
    try (ResultSet result = connection.getMetaData().getTables(null, null, pattern, null)) {
      while (result.next()) count++;
    }
    return count;
  }

  private static void seedMarket(ConnectionProvider connections, TableNames names)
      throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement market = connection.prepareStatement(
             "INSERT INTO " + names.markets()
                 + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
                 + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?)");
         PreparedStatement state = connection.prepareStatement(
             "INSERT INTO " + names.marketState()
                 + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                 + "last_price,halted_until,discovery_quantity,circuit_breaker_level,version)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      market.setString(1, "diamond-usd");
      market.setString(2, "USD");
      market.setString(3, "diamond");
      market.setString(4, "{}");
      market.setString(5, "{}");
      market.setString(6, "{}");
      market.setString(7, "{}");
      market.setLong(8, 1);
      market.setLong(9, 1);
      market.setLong(10, 0);
      market.executeUpdate();

      state.setString(1, "diamond-usd");
      state.setString(2, "OPEN");
      state.setLong(3, 0);
      state.setLong(4, 0);
      state.setBigDecimal(5, new BigDecimal("100.00"));
      state.setNull(6, java.sql.Types.DECIMAL);
      state.setNull(7, java.sql.Types.BIGINT);
      state.setLong(8, 0);
      state.setInt(9, 0);
      state.setLong(10, 0);
      state.executeUpdate();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for MySQL transaction gate");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static int rowCount(Connection connection, String table) throws SQLException {
    try (ResultSet result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  private static long versionRowCount(Connection connection, String table, int version)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT COUNT(*) FROM " + table + " WHERE version=?")) {
      query.setInt(1, version);
      try (ResultSet result = query.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    }
  }

  private static void seedTrades(
      ConnectionProvider connections, TableNames names, int count) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement insert = connection.prepareStatement(
             "INSERT INTO " + names.trades()
                 + " (trade_id,market_id,maker_order_id,taker_order_id,buyer_account_id,"
                 + "seller_account_id,price,quantity,maker_fee,taker_fee,match_sequence,executed_at)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      for (int sequence = 1; sequence <= count; sequence++) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, "diamond-usd");
        insert.setString(3, UUID.randomUUID().toString());
        insert.setString(4, UUID.randomUUID().toString());
        insert.setString(5, UUID.randomUUID().toString());
        insert.setString(6, UUID.randomUUID().toString());
        insert.setBigDecimal(7, new BigDecimal("100.00"));
        insert.setLong(8, 1);
        insert.setBigDecimal(9, BigDecimal.ZERO);
        insert.setBigDecimal(10, BigDecimal.ZERO);
        insert.setLong(11, sequence);
        insert.setLong(12, sequence);
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static boolean indexExists(Connection connection, String table, String index)
      throws SQLException {
    try (ResultSet result = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
      while (result.next()) {
        if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
      return result.next();
    }
  }

  private static void assertImmutableLedgerTriggers(Connection connection, TableNames names)
      throws SQLException {
    assertThat(triggerExists(connection, names.prefix() + "exchange_ledger_journals_no_update"))
        .isTrue();
    assertThat(triggerExists(connection, names.prefix() + "exchange_ledger_journals_no_delete"))
        .isTrue();
    assertThat(triggerExists(connection, names.prefix() + "exchange_ledger_entries_no_update"))
        .isTrue();
    assertThat(triggerExists(connection, names.prefix() + "exchange_ledger_entries_no_delete"))
        .isTrue();
  }

  private static boolean triggerExists(Connection connection, String trigger) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM INFORMATION_SCHEMA.TRIGGERS"
            + " WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?")) {
      query.setString(1, trigger);
      try (ResultSet result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void assertImmutableLedgerBehavior(Connection connection, TableNames names)
      throws SQLException {
    String journalId = UUID.randomUUID().toString();
    try (var journal = connection.prepareStatement(
        "INSERT INTO " + names.journals()
            + " (journal_id,journal_type,reference_id,created_at,reversal_of)"
            + " VALUES (?,?,?,?,NULL)")) {
      journal.setString(1, journalId);
      journal.setString(2, "ADJUSTMENT");
      journal.setString(3, UUID.randomUUID().toString());
      journal.setLong(4, 0);
      journal.executeUpdate();
    }
    try (var entry = connection.prepareStatement(
        "INSERT INTO " + names.entries()
            + " (entry_id,journal_id,account_code,asset_id,amount,created_at)"
            + " VALUES (?,?,?,?,?,?)")) {
      entry.setString(1, UUID.randomUUID().toString());
      entry.setString(2, journalId);
      entry.setString(3, "player:a");
      entry.setString(4, "USD");
      entry.setBigDecimal(5, java.math.BigDecimal.ONE);
      entry.setLong(6, 0);
      entry.executeUpdate();
    }

    assertImmutableFailure(connection, "UPDATE " + names.entries() + " SET amount=2");
    assertImmutableFailure(connection, "DELETE FROM " + names.entries());
    assertImmutableFailure(connection,
        "UPDATE " + names.journals() + " SET journal_type='MUTATED'");
    assertImmutableFailure(connection, "DELETE FROM " + names.journals());
    assertThat(rowCount(connection, names.journals())).isEqualTo(1);
    assertThat(rowCount(connection, names.entries())).isEqualTo(1);
  }

  private static void assertImmutableFailure(Connection connection, String sql) {
    assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("immutable ledger");
  }
}
