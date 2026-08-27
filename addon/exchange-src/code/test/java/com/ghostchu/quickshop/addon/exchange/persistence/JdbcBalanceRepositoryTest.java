package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.repository.CurrencyBalance;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.InsufficientAssetsException;
import com.ghostchu.quickshop.addon.exchange.repository.ItemBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcBalanceRepositoryTest {
  @TempDir
  Path temp;

  private ConnectionProvider connections;
  private TableNames tables;
  private ExchangeRepository repository;

  @BeforeEach
  void createRepository() throws Exception {
    connections = SqliteTestDatabase.at(temp.resolve("balance.db"));
    tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
  }

  @Test
  void appliesEveryCurrencyTransformationAndIncrementsVersion() throws Exception {
    UUID account = UUID.randomUUID();

    repository.inTransaction(tx -> {
      assertCurrency(tx.currency(account, "USD"), "0", "0", 0);
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("100.00"));
      assertCurrency(tx.currency(account, "USD"), "100.00", "0", 1);
      tx.freezeCurrency(account, "USD", new BigDecimal("60.00"));
      assertCurrency(tx.currency(account, "USD"), "40.00", "60.00", 2);
      tx.releaseCurrency(account, "USD", new BigDecimal("10.00"));
      assertCurrency(tx.currency(account, "USD"), "50.00", "50.00", 3);
      tx.consumeFrozenCurrency(account, "USD", new BigDecimal("15.00"));
      assertCurrency(tx.currency(account, "USD"), "50.00", "35.00", 4);
      return null;
    });

    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")),
        "50.00", "35.00", 4);
  }

  @Test
  void appliesEveryItemTransformationAndIncrementsVersion() throws Exception {
    UUID account = UUID.randomUUID();

    repository.inTransaction(tx -> {
      assertItems(tx.inventory(account, "DIAMOND"), 0, 0, 0);
      tx.creditAvailableItems(account, "DIAMOND", 10);
      assertItems(tx.inventory(account, "DIAMOND"), 10, 0, 1);
      tx.freezeItems(account, "DIAMOND", 7);
      assertItems(tx.inventory(account, "DIAMOND"), 3, 7, 2);
      tx.releaseItems(account, "DIAMOND", 2);
      assertItems(tx.inventory(account, "DIAMOND"), 5, 5, 3);
      tx.consumeFrozenItems(account, "DIAMOND", 3);
      assertItems(tx.inventory(account, "DIAMOND"), 5, 2, 4);
      return null;
    });

    assertItems(repository.inTransaction(tx -> tx.inventory(account, "DIAMOND")), 5, 2, 4);
  }

  @Test
  void accountAssetsIncludesSecurityBalancesWithSymbolAndName() throws Exception {
    UUID account = UUID.randomUUID();
    try (Connection connection = connections.open();
         PreparedStatement market = connection.prepareStatement(
             "INSERT INTO " + tables.markets()
                 + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
                 + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      market.setString(1, "concept_alpha");
      market.setString(2, "default");
      market.setString(3, "");
      market.setString(4, "");
      market.setString(5, "{}");
      market.setString(6, "{}");
      market.setString(7, "{}");
      market.setLong(8, 1);
      market.setLong(9, 1);
      market.setLong(10, 0);
      market.executeUpdate();
    }
    Instant now = Instant.ofEpochMilli(1000);
    repository.inTransaction(tx -> {
      tx.insertSecurityDefinition(new SecurityDefinitionState("concept_alpha", "ALPHA", "Alpha",
          "Concept stock", "default", new BigDecimal("10.00"), 1000, 0, 1, "OPEN", null,
          now, now, 0));
      tx.creditAvailableSecurity(account, "concept_alpha", 25);
      tx.freezeSecurity(account, "concept_alpha", 5);
      return null;
    });

    List<AccountAssetBalance> assets = repository.accountAssets(account);

    assertThat(assets).contains(new AccountAssetBalance(AccountAssetBalance.Kind.SECURITY,
        "concept_alpha", new BigDecimal("20"), new BigDecimal("5"),
        "Alpha (ALPHA)", "ALPHA"));
  }

  @Test
  void rejectsEveryNonPositiveBalanceMutation() {
    UUID account = UUID.randomUUID();

    assertInvalidCurrency(account, BigDecimal.ZERO,
        (tx, amount) -> tx.creditAvailableCurrency(account, "USD", amount));
    assertInvalidCurrency(account, new BigDecimal("-0.01"),
        (tx, amount) -> tx.creditAvailableCurrency(account, "USD", amount));
    assertInvalidCurrency(account, BigDecimal.ZERO,
        (tx, amount) -> tx.freezeCurrency(account, "USD", amount));
    assertInvalidCurrency(account, new BigDecimal("-0.01"),
        (tx, amount) -> tx.freezeCurrency(account, "USD", amount));
    assertInvalidCurrency(account, BigDecimal.ZERO,
        (tx, amount) -> tx.releaseCurrency(account, "USD", amount));
    assertInvalidCurrency(account, new BigDecimal("-0.01"),
        (tx, amount) -> tx.releaseCurrency(account, "USD", amount));
    assertInvalidCurrency(account, BigDecimal.ZERO,
        (tx, amount) -> tx.consumeFrozenCurrency(account, "USD", amount));
    assertInvalidCurrency(account, new BigDecimal("-0.01"),
        (tx, amount) -> tx.consumeFrozenCurrency(account, "USD", amount));

    assertInvalidItems(account, 0,
        (tx, quantity) -> tx.creditAvailableItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, -1,
        (tx, quantity) -> tx.creditAvailableItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, 0,
        (tx, quantity) -> tx.freezeItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, -1,
        (tx, quantity) -> tx.freezeItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, 0,
        (tx, quantity) -> tx.releaseItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, -1,
        (tx, quantity) -> tx.releaseItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, 0,
        (tx, quantity) -> tx.consumeFrozenItems(account, "DIAMOND", quantity));
    assertInvalidItems(account, -1,
        (tx, quantity) -> tx.consumeFrozenItems(account, "DIAMOND", quantity));
  }

  @Test
  void insufficientCurrencySourcesRollBackAndPreserveState() throws Exception {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("10"));
      tx.freezeCurrency(account, "USD", new BigDecimal("6"));
      return null;
    });

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("2"));
      tx.freezeCurrency(account, "USD", new BigDecimal("7"));
      return null;
    })).isInstanceOf(InsufficientAssetsException.class)
        .hasMessage("insufficient currency");
    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")), "4", "6", 2);

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.releaseCurrency(account, "USD", new BigDecimal("7"));
      return null;
    })).isInstanceOf(InsufficientAssetsException.class);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.consumeFrozenCurrency(account, "USD", new BigDecimal("7"));
      return null;
    })).isInstanceOf(InsufficientAssetsException.class);
    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")), "4", "6", 2);
  }

  @Test
  void insufficientItemSourcesRollBackAndPreserveState() throws Exception {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableItems(account, "DIAMOND", 10);
      tx.freezeItems(account, "DIAMOND", 6);
      return null;
    });

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableItems(account, "DIAMOND", 2);
      tx.freezeItems(account, "DIAMOND", 7);
      return null;
    })).isInstanceOf(InsufficientAssetsException.class)
        .hasMessage("insufficient items");
    assertItems(repository.inTransaction(tx -> tx.inventory(account, "DIAMOND")), 4, 6, 2);

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.releaseItems(account, "DIAMOND", 7);
      return null;
    })).isInstanceOf(InsufficientAssetsException.class);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.consumeFrozenItems(account, "DIAMOND", 7);
      return null;
    })).isInstanceOf(InsufficientAssetsException.class);
    assertItems(repository.inTransaction(tx -> tx.inventory(account, "DIAMOND")), 4, 6, 2);
  }

  @Test
  void storesOneRequestResultPerAccountAndRequestAndRollsBackDuplicateTransaction()
      throws Exception {
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    StoredRequestResult first =
        new StoredRequestResult(account, request, "PLACE", "{\"order\":\"one\"}");
    StoredRequestResult duplicate =
        new StoredRequestResult(account, request, "PLACE", "{\"order\":\"two\"}");

    repository.inTransaction(tx -> {
      tx.putRequestResult(first);
      return null;
    });

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", BigDecimal.ONE);
      tx.putRequestResult(duplicate);
      return null;
    })).isInstanceOf(SQLException.class);

    Optional<StoredRequestResult> stored =
        repository.inTransaction(tx -> tx.requestResult(account, request));
    assertThat(stored).contains(first);
    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")), "0", "0", 0);
  }

  @Test
  void rollsBackBalanceMutationWhenWorkThrowsRuntimeException() throws Exception {
    UUID account = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("stop");

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", BigDecimal.TEN);
      throw failure;
    })).isSameAs(failure);

    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")), "0", "0", 0);
  }

  @Test
  void preservesOriginalFailureWhenMysqlRollbackAlsoFails() {
    SQLException rollbackFailure = new SQLException("rollback failed");
    Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "setAutoCommit", "close" -> null;
          case "rollback" -> throw rollbackFailure;
          default -> throw new AssertionError("unexpected connection call: " + method.getName());
        });
    ExchangeRepository mysqlRepository =
        new JdbcExchangeRepository(() -> connection, SqlDialect.MYSQL, tables);
    IllegalStateException workFailure = new IllegalStateException("work failed");

    assertThatThrownBy(() -> mysqlRepository.inTransaction(tx -> {
      throw workFailure;
    })).isSameAs(workFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed())
            .containsExactly(rollbackFailure));
  }

  @Test
  void rejectsStaleCurrencyAndItemVersionsAndRollsBack() throws Exception {
    AtomicReference<Connection> activeConnection = new AtomicReference<>();
    ConnectionProvider trackingConnections = () -> {
      Connection connection = connections.open();
      activeConnection.set(connection);
      return connection;
    };
    ExchangeRepository trackingRepository =
        new JdbcExchangeRepository(trackingConnections, SqlDialect.SQLITE, tables);
    UUID account = UUID.randomUUID();

    assertThatThrownBy(() -> trackingRepository.inTransaction(tx -> {
      CurrencyBalance stale = tx.currency(account, "USD");
      bumpCurrencyVersion(activeConnection.get(), account, "USD");
      invokeStaleCurrencyUpdate(tx, stale, BigDecimal.ONE, BigDecimal.ZERO);
      return null;
    })).isInstanceOf(ConcurrentModificationException.class)
        .hasMessage("currency version changed");
    assertCurrency(repository.inTransaction(tx -> tx.currency(account, "USD")), "0", "0", 0);

    assertThatThrownBy(() -> trackingRepository.inTransaction(tx -> {
      ItemBalance stale = tx.inventory(account, "DIAMOND");
      bumpItemVersion(activeConnection.get(), account, "DIAMOND");
      invokeStaleItemUpdate(tx, stale, 1, 0);
      return null;
    })).isInstanceOf(ConcurrentModificationException.class)
        .hasMessage("item version changed");
    assertItems(repository.inTransaction(tx -> tx.inventory(account, "DIAMOND")), 0, 0, 0);
  }

  @Test
  void persistsOrdersAndTradesAndRejectsStaleOrderVersion() throws Exception {
    Instant created = Instant.ofEpochMilli(1_000);
    UUID account = UUID.randomUUID();
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", account,
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("10.00"), null,
        10, 10, OrderStatus.OPEN, 1, 1, 1, created, created);
    Order partiallyFilled = order.withRemaining(8, Instant.ofEpochMilli(2_000));
    Trade trade = new Trade(UUID.randomUUID(), "DIAMOND", order.orderId(), UUID.randomUUID(),
        account, UUID.randomUUID(), new BigDecimal("10.00"), 2,
        new BigDecimal("0.10"), new BigDecimal("0.20"), 1, Instant.ofEpochMilli(2_000));

    repository.inTransaction(tx -> {
      tx.insertOrder(order, new BigDecimal("100.00"), 0);
      tx.updateOrder(partiallyFilled, new BigDecimal("80.00"), 0, 0);
      tx.insertTrade(trade);
      return null;
    });

    assertThat(orderVersion(order.orderId())).isEqualTo(1);
    assertThat(orderRemaining(order.orderId())).isEqualTo(8);
    assertThat(tradeCount(trade.tradeId())).isEqualTo(1);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.updateOrder(partiallyFilled, new BigDecimal("80.00"), 0, 0);
      return null;
    })).isInstanceOf(ConcurrentModificationException.class)
        .hasMessage("order version changed");
    assertThat(orderVersion(order.orderId())).isEqualTo(1);
  }

  @Test
  void marketTradesJoinOrdersForTakerDirectionAndSummarize24hWindow() throws Exception {
    Instant now = Instant.ofEpochMilli(5_000_000);
    UUID buyer = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    Order buyOrder = new Order(UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", buyer,
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("10.00"), null,
        10, 10, OrderStatus.OPEN, 1, 1, 1, now.minusSeconds(60), now.minusSeconds(60));
    Order sellOrder = new Order(UUID.randomUUID(), UUID.randomUUID(), "DIAMOND", seller,
        OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("10.00"), null,
        10, 10, OrderStatus.OPEN, 2, 1, 1, now.minusSeconds(30), now.minusSeconds(30));
    Trade buyTrade = new Trade(UUID.randomUUID(), "DIAMOND", buyOrder.orderId(), sellOrder.orderId(),
        buyer, seller, new BigDecimal("10.00"), 4,
        new BigDecimal("0.10"), new BigDecimal("0.20"), 1, now.minusSeconds(30));
    Trade sellTrade = new Trade(UUID.randomUUID(), "DIAMOND", sellOrder.orderId(), buyOrder.orderId(),
        buyer, seller, new BigDecimal("10.00"), 6,
        new BigDecimal("0.10"), new BigDecimal("0.20"), 2, now);

    repository.inTransaction(tx -> {
      tx.insertOrder(buyOrder, new BigDecimal("40.00"), 0);
      tx.insertOrder(sellOrder, new BigDecimal("0.00"), 4);
      tx.insertTrade(buyTrade);
      tx.insertTrade(sellTrade);
      return null;
    });

    List<ExchangeRepository.MarketTradeRow> rows =
        repository.marketTrades("DIAMOND", 10);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).takerSide()).isEqualTo(OrderSide.BUY);
    assertThat(rows.get(0).quantity()).isEqualTo(6);
    assertThat(rows.get(1).takerSide()).isEqualTo(OrderSide.SELL);
    assertThat(rows.get(1).quantity()).isEqualTo(4);

    List<ExchangeRepository.MarketTradeRow> firstPage =
        repository.marketTradesPage("DIAMOND", 1, 0);
    List<ExchangeRepository.MarketTradeRow> secondPage =
        repository.marketTradesPage("DIAMOND", 1, 1);
    assertThat(firstPage).singleElement().extracting(
        ExchangeRepository.MarketTradeRow::quantity).isEqualTo(6L);
    assertThat(secondPage).singleElement().extracting(
        ExchangeRepository.MarketTradeRow::quantity).isEqualTo(4L);

    ExchangeRepository.MarketTradeSummary summary =
        repository.marketTradeSummary("DIAMOND", now.minus(Duration.ofHours(24)));
    assertThat(summary.tradeCount()).isEqualTo(2);
    assertThat(summary.buyCount()).isEqualTo(1);
    assertThat(summary.sellCount()).isEqualTo(1);
    assertThat(summary.volume()).isEqualTo(10);

    ExchangeRepository.MarketTradeSummary windowed =
        repository.marketTradeSummary("DIAMOND", now.minusSeconds(20));
    assertThat(windowed.tradeCount()).isEqualTo(1);
    assertThat(windowed.buyCount()).isEqualTo(1);
    assertThat(windowed.volume()).isEqualTo(6);
  }

  private void assertInvalidCurrency(UUID account, BigDecimal amount, CurrencyMutation mutation) {
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      mutation.apply(tx, amount);
      return null;
    })).isInstanceOf(IllegalArgumentException.class);
  }

  private void assertInvalidItems(UUID account, long quantity, ItemMutation mutation) {
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      mutation.apply(tx, quantity);
      return null;
    })).isInstanceOf(IllegalArgumentException.class);
  }

  private long orderVersion(UUID orderId) throws SQLException {
    return queryLong("SELECT version FROM " + tables.orders() + " WHERE order_id=?", orderId);
  }

  private long orderRemaining(UUID orderId) throws SQLException {
    return queryLong(
        "SELECT remaining_quantity FROM " + tables.orders() + " WHERE order_id=?", orderId);
  }

  private long tradeCount(UUID tradeId) throws SQLException {
    return queryLong("SELECT COUNT(*) FROM " + tables.trades() + " WHERE trade_id=?", tradeId);
  }

  private long queryLong(String sql, UUID id) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, id.toString());
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private void bumpCurrencyVersion(
      Connection connection, UUID accountId, String currencyId) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE " + tables.accounts()
            + " SET version=version+1 WHERE account_id=? AND currency_id=?")) {
      update.setString(1, accountId.toString());
      update.setString(2, currencyId);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
  }

  private void bumpItemVersion(
      Connection connection, UUID accountId, String marketId) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE " + tables.inventory()
            + " SET version=version+1 WHERE account_id=? AND market_id=?")) {
      update.setString(1, accountId.toString());
      update.setString(2, marketId);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
  }

  private static void invokeStaleCurrencyUpdate(
      ExchangeTransaction transaction, CurrencyBalance stale,
      BigDecimal available, BigDecimal frozen) throws SQLException {
    invokeGuardedUpdate(transaction, "updateCurrency",
        new Class<?>[] {CurrencyBalance.class, BigDecimal.class, BigDecimal.class},
        stale, available, frozen);
  }

  private static void invokeStaleItemUpdate(
      ExchangeTransaction transaction, ItemBalance stale, long available, long frozen)
      throws SQLException {
    invokeGuardedUpdate(transaction, "updateItems",
        new Class<?>[] {ItemBalance.class, long.class, long.class}, stale, available, frozen);
  }

  private static void invokeGuardedUpdate(
      ExchangeTransaction transaction, String methodName, Class<?>[] parameterTypes,
      Object... arguments) throws SQLException {
    try {
      Method update = transaction.getClass().getDeclaredMethod(methodName, parameterTypes);
      update.setAccessible(true);
      update.invoke(transaction, arguments);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof SQLException sqlFailure) {
        throw sqlFailure;
      }
      if (failure.getCause() instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void assertCurrency(
      CurrencyBalance balance, String available, String frozen, long version) {
    assertThat(balance.available()).isEqualByComparingTo(available);
    assertThat(balance.frozen()).isEqualByComparingTo(frozen);
    assertThat(balance.version()).isEqualTo(version);
  }

  private static void assertItems(
      ItemBalance balance, long available, long frozen, long version) {
    assertThat(balance.availableQuantity()).isEqualTo(available);
    assertThat(balance.frozenQuantity()).isEqualTo(frozen);
    assertThat(balance.version()).isEqualTo(version);
  }

  @FunctionalInterface
  private interface CurrencyMutation {
    void apply(com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction transaction,
               BigDecimal amount) throws SQLException;
  }

  @FunctionalInterface
  private interface ItemMutation {
    void apply(com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction transaction,
               long quantity) throws SQLException;
  }
}
