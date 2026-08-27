package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.repository.MarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.repository.MarketTradeSample;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookRecoveryServiceTest {
  @Test
  void rebuildsSamePriceOrdersInOriginalFifoOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID secondSeller = fixture.accountWithItems(1);
    fixture.service().place(limitSell(firstSeller, 1));
    fixture.service().place(limitSell(secondSeller, 1));

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", Instant.now());

    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::prioritySequence)
        .containsExactly(1L, 2L);
    assertThat(recovered.prioritySequence()).isEqualTo(2);
    assertThat(recovered.matchSequence()).isZero();
  }

  @Test
  void preservesPartialQuantityAndFifoPriority() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(2);
    UUID secondSeller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(limitSell(firstSeller, 2));
    fixture.service().place(limitSell(secondSeller, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", Instant.now());

    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::prioritySequence)
        .containsExactly(1L, 2L);
    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::remainingQuantity)
        .containsExactly(1L, 1L);
  }

  @Test
  void restoresReferenceWindowAndDiscoveryQuantityExactly() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));
    Instant recoveredAt = Instant.now();

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", recoveredAt);
    recovered.referencePrices().record(
        new BigDecimal("105.00"), 1, recoveredAt.plusMillis(1));

    assertThat(recovered.referencePrices().referenceAt(recoveredAt.plusMillis(1)))
        .isEqualByComparingTo("102.55");
    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(51);
  }

  @Test
  void replaysV1HistoryOnceAndPersistsExactMetadata() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));
    long beforeVersion = fixture.marketVersion();
    fixture.clearMarketRiskMetadata();

    AtomicInteger fullHistoryVisits = new AtomicInteger();
    OrderBookRecoveryService recovery = new OrderBookRecoveryService(
        countingHistory(fixture.repository(), fullHistoryVisits), fixture.rules(),
        RiskLimits.defaults());

    RecoveredMarket recovered = recovery.recover("diamond-usd", Instant.now());
    RecoveredMarket recoveredAgain = recovery.recover("diamond-usd", Instant.now());

    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(50);
    assertThat(recovered.circuitBreaker().level()).isZero();
    assertThat(recovered.marketVersion()).isEqualTo(beforeVersion + 1);
    assertThat(fixture.marketDiscoveryQuantity()).isEqualTo("50");
    assertThat(fixture.marketCircuitBreakerLevel()).isEqualTo("0");
    assertThat(recoveredAgain.referencePrices().discoveryQuantity()).isEqualTo(50);
    assertThat(fullHistoryVisits).hasValue(1);
  }

  @Test
  void allocatesPriorityAndMatchStrictlyAboveRecoveredCounters() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.setMarketSequences(10, 20);
    fixture.service().recoverFromDatabase();

    fixture.service().place(limitSell(seller, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(fixture.marketPrioritySequence()).isEqualTo(12);
    assertThat(fixture.marketMatchSequence()).isEqualTo(21);
  }

  @Test
  void corruptSequenceLeavesMarketRecovering() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    fixture.service().place(limitSell(seller, 1));
    fixture.setMarketPrioritySequence(0);

    assertThatThrownBy(() -> fixture.recovery().recover("diamond-usd", Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sequence");
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
  }

  @Test
  void expiresOldSamplesButRetainsDiscoveryQuantity() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));

    RecoveredMarket recovered = fixture.recovery().recover(
        "diamond-usd", Instant.now().plus(Duration.ofMinutes(6)));

    assertThat(recovered.referencePrices().samples()).isEmpty();
    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(50);
  }

  @Test
  void rejectsCorruptOrdersSamplesMetadataAndOverflowBoundaries() {
    Instant now = Instant.EPOCH.plusSeconds(10);
    MarketState validState = state(2, 2, 0L, 0);
    Order validOrder = order("diamond-usd", OrderStatus.OPEN, 1, UUID.randomUUID());
    MarketTradeSample first = new MarketTradeSample(
        new BigDecimal("100.00"), 1, 1, now.minusSeconds(2));
    MarketTradeSample second = new MarketTradeSample(
        new BigDecimal("100.00"), 1, 2, now.minusSeconds(1));

    List<MarketSnapshot> corruptSnapshots = List.of(
        snapshot(validState,
            List.of(persisted(order("other-market", OrderStatus.OPEN, 1, UUID.randomUUID()))),
            List.of(), 1, 0),
        snapshot(validState,
            List.of(persisted(order("diamond-usd", OrderStatus.FILLED, 1, UUID.randomUUID()))),
            List.of(), 1, 0),
        snapshot(validState, List.of(persisted(validOrder), persisted(validOrder)),
            List.of(), 1, 0),
        snapshot(validState, List.of(), List.of(second, first), 0, 2),
        snapshot(state(2, 2, null, 0), List.of(), List.of(first, second), 0, 2),
        snapshot(state(2, 2, 101L, 0), List.of(), List.of(first, second), 0, 2),
        snapshot(state(Long.MAX_VALUE, 2, 0L, 0), List.of(), List.of(), 0, 0),
        snapshot(state(2, Long.MAX_VALUE, 0L, 0), List.of(), List.of(), 0, 0));

    for (MarketSnapshot corrupt : corruptSnapshots) {
      SnapshotRepository repository = new SnapshotRepository(corrupt);
      OrderBookRecoveryService recovery = new OrderBookRecoveryService(
          repository, TestFixtures.rules(), RiskLimits.defaults());

      assertThatThrownBy(() -> recovery.recover("diamond-usd", now))
          .isInstanceOf(RuntimeException.class);
      assertThat(repository.state().status()).isEqualTo(MarketStatus.RECOVERING);
    }
  }

  private static OrderRequest limitSell(UUID accountId, long quantity) {
    return new OrderRequest(UUID.randomUUID(), accountId, "diamond-usd", OrderSide.SELL,
        "LIMIT", new BigDecimal("100.00"), null, quantity);
  }

  private static ExchangeRepository countingHistory(
      ExchangeRepository delegate, AtomicInteger visits) {
    return new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws java.sql.SQLException {
        return delegate.inTransaction(tx -> work.apply((ExchangeTransaction) Proxy.newProxyInstance(
            ExchangeTransaction.class.getClassLoader(),
            new Class<?>[] {ExchangeTransaction.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("visitTradeHistory")) {
                visits.incrementAndGet();
              }
              try {
                return method.invoke(tx, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            })));
      }

      @Override
      public Object coordinationKey() {
        return delegate.coordinationKey();
      }
    };
  }

  private static MarketState state(
      long prioritySequence, long matchSequence, Long discoveryQuantity, Integer breakerLevel) {
    return new MarketState("diamond-usd", MarketStatus.OPEN, prioritySequence, matchSequence,
        new BigDecimal("100.00"), null, null, discoveryQuantity, breakerLevel, 0);
  }

  private static Order order(
      String marketId, OrderStatus status, long prioritySequence, UUID orderId) {
    long remaining = status == OrderStatus.FILLED ? 0 : 1;
    return new Order(orderId, UUID.randomUUID(), marketId, UUID.randomUUID(), OrderSide.SELL,
        OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("100.00"), null,
        1, remaining, status, prioritySequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static PersistedOrder persisted(Order order) {
    return new PersistedOrder(order, BigDecimal.ZERO, order.remainingQuantity(), 0);
  }

  private static MarketSnapshot snapshot(
      MarketState state, List<PersistedOrder> orders, List<MarketTradeSample> trades,
      long maximumPriority, long maximumMatch) {
    return new MarketSnapshot(state, orders, trades, maximumPriority, maximumMatch);
  }

  private static final class SnapshotRepository implements ExchangeRepository {
    private final MarketSnapshot snapshot;
    private MarketState state;

    private SnapshotRepository(MarketSnapshot snapshot) {
      this.snapshot = snapshot;
      this.state = snapshot.state();
    }

    @Override
    public <T> T inTransaction(TransactionWork<T> work) throws java.sql.SQLException {
      ExchangeTransaction transaction = (ExchangeTransaction) Proxy.newProxyInstance(
          ExchangeTransaction.class.getClassLoader(), new Class<?>[] {ExchangeTransaction.class},
          (proxy, method, arguments) -> switch (method.getName()) {
            case "marketState" -> state;
            case "marketSnapshot" -> snapshot;
            case "updateMarketState" -> {
              state = (MarketState) arguments[0];
              yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
          });
      return work.apply(transaction);
    }

    private MarketState state() {
      return state;
    }
  }
}
