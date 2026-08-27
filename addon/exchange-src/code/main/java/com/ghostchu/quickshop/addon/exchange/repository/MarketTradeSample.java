package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTradeSample(
    BigDecimal price, long quantity, long matchSequence, Instant executedAt) {
  public MarketTradeSample {
    if (price == null || price.signum() <= 0 || quantity <= 0 || matchSequence <= 0
        || executedAt == null) {
      throw new IllegalArgumentException("invalid market trade sample");
    }
  }
}
