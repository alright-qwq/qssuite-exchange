package com.ghostchu.quickshop.addon.exchange.core.risk;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchResult;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRiskTest {
  @Test
  void blendsBasePriceUntilDiscoveryVolumeReached() {
    ReferencePriceTracker tracker =
        new ReferencePriceTracker(new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);

    tracker.record(new BigDecimal("120.00"), 50, Instant.EPOCH);

    assertThat(tracker.referenceAt(Instant.EPOCH)).isEqualByComparingTo("110.00");
  }

  @Test
  void expiresSamplesOutsideFixedWindow() {
    ReferencePriceTracker tracker =
        new ReferencePriceTracker(new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);
    tracker.record(new BigDecimal("120.00"), 100, Instant.EPOCH);

    assertThat(tracker.referenceAt(Instant.EPOCH.plus(Duration.ofMinutes(5))))
        .isEqualByComparingTo("120.00");
    assertThat(tracker.referenceAt(Instant.EPOCH.plus(Duration.ofMinutes(5)).plusNanos(1)))
        .isEqualByComparingTo("100.00");
  }

  @Test
  void saturatesDiscoveryQuantityAtConfiguredTarget() {
    ReferencePriceTracker tracker =
        new ReferencePriceTracker(new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);

    tracker.record(new BigDecimal("105.00"), Long.MAX_VALUE, Instant.EPOCH);

    assertThat(tracker.discoveryQuantity()).isEqualTo(100);
  }

  @Test
  void restoresExactWindowAndDiscoveryState() {
    ReferencePriceTracker tracker = ReferencePriceTracker.restored(
        new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2, 50,
        List.of(new PriceSample(new BigDecimal("105.00"), 50, Instant.EPOCH)));

    tracker.record(new BigDecimal("105.00"), 1, Instant.EPOCH.plusSeconds(1));

    assertThat(tracker.referenceAt(Instant.EPOCH.plusSeconds(1)))
        .isEqualByComparingTo("102.55");
    assertThat(tracker.discoveryQuantity()).isEqualTo(51);
  }

  @Test
  void restoresExactBreakerLevelAfterResume() {
    CircuitBreaker breaker = CircuitBreaker.restored(RiskLimits.defaults(), 1, null);

    TradePermission permission = breaker.onPrice(
        new BigDecimal("120.00"), new BigDecimal("100.00"), Instant.EPOCH);

    assertThat(permission.level()).isEqualTo(2);
    assertThat(breaker.level()).isEqualTo(2);
  }

  @Test
  void rejectsRestoredTrackerWithNonPositiveBasePrice() {
    assertThatThrownBy(() -> ReferencePriceTracker.restored(
        new BigDecimal("0"), 100, Duration.ofMinutes(5), 2, 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRestoredBreakerWithNonPositiveReferencePrice() {
    assertThatThrownBy(() -> CircuitBreaker.restored(
        RiskLimits.defaults(), MarketStatus.OPEN,
        new BigDecimal("0"), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidPriceSamples() {
    assertThatThrownBy(() -> new PriceSample(null, 1, Instant.EPOCH))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PriceSample(new BigDecimal("0"), 1, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PriceSample(new BigDecimal("1"), 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PriceSample(new BigDecimal("1"), 1, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void cagesPriceAndEscalatesBreaker() {
    RiskLimits limits = RiskLimits.defaults();
    assertThat(limits.insideCage(new BigDecimal("120.00"), new BigDecimal("100.00"))).isTrue();
    assertThat(limits.insideCage(new BigDecimal("120.01"), new BigDecimal("100.00"))).isFalse();

    CircuitBreaker breaker = new CircuitBreaker(limits);
    Instant now = Instant.parse("2026-07-26T00:00:00Z");
    assertThat(breaker.onPrice(new BigDecimal("111.00"), new BigDecimal("100.00"), now).haltUntil())
        .contains(now.plus(Duration.ofMinutes(2)));
    breaker.resume(now.plus(Duration.ofMinutes(2)));
    assertThat(breaker.onPrice(new BigDecimal("121.00"), new BigDecimal("100.00"),
        now.plus(Duration.ofMinutes(3))).haltUntil())
        .contains(now.plus(Duration.ofMinutes(13)));
  }

  @Test
  void protectedBestLevelStaysInBookWhileNextExecutableLevelTrades() {
    RiskLimits limits = RiskLimits.defaults();
    Predicate<BigDecimal> guard =
        price -> limits.insideCage(price, new BigDecimal("100.00"));
    OrderBook book = new OrderBook();
    Order protectedAsk = limit(OrderSide.SELL, "70.00", 1, UUID.randomUUID());
    Order executableAsk = limit(OrderSide.SELL, "90.00", 2, UUID.randomUUID());
    book.add(protectedAsk);
    book.add(executableAsk);
    MatchingEngine engine = engine(book, guard);

    MatchResult result = engine.submit(limit(OrderSide.BUY, "100.00", 3, UUID.randomUUID()));

    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("90.00"));
    assertThat(book.orders(OrderSide.SELL)).containsExactly(protectedAsk);
  }

  @Test
  void protectedSelfOrderDoesNotCauseFalseSelfTradeRejection() {
    RiskLimits limits = RiskLimits.defaults();
    Predicate<BigDecimal> guard =
        price -> limits.insideCage(price, new BigDecimal("100.00"));
    OrderBook book = new OrderBook();
    UUID takerAccount = UUID.randomUUID();
    Order protectedSelfAsk = limit(OrderSide.SELL, "70.00", 1, takerAccount);
    Order executableAsk = limit(OrderSide.SELL, "90.00", 2, UUID.randomUUID());
    book.add(protectedSelfAsk);
    book.add(executableAsk);

    MatchResult result = engine(book, guard)
        .submit(limit(OrderSide.BUY, "100.00", 3, takerAccount));

    assertThat(result.selfTradeRejected()).isFalse();
    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("90.00"));
    assertThat(book.orders(OrderSide.SELL)).containsExactly(protectedSelfAsk);
  }

  @Test
  void marketOrderWithOnlyProtectedLiquidityCancelsWithoutMutatingBook() {
    RiskLimits limits = RiskLimits.defaults();
    Predicate<BigDecimal> guard =
        price -> limits.insideCage(price, new BigDecimal("100.00"));
    OrderBook book = new OrderBook();
    Order protectedAsk = limit(OrderSide.SELL, "70.00", 1, UUID.randomUUID());
    book.add(protectedAsk);

    MatchResult result = engine(book, guard).submit(marketBuy("100.00", 1, 2));

    assertThat(result.trades()).isEmpty();
    assertThat(result.finalOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(result.rested()).isFalse();
    assertThat(book.orders(OrderSide.SELL)).containsExactly(protectedAsk);
  }

  @Test
  void orderBookBestExecutableSkipsProtectedPricesWithoutRemovingThem() {
    OrderBook book = new OrderBook();
    Order protectedAsk = limit(OrderSide.SELL, "70.00", 1, UUID.randomUUID());
    Order executableAsk = limit(OrderSide.SELL, "90.00", 2, UUID.randomUUID());
    book.add(protectedAsk);
    book.add(executableAsk);

    assertThat(book.bestExecutable(OrderSide.SELL,
        price -> price.compareTo(new BigDecimal("80.00")) >= 0)).contains(executableAsk);
    assertThat(book.orders(OrderSide.SELL)).containsExactly(protectedAsk, executableAsk);
  }

  @Test
  void rejectsMissingExecutablePriceDependency() {
    OrderBook book = new OrderBook();

    assertThatThrownBy(() -> new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        () -> 1, () -> Instant.EPOCH, UUID::randomUUID, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MatchingEngine engine(OrderBook book, Predicate<BigDecimal> executablePrice) {
    return new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        () -> 1, () -> Instant.EPOCH, UUID::randomUUID, executablePrice);
  }

  private static Order limit(OrderSide side, String price, long priority, UUID accountId) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", accountId, side,
        OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        1, 1, OrderStatus.OPEN, priority, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static Order marketBuy(String boundary, long quantity, long priority) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal(boundary),
        quantity, quantity, OrderStatus.OPEN, priority, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
