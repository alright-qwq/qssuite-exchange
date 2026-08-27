package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.util.Objects;

/** Per-market account limits applied to new order exposure. */
public record AccountOrderLimits(
    long maximumHolding, BigDecimal maximumFrozenCurrency, int maximumOpenOrders,
    int operationsPerSecond, int operationsPerMinute) {

  public AccountOrderLimits {
    maximumFrozenCurrency = Objects.requireNonNull(
        maximumFrozenCurrency, "maximumFrozenCurrency");
    if (maximumHolding <= 0 || maximumFrozenCurrency.signum() <= 0
        || maximumOpenOrders <= 0 || operationsPerSecond <= 0 || operationsPerMinute <= 0) {
      throw new IllegalArgumentException("account order limits must be positive");
    }
  }

  public static AccountOrderLimits defaults() {
    return new AccountOrderLimits(100_000, new BigDecimal("10000000.00"), 100, 5, 60);
  }
}
