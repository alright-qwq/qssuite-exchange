package com.ghostchu.quickshop.addon.exchange.operations;

import java.util.Map;

/** Immutable, low-cardinality exchange health view. */
public record MetricSnapshot(Map<String, MarketMetrics> markets) {
  public MetricSnapshot {
    markets = Map.copyOf(markets);
  }

  public record MarketMetrics(int queueLength, Latency matchingLatency) { }

  public record Latency(long p50Millis, long p95Millis, long p99Millis) { }
}
