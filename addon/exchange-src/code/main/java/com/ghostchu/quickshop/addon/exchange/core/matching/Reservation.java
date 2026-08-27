package com.ghostchu.quickshop.addon.exchange.core.matching;

import java.math.BigDecimal;

public record Reservation(BigDecimal frozenCurrency, long frozenQuantity) {
  public Reservation {
    if (frozenCurrency == null || frozenCurrency.signum() < 0 || frozenQuantity < 0) {
      throw new IllegalArgumentException("reservation cannot be null or negative");
    }
  }
}
