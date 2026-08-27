package com.ghostchu.quickshop.addon.exchange.transfer;

public final class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("requestId already belongs to a different transfer");
  }
}
