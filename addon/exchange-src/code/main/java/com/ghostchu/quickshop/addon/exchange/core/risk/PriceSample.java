package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PriceSample(BigDecimal price, long quantity, Instant occurredAt) {
  public PriceSample {
    Objects.requireNonNull(price, "price");
    if (price.signum() <= 0) {
      throw new IllegalArgumentException("price must be positive");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
