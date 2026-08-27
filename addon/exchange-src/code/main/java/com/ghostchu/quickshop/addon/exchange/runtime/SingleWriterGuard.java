package com.ghostchu.quickshop.addon.exchange.runtime;

public interface SingleWriterGuard extends AutoCloseable {
  void acquire() throws Exception;

  boolean held();

  /**
   * Runs work while the writer ownership remains fenced from a concurrent lock-loss callback.
   * Returns false when the guard was already unavailable.
   */
  default boolean runWhileHeld(GuardedWork work) throws Exception {
    if (!held()) {
      return false;
    }
    work.run();
    return true;
  }

  /** Called exactly once when a held distributed lock can no longer be trusted. */
  default void onLockLost(Runnable action) {
    // Local guards cannot lose a held operating-system lock while this process remains alive.
  }

  @Override
  void close() throws Exception;

  @FunctionalInterface
  interface GuardedWork {
    void run() throws Exception;
  }
}
