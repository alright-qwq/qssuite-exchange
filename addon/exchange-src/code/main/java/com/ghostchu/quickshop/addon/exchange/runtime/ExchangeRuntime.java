package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeActionService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates writer ownership, recovery and orderly dispatcher shutdown. */
public final class ExchangeRuntime implements AutoCloseable {
  private final SingleWriterGuard writer;
  private final CheckedRunnable recoverBooks;
  private final CheckedRunnable recoverTransfers;
  private final AutoCloseable dispatcher;
  private final CheckedRunnable onLockLost;
  private final CheckedRunnable afterDispatcherClosed;
  private final ExchangeViewService views;
  private final AdminExchangeService administration;
  private final ExchangeActionService actions;
  private final AtomicBoolean acceptingWrites = new AtomicBoolean();

  public ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                         TransferRecoveryService transfers, MarketDispatcher dispatcher) {
    this(writer, recoverBooks, transfers::recoverAllMoneyTransfers, dispatcher, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, afterDispatcherClosed, null);
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed,
                  ExchangeViewService views) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, afterDispatcherClosed,
        views, null, null);
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed,
                  ExchangeViewService views, AdminExchangeService administration) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, afterDispatcherClosed,
        views, administration, null);
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed,
                  ExchangeViewService views, AdminExchangeService administration,
                  ExchangeActionService actions) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.recoverBooks = Objects.requireNonNull(recoverBooks, "recoverBooks");
    this.recoverTransfers = Objects.requireNonNull(recoverTransfers, "recoverTransfers");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.onLockLost = Objects.requireNonNull(onLockLost, "onLockLost");
    this.afterDispatcherClosed = Objects.requireNonNull(afterDispatcherClosed,
        "afterDispatcherClosed");
    this.views = views;
    this.administration = administration;
    this.actions = actions;
    writer.onLockLost(this::fenceAfterLockLoss);
  }

  public void start() throws Exception {
    if (!writer.held()) {
      writer.acquire();
    }
    try {
      boolean recovered = writer.runWhileHeld(() -> {
        recoverBooks.run();
        recoverTransfers.run();
      });
      if (!recovered) {
        throw new IllegalStateException("exchange writer lock was lost during startup recovery");
      }
      acceptingWrites.set(true);
    } catch (Exception failure) {
      writer.close();
      throw failure;
    }
  }

  public boolean acceptingWrites() {
    return acceptingWrites.get() && writer.held();
  }

  public ExchangeViewService views() {
    if (views == null) {
      throw new IllegalStateException("runtime views are not configured");
    }
    return views;
  }

  public AdminExchangeService administration() {
    if (administration == null) {
      throw new IllegalStateException("runtime administration is not configured");
    }
    return administration;
  }

  public ExchangeActionService actions() {
    if (actions == null) {
      throw new IllegalStateException("runtime actions are not configured");
    }
    return actions;
  }

  /** Executes a command mutation while writer ownership remains fenced. */
  public boolean runWhileWriting(CheckedRunnable work) throws Exception {
    Objects.requireNonNull(work, "work");
    if (!acceptingWrites()) {
      return false;
    }
    AtomicBoolean completed = new AtomicBoolean();
    boolean held = writer.runWhileHeld(() -> {
      if (!acceptingWrites()) {
        return;
      }
      work.run();
      completed.set(true);
    });
    return held && completed.get();
  }

  /** Executes a value-producing mutation under the same writer fence. */
  public <T> Optional<T> callWhileWriting(CheckedSupplier<T> work) throws Exception {
    Objects.requireNonNull(work, "work");
    if (!acceptingWrites()) {
      return Optional.empty();
    }
    AtomicReference<T> result = new AtomicReference<>();
    AtomicBoolean completed = new AtomicBoolean();
    boolean held = writer.runWhileHeld(() -> {
      if (!acceptingWrites()) {
        return;
      }
      result.set(work.get());
      completed.set(true);
    });
    return held && completed.get() ? Optional.ofNullable(result.get()) : Optional.empty();
  }

  /** Keeps writer ownership fenced until the asynchronous mutation reaches a terminal result. */
  public <T> CompletableFuture<Optional<T>> callAsyncWhileWriting(
      CheckedSupplier<CompletableFuture<T>> work) {
    return callAsyncWhileWriting(work, java.util.concurrent.ForkJoinPool.commonPool());
  }

  /** Uses the caller-owned executor so its lifecycle can be drained before runtime shutdown. */
  public <T> CompletableFuture<Optional<T>> callAsyncWhileWriting(
      CheckedSupplier<CompletableFuture<T>> work, Executor executor) {
    Objects.requireNonNull(work, "work");
    Objects.requireNonNull(executor, "executor");
    return CompletableFuture.supplyAsync(() -> {
      try {
        return callWhileWriting(() -> work.get().join());
      } catch (Exception failure) {
        throw new CompletionException(failure);
      }
    }, executor);
  }

  private void fenceAfterLockLoss() {
    acceptingWrites.set(false);
    try {
      onLockLost.run();
    } catch (Exception ignored) {
      // The write fence is already active; retry and operator recovery happen on restart.
    }
  }

  @Override
  public void close() throws Exception {
    acceptingWrites.set(false);
    // Every shutdown stage must run even when an earlier stage fails: a stuck dispatcher or a
    // failed final drain must not keep the writer lock held forever, or the only recovery left
    // would be a full server restart. The first failure is rethrown (with the rest suppressed)
    // so callers can still surface and log the underlying problem.
    Exception firstFailure = null;
    try {
      dispatcher.close();
    } catch (Exception failure) {
      firstFailure = failure;
    }
    try {
      afterDispatcherClosed.run();
    } catch (Exception failure) {
      firstFailure = accumulate(firstFailure, failure);
    }
    try {
      writer.close();
    } catch (Exception failure) {
      firstFailure = accumulate(firstFailure, failure);
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  private static Exception accumulate(Exception first, Exception next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  @FunctionalInterface
  public interface CheckedRunnable {
    void run() throws Exception;
  }

  @FunctionalInterface
  public interface CheckedSupplier<T> {
    T get() throws Exception;
  }
}
