package com.ghostchu.quickshop.addon.exchange.config;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable metadata for a server-managed, ledger-only security. */
public record SecurityDefinition(String symbol, String name, String description,
                                 String currencyId, BigDecimal basePrice,
                                 long totalSupply, long minimumUnit) {
  public SecurityDefinition {
    if (symbol == null || !symbol.matches("[A-Z][A-Z0-9_]{0,15}")) {
      throw new IllegalArgumentException("symbol must be uppercase alphanumeric");
    }
    requireText(name, "name");
    requireText(description, "description");
    requireText(currencyId, "currencyId");
    if (basePrice == null || basePrice.signum() <= 0) {
      throw new IllegalArgumentException("basePrice must be positive");
    }
    if (totalSupply <= 0 || minimumUnit <= 0 || totalSupply % minimumUnit != 0) {
      throw new IllegalArgumentException("totalSupply must be a positive multiple of minimumUnit");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
