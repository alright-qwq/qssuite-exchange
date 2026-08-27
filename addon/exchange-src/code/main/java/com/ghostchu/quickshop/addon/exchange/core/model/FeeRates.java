package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable maker and taker rates selected by an order's fee schedule version. */
public record FeeRates(BigDecimal makerRate, BigDecimal takerRate) {
  public FeeRates {
    validate(makerRate, "makerRate");
    validate(takerRate, "takerRate");
  }

  private static void validate(BigDecimal rate, String name) {
    Objects.requireNonNull(rate, name);
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be between zero and one");
    }
  }
}
