package com.ghostchu.quickshop.addon.exchange.ui;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable market-list summary based on real 24-hour quote values. */
public record MarketOverviewSnapshot(
    int marketCount,
    int risingCount,
    int fallingCount,
    long totalVolume24h,
    BigDecimal totalNotional24h,
    MarketRow mostActive,
    MarketRow biggestGainer,
    MarketRow biggestLoser) {
  public MarketOverviewSnapshot {
    if (marketCount < 0 || risingCount < 0 || fallingCount < 0 || totalVolume24h < 0
        || risingCount + fallingCount > marketCount) {
      throw new IllegalArgumentException("invalid market overview counts");
    }
    totalNotional24h = Objects.requireNonNull(totalNotional24h, "totalNotional24h");
    if (totalNotional24h.signum() < 0) {
      throw new IllegalArgumentException("totalNotional24h must be non-negative");
    }
  }
}
