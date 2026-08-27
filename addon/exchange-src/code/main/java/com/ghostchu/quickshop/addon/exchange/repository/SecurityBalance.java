package com.ghostchu.quickshop.addon.exchange.repository;

import java.util.UUID;

/** Ledger-only virtual security balance for one owner. */
public record SecurityBalance(UUID accountId, String marketId,
                              long availableQuantity, long frozenQuantity, long version) {
  public SecurityBalance {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    if (availableQuantity < 0 || frozenQuantity < 0) {
      throw new IllegalArgumentException("security balance must not be negative");
    }
  }
}
