package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persisted lifecycle state of a virtual security, including issued supply and status. */
public record SecurityDefinitionState(
    String marketId, String symbol, String name, String description, String currencyId,
    BigDecimal basePrice, long totalSupply, long issuedSupply, long minimumUnit,
    String status, UUID recoveryAccount, Instant createdAt, Instant updatedAt, long version) {
  public SecurityDefinitionState {
    if (marketId == null || marketId.isBlank() || symbol == null || symbol.isBlank()
        || name == null || name.isBlank() || currencyId == null || currencyId.isBlank()) {
      throw new IllegalArgumentException("security identity fields are required");
    }
    if (basePrice == null || basePrice.signum() <= 0 || totalSupply <= 0 || minimumUnit <= 0
        || issuedSupply < 0 || issuedSupply > totalSupply) {
      throw new IllegalArgumentException("invalid security supply or price");
    }
  }
}
