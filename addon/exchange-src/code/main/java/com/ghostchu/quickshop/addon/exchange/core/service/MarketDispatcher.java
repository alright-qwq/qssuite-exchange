package com.ghostchu.quickshop.addon.exchange.core.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MarketDispatcher implements AutoCloseable {
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

  private final RequestResultStore requestResults;
  private final MarketCommandProcessor processor;
  private final long closeTimeoutNanos;
  private final Object lifecycleLock = new Object();
  private final Map<String, ExecutorService> executors = new HashMap<>();
  private final Map<RequestKey, CompletableFuture<CommandResult>> inFlight = new ConcurrentHashMap<>();
  private final Set<DispatchTask> activeTasks = ConcurrentHashMap.newKeySet();
  private boolean closed;
  private IllegalStateException closeFailure;

  public MarketDispatcher(RequestResultStore requestResults, MarketCommandProcessor processor) {
    this(requestResults, processor, DEFAULT_CLOSE_TIMEOUT);
  }

  MarketDispatcher(RequestResultStore requestResults, MarketCommandProcessor processor,
                   Duration closeTimeout) {
    this.requestResults = Objects.requireNonNull(requestResults, "requestResults");
    this.processor = Objects.requireNonNull(processor, "processor");
    Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isNegative() || closeTimeout.isZero()) {
      throw new IllegalArgumentException("closeTimeout must be positive");
    }
    this.closeTimeoutNanos = closeTimeout.toNanos();
  }

  public CompletableFuture<CommandResult> submit(ExchangeCommand command) {
    Objects.requireNonNull(command, "command");
    synchronized (lifecycleLock) {
      if (closed) {
        return CompletableFuture.failedFuture(
            new RejectedExecutionException("Market dispatcher is closed"));
      }

      RequestKey requestKey = new RequestKey(command.accountId(), command.requestId());
      CompletableFuture<CommandResult> existing = inFlight.get(requestKey);
      if (existing != null) {
        return existing;
      }

      ExecutorService executor = executors.computeIfAbsent(command.marketId(), this::newExecutor);
      CompletableFuture<CommandResult> result = new CompletableFuture<>();
      DispatchTask task = new DispatchTask(command, requestKey, result);
      inFlight.put(requestKey, result);
      activeTasks.add(task);
      try {
        executor.execute(task);
      } catch (RejectedExecutionException rejected) {
        inFlight.remove(requestKey, result);
        activeTasks.remove(task);
        result.completeExceptionally(rejected);
      }
      return result;
    }
  }

  @Override
  public synchronized void close() {
    List<ExecutorService> acceptedExecutors;
    synchronized (lifecycleLock) {
      if (closed) {
        if (closeFailure != null) {
          throw closeFailure;
        }
        return;
      }
      closed = true;
      acceptedExecutors = new ArrayList<>(executors.values());
      acceptedExecutors.forEach(ExecutorService::shutdown);
    }

    long deadline = System.nanoTime() + closeTimeoutNanos;
    InterruptedException interruption = null;
    boolean timedOut = false;
    for (ExecutorService executor : acceptedExecutors) {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        timedOut = true;
        break;
      }
      try {
        if (!executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
          timedOut = true;
          break;
        }
      } catch (InterruptedException exception) {
        interruption = exception;
        break;
      }
    }

    if (timedOut || interruption != null) {
      CancellationException cancellation = new CancellationException(
          "Market dispatcher processor did not terminate before the close deadline");
      synchronized (lifecycleLock) {
        List<DispatchTask> timedOutTasks = List.copyOf(activeTasks);
        closeFailure = new IllegalStateException(
            "Market dispatcher closed with " + timedOutTasks.size() + " processor task(s) still active",
            interruption);
        timedOutTasks.forEach(task -> task.cancelAfterTimeout(cancellation));
      }
    }

    for (ExecutorService executor : acceptedExecutors) {
      if (!executor.isTerminated()) {
        cancelQueuedTasks(executor.shutdownNow());
      }
    }

    if (closeFailure != null) {
      if (interruption != null) {
        Thread.currentThread().interrupt();
      }
      throw closeFailure;
    }
  }

  private ExecutorService newExecutor(String market) {
    return Executors.newSingleThreadExecutor(Thread.ofPlatform()
        .name("qs-exchange-" + market + "-", 0).factory());
  }

  private void cancelQueuedTasks(List<Runnable> queuedTasks) {
    for (Runnable queuedTask : queuedTasks) {
      if (queuedTask instanceof DispatchTask dispatchTask) {
        dispatchTask.cancelBeforeExecution();
      }
    }
  }

  private final class DispatchTask implements Runnable {
    private final ExchangeCommand command;
    private final RequestKey requestKey;
    private final CompletableFuture<CommandResult> result;

    private DispatchTask(ExchangeCommand command, RequestKey requestKey,
                         CompletableFuture<CommandResult> result) {
      this.command = command;
      this.requestKey = requestKey;
      this.result = result;
    }

    @Override
    public void run() {
      try {
        CommandResult commandResult = requestResults.find(command.accountId(), command.requestId())
            .orElseGet(() -> {
              CommandResult processed = processor.process(command);
              return requestResults.putIfAbsent(command.accountId(), command.requestId(), processed);
            });
        result.complete(commandResult);
      } catch (Throwable failure) {
        result.completeExceptionally(failure);
      } finally {
        inFlight.remove(requestKey, result);
        activeTasks.remove(this);
      }
    }

    private void cancelBeforeExecution() {
      result.completeExceptionally(
          new CancellationException("Market dispatcher closed before command execution"));
      inFlight.remove(requestKey, result);
      activeTasks.remove(this);
    }

    private void cancelAfterTimeout(CancellationException cancellation) {
      result.completeExceptionally(cancellation);
    }
  }

  private record RequestKey(UUID accountId, UUID requestId) {}
}
