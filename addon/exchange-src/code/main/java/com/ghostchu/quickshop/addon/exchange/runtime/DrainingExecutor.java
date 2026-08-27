package com.ghostchu.quickshop.addon.exchange.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Single background executor that rejects new work and drains accepted work before shutdown. */
public final class DrainingExecutor implements Executor, AutoCloseable {
  private final ExecutorService executor;
  private final Duration closeTimeout;
  private boolean closed;

  public DrainingExecutor(String threadNamePrefix, Duration closeTimeout) {
    if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
      throw new IllegalArgumentException("threadNamePrefix is required");
    }
    this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isZero() || closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must be positive");
    }
    this.executor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon(true).name(threadNamePrefix, 0).factory());
  }

  @Override
  public synchronized void execute(Runnable command) {
    Objects.requireNonNull(command, "command");
    if (closed) {
      throw new IllegalStateException("executor is closed");
    }
    executor.execute(command);
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      executor.shutdown();
    }
    try {
      if (!executor.awaitTermination(closeTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
        executor.shutdownNow();
        throw new IllegalStateException("timed out draining executor during close");
      }
    } catch (InterruptedException failure) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while draining executor", failure);
    }
  }
}
