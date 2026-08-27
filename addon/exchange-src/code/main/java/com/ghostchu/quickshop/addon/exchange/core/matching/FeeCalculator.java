package com.ghostchu.quickshop.addon.exchange.core.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record FeeCalculator(int currencyScale) {
  public FeeCalculator {
    if (currencyScale < 0) {
      throw new IllegalArgumentException("currencyScale cannot be negative");
    }
  }

  public BigDecimal fee(BigDecimal notional, BigDecimal rate) {
    if (notional == null || rate == null || notional.signum() < 0 || rate.signum() < 0) {
      throw new IllegalArgumentException("notional and rate must be non-negative");
    }
    return notional.multiply(rate).setScale(currencyScale, RoundingMode.UP);
  }
}
