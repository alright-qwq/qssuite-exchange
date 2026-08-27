package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitMatchingTest {
  @Test
  void fillsAcrossMakersAtTheirPricesAndRestsRemainder() {
    AtomicLong matches = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(new OrderBook(), TestFixtures.rules(), new FeeCalculator(2),
        matches::incrementAndGet, () -> Instant.parse("2026-07-26T00:00:00Z"), UUID::randomUUID);
    engine.submit(order(OrderSide.SELL, "99.00", 4, 1));
    engine.submit(order(OrderSide.SELL, "100.00", 4, 2));

    MatchResult result = engine.submit(order(OrderSide.BUY, "101.00", 10, 3));

    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("99.00"), new BigDecimal("100.00"));
    assertThat(result.trades()).extracting(Trade::quantity).containsExactly(4L, 4L);
    assertThat(result.finalOrder().remainingQuantity()).isEqualTo(2);
    assertThat(result.rested()).isTrue();
  }

  @Test
  void rejectsEntireIncomingOrderBeforeItWouldReachSelfTrade() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book);
    UUID incomingAccount = UUID.randomUUID();
    Order externalMaker = order(OrderSide.SELL, "99.00", 1, 1, UUID.randomUUID(),
        "diamond-usd", UUID.randomUUID());
    Order ownMaker = order(OrderSide.SELL, "100.00", 1, 2, incomingAccount,
        "diamond-usd", UUID.randomUUID());
    engine.submit(externalMaker);
    engine.submit(ownMaker);
    Order incoming = order(OrderSide.BUY, "101.00", 2, 3, incomingAccount,
        "diamond-usd", UUID.randomUUID());

    MatchResult result = engine.submit(incoming);

    assertThat(result.selfTradeRejected()).isTrue();
    assertThat(result.trades()).isEmpty();
    assertThat(result.changedMakers()).isEmpty();
    assertThat(result.finalOrder()).isEqualTo(incoming);
    assertThat(book.openOrderCount()).isEqualTo(2);
    assertThat(book.best(OrderSide.SELL)).contains(externalMaker);
  }

  @Test
  void rejectsDuplicateCrossMarketAndNonOpenIncomingOrdersWithoutMutation() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book);
    Order maker = order(OrderSide.SELL, "100.00", 2, 1, UUID.randomUUID(),
        "diamond-usd", UUID.randomUUID());
    engine.submit(maker);

    Order duplicate = order(OrderSide.BUY, "101.00", 1, 2, UUID.randomUUID(),
        "diamond-usd", maker.orderId());
    Order otherMarket = order(OrderSide.BUY, "101.00", 1, 3, UUID.randomUUID(),
        "gold-usd", UUID.randomUUID());
    Order partiallyFilled = order(OrderSide.BUY, "101.00", 2, 4, UUID.randomUUID(),
        "diamond-usd", UUID.randomUUID()).withRemaining(1, Instant.EPOCH.plusSeconds(1));

    assertThatThrownBy(() -> engine.submit(duplicate)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.submit(otherMarket)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.submit(partiallyFilled)).isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isEqualTo(1);
    assertThat(book.best(OrderSide.SELL)).contains(maker);
  }

  @Test
  void supplierFailureDoesNotMutateRestingMaker() {
    OrderBook book = new OrderBook();
    AtomicLong matches = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        matches::incrementAndGet, () -> Instant.parse("2026-07-26T00:00:00Z"), () -> null);
    Order maker = order(OrderSide.SELL, "100.00", 1, 1);
    engine.submit(maker);

    assertThatThrownBy(() -> engine.submit(order(OrderSide.BUY, "100.00", 1, 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isEqualTo(1);
    assertThat(book.best(OrderSide.SELL)).contains(maker);
  }

  @Test
  void replacementPreflightPreventsPartialPublicationWhenClockMovesBackward() {
    OrderBook book = new OrderBook();
    AtomicLong matches = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        matches::incrementAndGet, () -> Instant.EPOCH.plusSeconds(5), UUID::randomUUID);
    Order firstMaker = order(OrderSide.SELL, "99.00", 1, 1);
    Order secondMaker = withUpdatedAt(order(OrderSide.SELL, "100.00", 2, 2),
        Instant.EPOCH.plusSeconds(10));
    engine.submit(firstMaker);
    engine.submit(secondMaker);

    assertThatThrownBy(() -> engine.submit(order(OrderSide.BUY, "101.00", 2, 3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isEqualTo(2);
    assertThat(book.best(OrderSide.SELL)).contains(firstMaker);
    assertThat(book.cancel(firstMaker.orderId())).contains(firstMaker);
    assertThat(book.best(OrderSide.SELL)).contains(secondMaker);
  }

  @Test
  void nonPositivePricesAreRejectedBeforeTheBookCanMutate() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book);

    assertThatThrownBy(() -> engine.submit(new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, BigDecimal.ZERO, null,
        1, 1, OrderStatus.OPEN, 1, 1, 1, Instant.EPOCH, Instant.EPOCH)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.submit(new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal("-1.00"),
        1, 1, OrderStatus.OPEN, 2, 1, 1, Instant.EPOCH, Instant.EPOCH)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isZero();
  }

  private static Order order(OrderSide side, String price, long quantity, long sequence) {
    return order(side, price, quantity, sequence, UUID.randomUUID(),
        "diamond-usd", UUID.randomUUID());
  }

  private static Order order(OrderSide side, String price, long quantity, long sequence,
                             UUID accountId, String marketId, UUID orderId) {
    return new Order(orderId, UUID.randomUUID(), marketId, accountId,
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static MatchingEngine engine(OrderBook book) {
    AtomicLong matches = new AtomicLong();
    return new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        matches::incrementAndGet, () -> Instant.parse("2026-07-26T00:00:00Z"), UUID::randomUUID);
  }

  private static Order withUpdatedAt(Order order, Instant updatedAt) {
    return new Order(order.orderId(), order.requestId(), order.marketId(), order.accountId(),
        order.side(), order.type(), order.timeInForce(), order.limitPrice(), order.slippageBoundary(),
        order.originalQuantity(), order.remainingQuantity(), order.status(), order.prioritySequence(),
        order.configVersion(), order.feeVersion(), order.createdAt(), updatedAt);
  }
}
