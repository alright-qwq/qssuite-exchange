package com.ghostchu.quickshop.addon.exchange.core.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDispatcherTest {
  @Test
  void returnsTheStoredResultForADuplicateAccountRequestPair() {
    AtomicInteger calls = new AtomicInteger();
    RequestResultStore store = resultStore();
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ExchangeCommand command = command("diamond-usd", accountId, requestId);

    try (MarketDispatcher dispatcher = new MarketDispatcher(store,
        submitted -> new CommandResult(submitted.requestId(), "accepted-" + calls.incrementAndGet()))) {
      CommandResult first = dispatcher.submit(command).join();
      CommandResult duplicate = dispatcher.submit(command).join();
      CommandResult otherAccount = dispatcher.submit(command("diamond-usd", UUID.randomUUID(), requestId)).join();

      assertThat(first).isEqualTo(duplicate);
      assertThat(otherAccount).isNotEqualTo(first);
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void concurrentDuplicatesAcrossMarketsRunProcessorOnceAndShareTheFirstResult()
      throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch duplicateProcessed = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      calls.incrementAndGet();
      if (submitted.marketId().equals("diamond-usd")) {
        firstStarted.countDown();
        await(releaseFirst);
      } else {
        duplicateProcessed.countDown();
      }
      return new CommandResult(submitted.requestId(), submitted.marketId());
    });

    try {
      CompletableFuture<CommandResult> first =
          dispatcher.submit(command("diamond-usd", accountId, requestId));
      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<CommandResult> duplicate =
          dispatcher.submit(command("gold-usd", accountId, requestId));
      boolean duplicateRanBeforeFirstFinished = duplicateProcessed.await(200, TimeUnit.MILLISECONDS);
      releaseFirst.countDown();

      CommandResult firstResult = first.join();
      CommandResult duplicateResult = duplicate.join();
      assertThat(duplicateRanBeforeFirstFinished).isFalse();
      assertThat(calls).hasValue(1);
      assertThat(firstResult.outcome()).isEqualTo("diamond-usd");
      assertThat(duplicateResult).isSameAs(firstResult);
    } finally {
      releaseFirst.countDown();
      dispatcher.close();
    }
  }

  @Test
  void serializesCommandsForOneMarketOnOneThread() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch permitFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    Set<String> processorThreads = ConcurrentHashMap.newKeySet();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      processorThreads.add(Thread.currentThread().getName());
      int nowActive = active.incrementAndGet();
      maximumActive.accumulateAndGet(nowActive, Math::max);
      if (submitted.operation().equals("first")) {
        firstStarted.countDown();
        await(permitFirst);
      }
      active.decrementAndGet();
      return new CommandResult(submitted.requestId(), submitted.operation());
    });

    try {
      CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<CommandResult> second = dispatcher.submit(command("diamond-usd", "second"));

      assertThat(second).isNotDone();
      permitFirst.countDown();

      assertThat(first.join().outcome()).isEqualTo("first");
      assertThat(second.join().outcome()).isEqualTo("second");
      assertThat(maximumActive).hasValue(1);
      assertThat(processorThreads).hasSize(1);
    } finally {
      permitFirst.countDown();
      dispatcher.close();
    }
  }

  @Test
  void closeDrainsAcceptedMarketCommandsBeforeReturning() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch permitFirst = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      calls.incrementAndGet();
      if (submitted.operation().equals("first")) {
        firstStarted.countDown();
        await(permitFirst);
      }
      return new CommandResult(submitted.requestId(), submitted.operation());
    });

    CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<CommandResult> second = dispatcher.submit(command("diamond-usd", "second"));
    CompletableFuture<Void> closing = CompletableFuture.runAsync(dispatcher::close);

    permitFirst.countDown();

    assertThat(closing).succeedsWithin(Duration.ofSeconds(2));
    assertThat(first.join().outcome()).isEqualTo("first");
    assertThat(second.join().outcome()).isEqualTo("second");
    assertThat(calls).hasValue(2);
  }

  @Test
  void submitDuringCloseIsOutsideTheAcceptedWorkBoundary() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      if (submitted.operation().equals("blocking")) {
        firstStarted.countDown();
        await(releaseFirst);
      }
      return new CommandResult(submitted.requestId(), submitted.operation());
    });

    CompletableFuture<CommandResult> first =
        dispatcher.submit(command("diamond-usd", UUID.randomUUID(), UUID.randomUUID(), "blocking"));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> closing = CompletableFuture.runAsync(dispatcher::close);

    try {
      assertThat(awaitSubmissionRejection(dispatcher, "diamond-usd")).isTrue();
      assertThat(closing).isNotDone();
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));

      CompletableFuture<CommandResult> late =
          dispatcher.submit(command("gold-usd", UUID.randomUUID(), UUID.randomUUID(), "late"));

      assertThat(late.isCompletedExceptionally()).isTrue();
      assertThatThrownBy(late::join)
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(RejectedExecutionException.class);
    } finally {
      releaseFirst.countDown();
    }

    assertThat(closing).succeedsWithin(Duration.ofSeconds(2));
    assertThat(first.join().outcome()).isEqualTo("blocking");
  }

  @Test
  void closeUsesOneDeadlineAndReportsProcessorsThatIgnoreInterruption() throws InterruptedException {
    CountDownLatch processorsStarted = new CountDownLatch(2);
    CountDownLatch releaseProcessors = new CountDownLatch(1);
    CountDownLatch processorsFinished = new CountDownLatch(2);
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      processorsStarted.countDown();
      try {
        awaitIgnoringInterrupts(releaseProcessors);
        return new CommandResult(submitted.requestId(), submitted.operation());
      } finally {
        processorsFinished.countDown();
      }
    }, Duration.ofMillis(400));

    CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
    CompletableFuture<CommandResult> second = dispatcher.submit(command("gold-usd", "second"));
    assertThat(processorsStarted.await(1, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> closing = CompletableFuture.runAsync(dispatcher::close);

    try {
      assertThatThrownBy(() -> closing.get(650, TimeUnit.MILLISECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(first.isCompletedExceptionally()).isTrue();
      assertThat(second.isCompletedExceptionally()).isTrue();
    } finally {
      releaseProcessors.countDown();
      assertThat(processorsFinished.await(1, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void closeTimeoutCompletesRemovedQueuedTasksExceptionally() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch firstFinished = new CountDownLatch(1);
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      if (submitted.operation().equals("first")) {
        firstStarted.countDown();
        try {
          awaitIgnoringInterrupts(releaseFirst);
        } finally {
          firstFinished.countDown();
        }
      }
      return new CommandResult(submitted.requestId(), submitted.operation());
    }, Duration.ofMillis(100));

    CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<CommandResult> queued = dispatcher.submit(command("diamond-usd", "queued"));

    try {
      assertThatThrownBy(dispatcher::close).isInstanceOf(IllegalStateException.class);

      assertThat(first.isCompletedExceptionally()).isTrue();
      assertThat(queued.isCompletedExceptionally()).isTrue();
    } finally {
      releaseFirst.countDown();
      assertThat(firstFinished.await(1, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void closeTimeoutFailsFutureBeforeInterruptingProcessor() throws InterruptedException {
    CountDownLatch processorStarted = new CountDownLatch(1);
    CountDownLatch releaseProcessor = new CountDownLatch(1);
    CountDownLatch interruptionObserved = new CountDownLatch(1);
    AtomicReference<CompletableFuture<CommandResult>> submittedFuture = new AtomicReference<>();
    AtomicBoolean futureFailedBeforeInterruption = new AtomicBoolean();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      processorStarted.countDown();
      try {
        releaseProcessor.await();
      } catch (InterruptedException interrupted) {
        futureFailedBeforeInterruption.set(submittedFuture.get().isCompletedExceptionally());
        interruptionObserved.countDown();
        awaitIgnoringInterrupts(releaseProcessor);
      }
      return new CommandResult(submitted.requestId(), submitted.operation());
    }, Duration.ofMillis(100));

    CompletableFuture<CommandResult> result = dispatcher.submit(command("diamond-usd", "blocking"));
    submittedFuture.set(result);
    assertThat(processorStarted.await(1, TimeUnit.SECONDS)).isTrue();

    try {
      assertThatThrownBy(dispatcher::close).isInstanceOf(IllegalStateException.class);
      assertThat(interruptionObserved.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(futureFailedBeforeInterruption).isTrue();
    } finally {
      releaseProcessor.countDown();
    }
  }

  @Test
  void repeatedClosePreservesTimeoutFailureWhileProcessorRemainsActive() throws InterruptedException {
    CountDownLatch processorStarted = new CountDownLatch(1);
    CountDownLatch releaseProcessor = new CountDownLatch(1);
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      processorStarted.countDown();
      awaitIgnoringInterrupts(releaseProcessor);
      return new CommandResult(submitted.requestId(), submitted.operation());
    }, Duration.ofMillis(100));

    dispatcher.submit(command("diamond-usd", "blocking"));
    assertThat(processorStarted.await(1, TimeUnit.SECONDS)).isTrue();

    try {
      assertThatThrownBy(dispatcher::close).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(dispatcher::close).isInstanceOf(IllegalStateException.class);
    } finally {
      releaseProcessor.countDown();
    }
  }

  private static ExchangeCommand command(String marketId, String operation) {
    return command(marketId, UUID.randomUUID(), UUID.randomUUID(), operation);
  }

  private static ExchangeCommand command(String marketId, UUID accountId, UUID requestId) {
    return command(marketId, accountId, requestId, "PLACE");
  }

  private static ExchangeCommand command(String marketId, UUID accountId, UUID requestId, String operation) {
    return new ExchangeCommand(marketId, accountId, requestId, operation);
  }

  private static RequestResultStore resultStore() {
    Map<RequestKey, CommandResult> results = new ConcurrentHashMap<>();
    return new RequestResultStore() {
      @Override
      public Optional<CommandResult> find(UUID accountId, UUID requestId) {
        return Optional.ofNullable(results.get(new RequestKey(accountId, requestId)));
      }

      @Override
      public CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result) {
        return results.computeIfAbsent(new RequestKey(accountId, requestId), ignored -> result);
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test permit");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static boolean awaitSubmissionRejection(MarketDispatcher dispatcher, String marketId)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      try {
        CompletableFuture<CommandResult> submission = dispatcher.submit(command(marketId, "probe"));
        if (submission.isCompletedExceptionally()) {
          try {
            submission.join();
          } catch (CompletionException exception) {
            if (exception.getCause() instanceof RejectedExecutionException) {
              return true;
            }
          }
        }
      } catch (RejectedExecutionException rejected) {
        return true;
      }
      Thread.sleep(1);
    }
    return false;
  }

  private static void awaitIgnoringInterrupts(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record RequestKey(UUID accountId, UUID requestId) {}
}
