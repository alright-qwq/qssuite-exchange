package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-only ledger projection scoped to one player account. */
public record AccountLedgerEntry(
    UUID entryId, String journalType, UUID referenceId, String assetId,
    BigDecimal amount, Instant createdAt) {
  public AccountLedgerEntry {
    Objects.requireNonNull(entryId, "entryId");
    if (journalType == null || journalType.isBlank()) {
      throw new IllegalArgumentException("journalType is required");
    }
    Objects.requireNonNull(referenceId, "referenceId");
    if (assetId == null || assetId.isBlank()) {
      throw new IllegalArgumentException("assetId is required");
    }
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
