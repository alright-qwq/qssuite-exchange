package com.ghostchu.quickshop.addon.exchange.security;

/** Durable outcome of an idempotent security lifecycle mutation. */
public record SecurityMutationResult(
    String marketId, String symbol, String action, String status, String payload,
    boolean replayed) {}
