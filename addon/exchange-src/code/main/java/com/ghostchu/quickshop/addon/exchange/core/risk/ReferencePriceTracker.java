package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

public final class ReferencePriceTracker {
  private final BigDecimal basePrice;
  private final long discoveryQuantity;
  private final Duration window;
  private final int scale;
  private final ArrayDeque<PriceSample> samples = new ArrayDeque<>();
  private long cumulativeDiscoveryQuantity;

  public ReferencePriceTracker(BigDecimal basePrice, long discoveryQuantity,
                               Duration window, int scale) {
    Objects.requireNonNull(basePrice, "basePrice");
    if (basePrice.signum() <= 0) {
      throw new IllegalArgumentException("base price must be positive");
    }
    if (discoveryQuantity < 10) {
      throw new IllegalArgumentException("discovery quantity must be at least 10");
    }
    Objects.requireNonNull(window, "window");
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("reference window must be positive");
    }
    if (scale < 0 || scale > 12) {
      throw new IllegalArgumentException("price scale must be between 0 and 12");
    }
    this.basePrice = basePrice;
    this.discoveryQuantity = discoveryQuantity;
    this.window = window;
    this.scale = scale;
  }

  public void record(BigDecimal price, long quantity, Instant occurredAt) {
    requireSample(price, quantity, occurredAt);
    PriceSample previous = samples.peekLast();
    if (previous != null && occurredAt.isBefore(previous.occurredAt())) {
      throw new IllegalArgumentException("price samples must be chronological");
    }
    samples.addLast(new PriceSample(price, quantity, occurredAt));
    long remainingDiscovery = discoveryQuantity - cumulativeDiscoveryQuantity;
    cumulativeDiscoveryQuantity = Math.addExact(cumulativeDiscoveryQuantity,
        Math.min(quantity, remainingDiscovery));
  }

  public BigDecimal referenceAt(Instant now) {
    Instant cutoff = now.minus(window);
    while (!samples.isEmpty() && samples.peekFirst().occurredAt().isBefore(cutoff)) {
      samples.removeFirst();
    }
    if (samples.isEmpty()) {
      return basePrice;
    }
    BigDecimal notional = BigDecimal.ZERO;
    long volume = 0;
    for (PriceSample sample : samples) {
      notional = notional.add(sample.price().multiply(BigDecimal.valueOf(sample.quantity())));
      volume = Math.addExact(volume, sample.quantity());
    }
    BigDecimal vwap = notional.divide(BigDecimal.valueOf(volume), scale + 6, RoundingMode.HALF_UP);
    BigDecimal ratio = BigDecimal.valueOf(Math.min(cumulativeDiscoveryQuantity, discoveryQuantity))
        .divide(BigDecimal.valueOf(discoveryQuantity), scale + 6, RoundingMode.HALF_UP);
    return basePrice.multiply(BigDecimal.ONE.subtract(ratio)).add(vwap.multiply(ratio))
        .setScale(scale, RoundingMode.HALF_UP);
  }

  public ReferencePriceTracker copy() {
    ReferencePriceTracker copy = new ReferencePriceTracker(
        basePrice, discoveryQuantity, window, scale);
    copy.samples.addAll(samples);
    copy.cumulativeDiscoveryQuantity = cumulativeDiscoveryQuantity;
    return copy;
  }

  public long discoveryQuantity() {
    return cumulativeDiscoveryQuantity;
  }

  public List<PriceSample> samples() {
    return List.copyOf(samples);
  }

  public static ReferencePriceTracker restored(
      BigDecimal referencePrice, long discoveryQuantity, Duration window, int scale) {
    return new ReferencePriceTracker(referencePrice, discoveryQuantity, window, scale);
  }

  public static ReferencePriceTracker restored(
      BigDecimal basePrice, long discoveryQuantity, Duration window, int scale,
      long cumulativeDiscoveryQuantity, List<PriceSample> samples) {
    if (cumulativeDiscoveryQuantity < 0 || cumulativeDiscoveryQuantity > discoveryQuantity) {
      throw new IllegalArgumentException("discovery quantity is outside configured bounds");
    }
    ReferencePriceTracker restored =
        new ReferencePriceTracker(basePrice, discoveryQuantity, window, scale);
    Objects.requireNonNull(samples, "samples");
    long recentQuantity = 0;
    Instant previous = null;
    for (PriceSample sample : samples) {
      Objects.requireNonNull(sample, "sample");
      requireSample(sample.price(), sample.quantity(), sample.occurredAt());
      if (previous != null && sample.occurredAt().isBefore(previous)) {
        throw new IllegalArgumentException("price samples must be chronological");
      }
      previous = sample.occurredAt();
      long remainingDiscovery = discoveryQuantity - recentQuantity;
      recentQuantity = Math.addExact(recentQuantity,
          Math.min(sample.quantity(), remainingDiscovery));
      restored.samples.addLast(sample);
    }
    if (cumulativeDiscoveryQuantity < discoveryQuantity
        && recentQuantity > cumulativeDiscoveryQuantity) {
      throw new IllegalArgumentException("recent quantity exceeds cumulative discovery quantity");
    }
    restored.cumulativeDiscoveryQuantity = cumulativeDiscoveryQuantity;
    return restored;
  }

  private static void requireSample(BigDecimal price, long quantity, Instant occurredAt) {
    if (price == null || price.signum() <= 0 || quantity <= 0 || occurredAt == null) {
      throw new IllegalArgumentException("price sample must be positive and timestamped");
    }
  }
}
