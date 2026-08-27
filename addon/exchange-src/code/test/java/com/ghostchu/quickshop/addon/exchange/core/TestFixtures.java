package com.ghostchu.quickshop.addon.exchange.core;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;

import java.math.BigDecimal;

public final class TestFixtures {
  private TestFixtures() {
  }

  public static MarketRules rules() {
    return new MarketRules("diamond-usd", "USD", new BigDecimal("100.00"),
        new BigDecimal("1.00"), new BigDecimal("10000.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }
}
