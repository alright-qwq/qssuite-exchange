package com.ghostchu.quickshop.addon.exchange.service;

@FunctionalInterface
public interface RecoveryHandler {
  /** Explicit opt-out for tests or embeddings that intentionally provide no recovery service. */
  RecoveryHandler NO_OP = (marketId, failure) -> {};

  void recover(String marketId, Throwable failure);
}
