package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandleAggregatorTest {
  @Test
  void aggregatesOneMinuteOhlcvAndNotional() {
    CandleAggregator aggregator = new CandleAggregator();
    aggregator.record("diamond-usd", new BigDecimal("100.00"), 2,
        Instant.parse("2026-07-26T00:00:10Z"));
    aggregator.record("diamond-usd", new BigDecimal("110.00"), 3,
        Instant.parse("2026-07-26T00:00:40Z"));

    Candle candle = aggregator.snapshot("diamond-usd",
        Instant.parse("2026-07-26T00:00:00Z")).orElseThrow();

    assertThat(candle.open()).isEqualByComparingTo("100.00");
    assertThat(candle.high()).isEqualByComparingTo("110.00");
    assertThat(candle.low()).isEqualByComparingTo("100.00");
    assertThat(candle.close()).isEqualByComparingTo("110.00");
    assertThat(candle.volume()).isEqualTo(5);
    assertThat(candle.notional()).isEqualByComparingTo("530.00");
  }
}
