package com.ghostchu.quickshop.addon.exchange.repository;

import java.time.Instant;
import java.util.UUID;

/** Immutable ledger event describing one virtual security mutation. */
public record SecurityLedgerEntry(
    UUID eventId, String idempotencyKey, String marketId, UUID ownerId, String eventType,
    long signedQuantity, long availableDelta, long frozenDelta, String referenceType,
    String referenceId, UUID actorId, String reason, Instant createdAt) {}
