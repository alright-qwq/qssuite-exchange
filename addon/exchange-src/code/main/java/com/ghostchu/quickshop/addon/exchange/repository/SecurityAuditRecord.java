package com.ghostchu.quickshop.addon.exchange.repository;

import java.time.Instant;
import java.util.UUID;

/** Immutable administrator action record for virtual security lifecycle operations. */
public record SecurityAuditRecord(
    UUID auditId, String requestId, String marketId, String action, UUID actorId,
    String payload, String outcome, Instant createdAt) {}
