package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process metrics limited to configured market identifiers, never player identifiers. */
public final class ExchangeMetrics {
  private static final int MAX_SAMPLES = 4_096;
  private final Map<String, MarketAccumulator> markets = new ConcurrentHashMap<>();

  public void recordQueueLength(String marketId, int queueLength) {
    if (queueLength < 0) {
      throw new IllegalArgumentException("queue length cannot be negative");
    }
    accumulator(marketId).queueLength.set(queueLength);
  }

  public void recordMatchingLatency(String marketId, Duration latency) {
    Objects.requireNonNull(latency, "latency");
    if (latency.isNegative()) {
      throw new IllegalArgumentException("latency cannot be negative");
    }
    accumulator(marketId).record(latency.toMillis());
  }

  public MetricSnapshot snapshot() {
    Map<String, MetricSnapshot.MarketMetrics> snapshot = new HashMap<>();
    markets.forEach((marketId, accumulator) -> snapshot.put(marketId,
        new MetricSnapshot.MarketMetrics(accumulator.queueLength.get(), accumulator.latency())));
    return new MetricSnapshot(snapshot);
  }

  private MarketAccumulator accumulator(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    return markets.computeIfAbsent(marketId, ignored -> new MarketAccumulator());
  }

  private static final class MarketAccumulator {
    private final AtomicInteger queueLength = new AtomicInteger();
    private final List<Long> samples = new ArrayList<>();

    private synchronized void record(long millis) {
      if (samples.size() == MAX_SAMPLES) {
        samples.remove(0);
      }
      samples.add(millis);
    }

    private synchronized MetricSnapshot.Latency latency() {
      if (samples.isEmpty()) {
        return new MetricSnapshot.Latency(0, 0, 0);
      }
      List<Long> sorted = new ArrayList<>(samples);
      sorted.sort(Long::compareTo);
      return new MetricSnapshot.Latency(percentile(sorted, 0.50), percentile(sorted, 0.95),
          percentile(sorted, 0.99));
    }

    private static long percentile(List<Long> sorted, double percentile) {
      int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
      return sorted.get(index);
    }
  }
}
