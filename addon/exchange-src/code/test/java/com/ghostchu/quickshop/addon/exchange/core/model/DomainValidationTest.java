package com.ghostchu.quickshop.addon.exchange.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainValidationTest {
  @Test
  void rejectsPriceOffTick() {
    assertThatThrownBy(() -> rules().validatePrice(new BigDecimal("10.02")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("price is not aligned to tickSize");
  }

  @Test
  void acceptsPriceTrailingZerosWithinNumericScale() {
    assertThatCode(() -> rules().validatePrice(new BigDecimal("10.050")))
        .doesNotThrowAnyException();
  }

  @Test
  void requiresAlignedAndBoundedMarketRulePrices() {
    assertThatThrownBy(() -> marketRules(new BigDecimal("10000.05"), new BigDecimal("1.00"),
        new BigDecimal("10000.00"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> marketRules(new BigDecimal("100.02"), new BigDecimal("1.00"),
        new BigDecimal("10000.00"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> marketRules(new BigDecimal("100.00"), new BigDecimal("1.001"),
        new BigDecimal("10000.00"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void marketOrderMustBeIoc() {
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, null,
        new BigDecimal("12.00"), 5, 5, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH))
        .hasMessage("market order requires IOC");
  }

  @Test
  void requiresCompleteOrderMetadata() {
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), " ", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("12.00"), null,
        5, 5, OrderStatus.OPEN, 1, 1, 1, Instant.EPOCH, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        null, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("12.00"), null,
        5, 5, OrderStatus.OPEN, 1, 1, 1, Instant.EPOCH, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("12.00"), null,
        5, 5, OrderStatus.OPEN, 0, 1, 1, Instant.EPOCH, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresExclusiveOrderPricingAndConsistentStatus() {
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("12.00"),
        new BigDecimal("11.00"), 5, 5, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, new BigDecimal("12.00"),
        new BigDecimal("11.00"), 5, 5, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limitOrder(5, 4, OrderStatus.OPEN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limitOrder(5, 0, OrderStatus.PARTIALLY_FILLED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permitsOnlyLegalOrderTransitions() {
    assertThatThrownBy(() -> limitOrder(5, 5, OrderStatus.OPEN).withRemaining(5, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limitOrder(5, 5, OrderStatus.OPEN).withRemaining(6, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limitOrder(5, 0, OrderStatus.FILLED).withStatus(OrderStatus.OPEN, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limitOrder(5, 5, OrderStatus.OPEN).withStatus(OrderStatus.FILLED, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(limitOrder(5, 5, OrderStatus.OPEN).withRemaining(0, Instant.now()).status())
        .isEqualTo(OrderStatus.FILLED);
    assertThat(limitOrder(5, 5, OrderStatus.OPEN).withStatus(OrderStatus.CANCELLED, Instant.now()).status())
        .isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  void requiresCompleteTradeIdentityAndDistinctParties() {
    UUID orderId = UUID.randomUUID();
    assertThatThrownBy(() -> new Trade(
        UUID.randomUUID(), " ", orderId, orderId, UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("12.00"), 5, BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    UUID accountId = UUID.randomUUID();
    assertThatThrownBy(() -> new Trade(
        UUID.randomUUID(), "diamond-usd", UUID.randomUUID(), UUID.randomUUID(), accountId, accountId,
        new BigDecimal("12.00"), 5, BigDecimal.ZERO, BigDecimal.ZERO, 1, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresPositiveTradeSequenceAndNonNegativeFees() {
    assertThatThrownBy(() -> new Trade(
        UUID.randomUUID(), "diamond-usd", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.00"), 5,
        new BigDecimal("-0.01"), BigDecimal.ZERO, 1, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Trade(
        UUID.randomUUID(), "diamond-usd", UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("12.00"), 5,
        BigDecimal.ZERO, null, 0, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatesMonotonicVersionSevenIdsWithinOneMillisecond() {
    TimeOrderedIdGenerator ids =
        new TimeOrderedIdGenerator(() -> 1_721_952_000_000L, new java.util.Random(7));
    UUID first = ids.get();
    UUID second = ids.get();
    assertThat(first.version()).isEqualTo(7);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(second.compareTo(first)).isPositive();
  }

  @Test
  void remainsMonotonicWhenClockMovesBackward() {
    long[] clock = {1_721_952_000_000L};
    TimeOrderedIdGenerator ids = new TimeOrderedIdGenerator(() -> clock[0], new java.util.Random(7));
    UUID first = ids.get();
    clock[0]--;
    UUID second = ids.get();

    assertThat(second.compareTo(first)).isPositive();
  }

  @Test
  void remainsMonotonicBeyondOneMillisecondSequenceCapacity() {
    TimeOrderedIdGenerator ids =
        new TimeOrderedIdGenerator(() -> 1_721_952_000_000L, new java.util.Random(7));
    UUID previous = ids.get();

    for (int index = 0; index < 4_100; index++) {
      UUID next = ids.get();
      assertThat(next.version()).isEqualTo(7);
      assertThat(next.variant()).isEqualTo(2);
      assertThat(next.compareTo(previous)).isPositive();
      previous = next;
    }
  }

  private static MarketRules rules() {
    return new MarketRules("diamond-usd", "USD", new BigDecimal("100.00"),
        new BigDecimal("1.00"), new BigDecimal("10000.00"), new BigDecimal("0.05"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }

  private static MarketRules marketRules(BigDecimal basePrice, BigDecimal minPrice, BigDecimal maxPrice) {
    return new MarketRules("diamond-usd", "USD", basePrice, minPrice, maxPrice,
        new BigDecimal("0.05"), 1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }

  private static Order limitOrder(long original, long remaining, OrderStatus status) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("12.00"), null,
        original, remaining, status, 1, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
