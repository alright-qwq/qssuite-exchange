package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, append-only operational alert emitted by detectors and persisted for admin review. */
public record AuditAlert(UUID alertId, String marketId, UUID accountId, String type,
                         String severity, String payload, Instant createdAt,
                         Instant acknowledgedAt) {
  public AuditAlert {
    Objects.requireNonNull(alertId, "alertId");
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    requireText(type, "type");
    requireText(severity, "severity");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
