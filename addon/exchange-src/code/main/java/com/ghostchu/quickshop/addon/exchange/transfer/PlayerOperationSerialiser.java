package com.ghostchu.quickshop.addon.exchange.transfer;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Strips per-player serialisation onto a fixed number of striped single-thread executors. */
public final class PlayerOperationSerialiser implements AutoCloseable {
  private static final int STRIPES = 32;
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(30);

  private final Executor[] stripes;
  private final java.util.List<com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor> drains;
  private final Duration closeTimeout;

  public PlayerOperationSerialiser() {
    this(DEFAULT_CLOSE_TIMEOUT);
  }

  public PlayerOperationSerialiser(Duration closeTimeout) {
    this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isZero() || closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must be positive");
    }
    drains = new java.util.ArrayList<>(STRIPES);
    stripes = new Executor[STRIPES];
    for (int index = 0; index < STRIPES; index++) {
      com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor drain =
          new com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor(
              "qs-exchange-account-" + index + "-", closeTimeout);
      drains.add(drain);
      stripes[index] = drain;
    }
  }

  public <T> CompletableFuture<T> submit(UUID playerId, Supplier<T> operation) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operation, "operation");
    int stripe = Math.floorMod(playerId.hashCode(), STRIPES);
    Executor executor = stripes[stripe];
    return CompletableFuture.supplyAsync(operation, executor);
  }

  @Override
  public void close() {
    // Every stripe must be shut down even when an earlier stripe times out: a stuck transfer on
    // one account must never leave the remaining 31 stripes running against a closing runtime.
    // The first failure is rethrown (with the rest suppressed) so callers can still surface it.
    Exception firstFailure = null;
    for (com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor drain : drains) {
      try {
        drain.close();
      } catch (Exception failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure != null) {
      if (firstFailure instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(
          "failed to drain player operation serialiser", firstFailure);
    }
  }
}
