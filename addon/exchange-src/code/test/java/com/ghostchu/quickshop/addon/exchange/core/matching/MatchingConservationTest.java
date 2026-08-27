package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import org.junit.jupiter.api.RepeatedTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingConservationTest {
  @RepeatedTest(20)
  void matchedQuantityNeverExceedsSubmittedQuantity() {
    Random random = new Random(0x515345L);
    OrderBook book = new OrderBook();
    AtomicLong priority = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        priority::incrementAndGet, () -> Instant.EPOCH, UUID::randomUUID);
    long submittedBuy = 0;
    long submittedSell = 0;
    long traded = 0;
    for (int i = 0; i < 2_000; i++) {
      long quantity = random.nextLong(1, 101);
      OrderSide side = random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
      if (side == OrderSide.BUY) {
        submittedBuy += quantity;
      } else {
        submittedSell += quantity;
      }
      Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
          side, OrderType.LIMIT, TimeInForce.GTC,
          BigDecimal.valueOf(random.nextLong(80, 121)).setScale(2), null,
          quantity, quantity, OrderStatus.OPEN, priority.incrementAndGet(),
          1, 1, Instant.EPOCH, Instant.EPOCH);
      MatchResult result = engine.submit(order);
      traded += result.trades().stream().mapToLong(Trade::quantity).sum();
      assertThat(result.trades()).allSatisfy(trade -> {
        assertThat(trade.quantity()).isPositive();
        assertThat(trade.makerFee()).isNotNegative();
        assertThat(trade.takerFee()).isNotNegative();
      });
    }
    long restingBuy = book.snapshot().stream().filter(order -> order.side() == OrderSide.BUY)
        .mapToLong(Order::remainingQuantity).sum();
    long restingSell = book.snapshot().stream().filter(order -> order.side() == OrderSide.SELL)
        .mapToLong(Order::remainingQuantity).sum();
    assertThat(traded + restingBuy).isLessThanOrEqualTo(submittedBuy);
    assertThat(traded + restingSell).isLessThanOrEqualTo(submittedSell);
  }
}
