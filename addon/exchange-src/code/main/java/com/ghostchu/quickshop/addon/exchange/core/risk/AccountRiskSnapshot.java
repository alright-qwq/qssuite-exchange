package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.util.Objects;

public record AccountRiskSnapshot(long holding, BigDecimal frozenCurrency, int openOrders) {
  public AccountRiskSnapshot {
    if (holding < 0 || openOrders < 0) {
      throw new IllegalArgumentException("account exposure cannot be negative");
    }
    frozenCurrency = Objects.requireNonNull(frozenCurrency, "frozenCurrency");
    if (frozenCurrency.signum() < 0) {
      throw new IllegalArgumentException("frozenCurrency cannot be negative");
    }
  }

  public boolean canAddHolding(long added, long maximum) {
    if (added < 0 || maximum < 0) {
      return false;
    }
    long remaining;
    try {
      remaining = Math.subtractExact(maximum, added);
    } catch (ArithmeticException overflow) {
      // A maximum too large for the limit arithmetic cannot be enforced safely.
      return false;
    }
    return holding >= 0 && holding <= remaining;
  }

  public boolean canFreeze(BigDecimal added, BigDecimal maximum) {
    if (added == null || maximum == null || added.signum() < 0 || maximum.signum() < 0) {
      return false;
    }
    return frozenCurrency.add(added).compareTo(maximum) <= 0;
  }

  public boolean canOpenOrder(int maximum) {
    return maximum >= 0 && openOrders < maximum;
  }
}
