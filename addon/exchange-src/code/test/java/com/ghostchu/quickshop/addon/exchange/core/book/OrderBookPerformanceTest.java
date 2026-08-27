package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class OrderBookPerformanceTest {
  @Test
  @Tag("performance")
  void insertsAndCancelsOneHundredThousandOrdersWithinBaseline() {
    assertTimeout(Duration.ofSeconds(8), () -> {
      OrderBook book = new OrderBook();
      UUID[] ids = new UUID[100_000];
      for (int i = 0; i < ids.length; i++) {
        ids[i] = UUID.randomUUID();
        book.add(new Order(ids[i], UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
            i % 2 == 0 ? OrderSide.BUY : OrderSide.SELL,
            OrderType.LIMIT, TimeInForce.GTC,
            BigDecimal.valueOf(80 + (i % 41)).setScale(2), null,
            1, 1, OrderStatus.OPEN, i + 1L, 1, 1, Instant.EPOCH, Instant.EPOCH));
      }
      for (int i = 0; i < ids.length; i += 2) {
        book.cancel(ids[i]);
      }
    });
  }

  @Test
  @Tag("performance")
  void submitsTwoHundredNonCrossingOrdersWithoutScanningDeepBook() {
    OrderBook book = new OrderBook();
    for (int i = 0; i < 100_000; i++) {
      book.add(order(new UUID(0, i + 1L), new UUID(1, i + 1L), new UUID(2, i + 1L),
          OrderSide.SELL, "100.00", i + 1L));
    }
    AtomicInteger guardedPriceLevels = new AtomicInteger();
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        () -> 1, () -> Instant.EPOCH, UUID::randomUUID,
        price -> {
          guardedPriceLevels.incrementAndGet();
          return true;
        });

    assertTimeout(Duration.ofSeconds(1), () -> {
      for (int i = 0; i < 200; i++) {
        engine.submit(order(new UUID(3, i + 1L), new UUID(4, i + 1L), new UUID(5, i + 1L),
            OrderSide.BUY, "99.00", 100_001L + i));
      }
    });

    assertThat(guardedPriceLevels).hasValue(200);
  }

  @Test
  @Tag("performance")
  void nonCrossingSubmissionAllocationDoesNotScaleWithSamePriceDepth() {
    allocationForNonCrossingSubmission(1_024);

    long shallowAllocation = allocationForNonCrossingSubmission(1);
    long deepAllocation = allocationForNonCrossingSubmission(100_000);

    assertThat(deepAllocation).isLessThan(shallowAllocation + 64 * 1_024);
  }

  private static long allocationForNonCrossingSubmission(int depth) {
    OrderBook book = new OrderBook();
    for (int i = 0; i < depth; i++) {
      book.add(order(new UUID(6, i + 1L), new UUID(7, i + 1L), new UUID(8, i + 1L),
          OrderSide.SELL, "100.00", i + 1L));
    }
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        () -> 1, () -> Instant.EPOCH, UUID::randomUUID, price -> true);
    Order incoming = order(new UUID(9, depth), new UUID(10, depth), new UUID(11, depth),
        OrderSide.BUY, "99.00", depth + 1L);
    com.sun.management.ThreadMXBean threadMetrics =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!threadMetrics.isThreadAllocatedMemoryEnabled()) {
      threadMetrics.setThreadAllocatedMemoryEnabled(true);
    }

    long before = threadMetrics.getCurrentThreadAllocatedBytes();
    engine.submit(incoming);
    return threadMetrics.getCurrentThreadAllocatedBytes() - before;
  }

  private static Order order(UUID orderId, UUID requestId, UUID accountId, OrderSide side,
                             String price, long priority) {
    return new Order(orderId, requestId, "diamond-usd", accountId, side,
        OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        1, 1, OrderStatus.OPEN, priority, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
