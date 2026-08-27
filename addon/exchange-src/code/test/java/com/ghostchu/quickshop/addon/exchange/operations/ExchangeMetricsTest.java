package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMetricsTest {
  @Test
  void snapshotsQueueAndLatencyWithoutAccountLabels() {
    ExchangeMetrics metrics = new ExchangeMetrics();
    metrics.recordQueueLength("diamond-usd", 7);
    metrics.recordMatchingLatency("diamond-usd", Duration.ofMillis(10));
    metrics.recordMatchingLatency("diamond-usd", Duration.ofMillis(30));

    MetricSnapshot snapshot = metrics.snapshot();

    assertThat(snapshot.markets()).containsOnlyKeys("diamond-usd");
    assertThat(snapshot.markets().get("diamond-usd").queueLength()).isEqualTo(7);
    assertThat(snapshot.markets().get("diamond-usd").matchingLatency().p50Millis())
        .isEqualTo(10);
    assertThat(snapshot.markets().get("diamond-usd").matchingLatency().p95Millis())
        .isEqualTo(30);
  }
}
