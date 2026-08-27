package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.PriceSample;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.TradePermission;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.MarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.repository.MarketTradeSample;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OrderBookRecoveryService {
  static final long DISCOVERY_QUANTITY = 100;
  static final Duration REFERENCE_WINDOW = Duration.ofMinutes(5);

  private final ExchangeRepository repository;
  private final MarketRules rules;
  private final RiskLimits riskLimits;

  public OrderBookRecoveryService(
      ExchangeRepository repository, MarketRules rules, RiskLimits riskLimits) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.riskLimits = Objects.requireNonNull(riskLimits, "riskLimits");
  }

  public RecoveredMarket recover(String marketId, Instant recoveredAt) throws SQLException {
    Objects.requireNonNull(recoveredAt, "recoveredAt");
    try {
      return repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        return recover(tx, state, recoveredAt);
      });
    } catch (SQLException | RuntimeException failure) {
      enterRecovery(marketId, failure);
      throw failure;
    }
  }

  private void enterRecovery(String marketId, Exception originalFailure) {
    try {
      repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        if (state.status() == MarketStatus.OPEN) {
          tx.updateMarketState(new MarketState(
              state.marketId(), MarketStatus.RECOVERING, state.prioritySequence(),
              state.matchSequence(), state.referencePrice(), state.lastPrice(),
              state.haltedUntil(), state.discoveryQuantity(), state.circuitBreakerLevel(),
              Math.addExact(state.version(), 1)), state.version());
        }
        return null;
      });
    } catch (SQLException | RuntimeException recoveryFailure) {
      originalFailure.addSuppressed(recoveryFailure);
    }
  }

  RecoveredMarket recover(ExchangeTransaction tx, MarketState state, Instant recoveredAt)
      throws SQLException {
    requireMarket(state);
    MarketSnapshot snapshot =
        tx.marketSnapshot(state, recoveredAt.minus(REFERENCE_WINDOW));
    validateSnapshot(snapshot);
    OrderBook book = rebuildBook(snapshot);
    boolean missingDiscovery = state.discoveryQuantity() == null;
    boolean missingBreaker = state.circuitBreakerLevel() == null;
    if (missingDiscovery != missingBreaker) {
      throw new IllegalStateException("market risk metadata is partially reconstructed");
    }
    if (missingDiscovery) {
      ReplayedRisk replayed = replayV1History(tx, state, snapshot, recoveredAt);
      MarketState upgraded = new MarketState(
          state.marketId(), state.status(), state.prioritySequence(), state.matchSequence(),
          state.referencePrice(), state.lastPrice(), state.haltedUntil(),
          replayed.referencePrices().discoveryQuantity(), replayed.circuitBreaker().level(),
          state.version() + 1);
      tx.updateMarketState(upgraded, state.version());
      return new RecoveredMarket(
          book, replayed.referencePrices(), replayed.circuitBreaker(), upgraded);
    }

    List<PriceSample> samples = snapshot.recentTrades().stream()
        .map(sample -> new PriceSample(sample.price(), sample.quantity(), sample.executedAt()))
        .toList();
    ReferencePriceTracker referencePrices = ReferencePriceTracker.restored(
        rules.basePrice(), DISCOVERY_QUANTITY, REFERENCE_WINDOW, rules.priceScale(),
        state.discoveryQuantity(), samples);
    CircuitBreaker circuitBreaker = CircuitBreaker.restored(
        riskLimits, state.circuitBreakerLevel(), state.haltedUntil());
    return new RecoveredMarket(book, referencePrices, circuitBreaker, state);
  }

  private ReplayedRisk replayV1History(
      ExchangeTransaction tx, MarketState state, MarketSnapshot snapshot, Instant recoveredAt)
      throws SQLException {
    ReferencePriceTracker prices = new ReferencePriceTracker(
        rules.basePrice(), DISCOVERY_QUANTITY, REFERENCE_WINDOW, rules.priceScale());
    CircuitBreaker breaker = new CircuitBreaker(riskLimits);
    ReplayCursor cursor = new ReplayCursor(rules.basePrice());
    tx.visitTradeHistory(state.marketId(), sample -> {
      if (sample.matchSequence() <= cursor.matchSequence
          || sample.matchSequence() > state.matchSequence()
          || (cursor.executedAt != null && sample.executedAt().isBefore(cursor.executedAt))) {
        throw new IllegalStateException("full trade history is not deterministic");
      }
      TradePermission permission =
          breaker.onPrice(sample.price(), cursor.referencePrice, sample.executedAt());
      prices.record(sample.price(), sample.quantity(), sample.executedAt());
      if (permission.allowed()) {
        cursor.referencePrice = prices.referenceAt(sample.executedAt());
      }
      cursor.matchSequence = sample.matchSequence();
      cursor.executedAt = sample.executedAt();
    });
    if (cursor.matchSequence != snapshot.maximumMatchSequence()
        || cursor.referencePrice.compareTo(state.referencePrice()) != 0) {
      throw new IllegalStateException("full trade history does not match market state");
    }
    prices.referenceAt(recoveredAt);
    return new ReplayedRisk(prices,
        CircuitBreaker.restored(riskLimits, breaker.level(), state.haltedUntil()));
  }

  private static OrderBook rebuildBook(MarketSnapshot snapshot) {
    OrderBook book = new OrderBook();
    snapshot.openOrders().stream()
        .map(ExchangeTransaction.PersistedOrder::order)
        .sorted(Comparator.comparingLong(Order::prioritySequence))
        .forEach(book::add);
    return book;
  }

  private void requireMarket(MarketState state) {
    if (state == null || !rules.marketId().equals(state.marketId())) {
      throw new IllegalArgumentException("market state does not match recovery rules");
    }
  }

  private static void validateSnapshot(MarketSnapshot snapshot) {
    MarketState state = snapshot.state();
    if (state.prioritySequence() < 0 || state.matchSequence() < 0
        || state.prioritySequence() == Long.MAX_VALUE || state.matchSequence() == Long.MAX_VALUE
        || snapshot.maximumPrioritySequence() > state.prioritySequence()
        || snapshot.maximumMatchSequence() > state.matchSequence()) {
      throw new IllegalStateException("market sequence snapshot is invalid");
    }
    Set<UUID> orderIds = new HashSet<>();
    Set<Long> priorities = new HashSet<>();
    for (ExchangeTransaction.PersistedOrder persisted : snapshot.openOrders()) {
      Order order = persisted.order();
      if (!state.marketId().equals(order.marketId())
          || (order.status() != OrderStatus.OPEN
              && order.status() != OrderStatus.PARTIALLY_FILLED)
          || order.remainingQuantity() <= 0
          || order.prioritySequence() > state.prioritySequence()
          || !orderIds.add(order.orderId()) || !priorities.add(order.prioritySequence())) {
        throw new IllegalStateException("open order snapshot is invalid");
      }
    }
    long previousMatch = 0;
    Instant previousTime = null;
    for (MarketTradeSample sample : snapshot.recentTrades()) {
      if (sample.matchSequence() <= previousMatch
          || sample.matchSequence() > state.matchSequence()
          || (previousTime != null && sample.executedAt().isBefore(previousTime))) {
        throw new IllegalStateException("trade sample snapshot is invalid");
      }
      previousMatch = sample.matchSequence();
      previousTime = sample.executedAt();
    }
  }

  private record ReplayedRisk(
      ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker) {}

  private static final class ReplayCursor {
    private java.math.BigDecimal referencePrice;
    private long matchSequence;
    private Instant executedAt;

    private ReplayCursor(java.math.BigDecimal referencePrice) {
      this.referencePrice = referencePrice;
    }
  }
}
