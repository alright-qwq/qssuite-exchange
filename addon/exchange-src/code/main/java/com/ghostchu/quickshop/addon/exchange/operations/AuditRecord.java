package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only record of an operator action. */
public record AuditRecord(UUID auditId, UUID actorId, String action, String targetId,
                          String reason, String beforeState, String afterState,
                          Instant createdAt) {
  public AuditRecord {
    Objects.requireNonNull(auditId, "auditId");
    Objects.requireNonNull(actorId, "actorId");
    requireText(action, "action");
    requireText(targetId, "targetId");
    requireText(reason, "reason");
    requireText(beforeState, "beforeState");
    requireText(afterState, "afterState");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
