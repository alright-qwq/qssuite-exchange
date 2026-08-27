package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookTest {
  @Test
  void choosesHighestBidLowestAskAndOldestAtPrice() {
    OrderBook book = new OrderBook();
    Order bid100 = order(OrderSide.BUY, "100.00", 1);
    Order bid101Old = order(OrderSide.BUY, "101.00", 2);
    Order bid101New = order(OrderSide.BUY, "101.00", 3);
    Order ask103 = order(OrderSide.SELL, "103.00", 4);
    book.add(bid100);
    book.add(bid101Old);
    book.add(bid101New);
    book.add(ask103);

    assertThat(book.best(OrderSide.BUY)).contains(bid101Old);
    assertThat(book.best(OrderSide.SELL)).contains(ask103);
    assertThat(book.cancel(bid101Old.orderId())).contains(bid101Old);
    assertThat(book.best(OrderSide.BUY)).contains(bid101New);
  }

  @Test
  void rejectsDuplicateNonLimitAndInactiveRestingOrders() {
    OrderBook book = new OrderBook();
    Order open = order(OrderSide.BUY, "100.00", 1);
    Order market = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal("100.00"),
        10, 10, OrderStatus.OPEN, 2, 1, 1, Instant.EPOCH, Instant.EPOCH);
    Order cancelled = open.withStatus(OrderStatus.CANCELLED, Instant.EPOCH.plusSeconds(1));

    book.add(open);

    assertThatThrownBy(() -> book.add(open)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> book.add(market)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> book.add(cancelled)).isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isEqualTo(1);
  }

  @Test
  void replacingPartiallyFilledOrderRetainsItsFifoPosition() {
    OrderBook book = new OrderBook();
    Order oldest = order(OrderSide.BUY, "100.00", 1);
    Order newer = order(OrderSide.BUY, "100.00", 2);
    Order partiallyFilled = oldest.withRemaining(8, Instant.EPOCH.plusSeconds(1));
    book.add(oldest);
    book.add(newer);

    book.replaceRemaining(partiallyFilled);

    assertThat(book.best(OrderSide.BUY)).contains(partiallyFilled);
    assertThat(book.cancel(partiallyFilled.orderId())).contains(partiallyFilled);
    assertThat(book.best(OrderSide.BUY)).contains(newer);
  }

  @Test
  void rejectsReplacementWithDifferentIdentitySideOrPriceWithoutCorruptingIndexes() {
    OrderBook book = new OrderBook();
    Order resting = order(OrderSide.BUY, "100.00", 1);
    book.add(resting);

    assertThatThrownBy(() -> book.replaceRemaining(order(OrderSide.BUY, "100.00", 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> book.replaceRemaining(copy(resting, OrderSide.SELL, new BigDecimal("100.00"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> book.replaceRemaining(copy(resting, OrderSide.BUY, new BigDecimal("101.00"))))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(book.cancel(resting.orderId())).contains(resting);
    assertThat(book.openOrderCount()).isZero();
  }

  @Test
  void rejectsReplacementThatChangesImmutableFieldsOrIncreasesQuantity() {
    Order resting = order(OrderSide.BUY, "100.00", 1)
        .withRemaining(5, Instant.EPOCH.plusSeconds(1));

    assertReplacementRejected(resting, replacement(resting, UUID.randomUUID(),
        resting.accountId(), resting.originalQuantity(), 4, resting.prioritySequence()));
    assertReplacementRejected(resting, replacement(resting, resting.requestId(),
        UUID.randomUUID(), resting.originalQuantity(), 4, resting.prioritySequence()));
    assertReplacementRejected(resting, replacement(resting, resting.requestId(),
        resting.accountId(), 20, 4, resting.prioritySequence()));
    assertReplacementRejected(resting, replacement(resting, resting.requestId(),
        resting.accountId(), resting.originalQuantity(), 4, resting.prioritySequence() + 1));
    assertReplacementRejected(resting, replacement(resting, resting.requestId(),
        resting.accountId(), resting.originalQuantity(), 7, resting.prioritySequence()));
  }

  private static Order order(OrderSide side, String price, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        10, 10, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static Order copy(Order order, OrderSide side, BigDecimal price) {
    return new Order(order.orderId(), order.requestId(), order.marketId(), order.accountId(),
        side, order.type(), order.timeInForce(), price, order.slippageBoundary(),
        order.originalQuantity(), order.remainingQuantity(), order.status(), order.prioritySequence(),
        order.configVersion(), order.feeVersion(), order.createdAt(), order.updatedAt());
  }

  private static Order replacement(Order order, UUID requestId, UUID accountId,
                                   long originalQuantity, long remainingQuantity,
                                   long prioritySequence) {
    return new Order(order.orderId(), requestId, order.marketId(), accountId,
        order.side(), order.type(), order.timeInForce(), order.limitPrice(), order.slippageBoundary(),
        originalQuantity, remainingQuantity, OrderStatus.PARTIALLY_FILLED, prioritySequence,
        order.configVersion(), order.feeVersion(), order.createdAt(), order.updatedAt().plusSeconds(1));
  }

  private static void assertReplacementRejected(Order resting, Order replacement) {
    OrderBook book = new OrderBook();
    book.add(resting);

    assertThatThrownBy(() -> book.replaceRemaining(replacement))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(book.best(resting.side())).contains(resting);
    assertThat(book.openOrderCount()).isEqualTo(1);
  }
}
