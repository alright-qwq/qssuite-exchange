package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeRequestSubmitter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRuntimeTest {
  @Test
  void acceptsWritesOnlyAfterRecoveryAndClosesDispatcherBeforeWriter() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true));

    assertThat(runtime.acceptingWrites()).isFalse();
    runtime.start();
    assertThat(runtime.acceptingWrites()).isTrue();

    runtime.close();

    assertThat(dispatcherClosed).isTrue();
    assertThat(writer.held()).isFalse();
    assertThat(writer.closedAfterDispatcher()).isTrue();
  }

  @Test
  void fencesNewWritesImmediatelyWhenTheWriterLockIsLost() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean marketsRecovering = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {},
        () -> marketsRecovering.set(true));

    runtime.start();
    writer.loseLock();

    assertThat(runtime.acceptingWrites()).isFalse();
    assertThat(marketsRecovering).isTrue();
  }

  @Test
  void completesRecoveryWhenFactoryAlreadyOwnsTheWriterLock() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean recovered = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> recovered.set(true), () -> {}, () -> {});
    writer.acquire();

    runtime.start();

    assertThat(recovered).isTrue();
    assertThat(runtime.acceptingWrites()).isTrue();
    runtime.close();
  }

  @Test
  void keepsStartupRecoveryInsideTheWriterFence() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean bookRecoveryFenced = new AtomicBoolean();
    AtomicBoolean transferRecoveryFenced = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer,
        () -> bookRecoveryFenced.set(writer.runningGuardedWork()),
        () -> transferRecoveryFenced.set(writer.runningGuardedWork()),
        () -> {});

    runtime.start();

    assertThat(bookRecoveryFenced).isTrue();
    assertThat(transferRecoveryFenced).isTrue();
    runtime.close();
  }

  @Test
  void flushesOperationalDataAfterDispatcherDrainAndBeforeWriterRelease() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    AtomicBoolean flushedAfterDispatcher = new AtomicBoolean();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true), () -> {},
        () -> flushedAfterDispatcher.set(dispatcherClosed.get()));

    runtime.start();
    runtime.close();

    assertThat(flushedAfterDispatcher).isTrue();
    assertThat(writer.closedAfterDispatcher()).isTrue();
  }

  @Test
  void keepsWriterHeldWhenOperationalDrainFails() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true), () -> {},
        () -> { throw new IllegalStateException("drain failed"); });
    runtime.start();

    assertThatThrownBy(runtime::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("drain failed");

    assertThat(runtime.acceptingWrites()).isFalse();
    assertThat(writer.held()).isTrue();
  }

  @Test
  void returnsWriteResultOnlyWhileTheWriterFenceIsHeld() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {});

    assertThat(runtime.callWhileWriting(() -> "not-run")).isEmpty();
    runtime.start();
    assertThat(runtime.callWhileWriting(() -> "committed")).contains("committed");

    writer.loseLock();
    assertThat(runtime.callWhileWriting(() -> "not-run")).isEmpty();
  }

  @Test
  void holdsWriterFenceUntilAnAsynchronousMutationCompletes() throws Exception {
    var database = Files.createTempFile("exchange-async-writer-", ".sqlite");
    LocalSingleWriterGuard first = new LocalSingleWriterGuard(database);
    LocalSingleWriterGuard second = new LocalSingleWriterGuard(database);
    ExchangeRuntime runtime = new ExchangeRuntime(first, () -> {}, () -> {}, () -> {});
    runtime.start();
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<String> mutation = new CompletableFuture<>();

    CompletableFuture<java.util.Optional<String>> fenced = runtime.callAsyncWhileWriting(() -> {
      started.countDown();
      return mutation;
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Thread closer = Thread.ofPlatform().start(() -> {
      try {
        runtime.close();
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    Thread.sleep(50L);

    assertThat(closer.isAlive()).isTrue();
    assertThatThrownBy(second::acquire).isInstanceOf(IllegalStateException.class);
    mutation.complete("completed");
    assertThat(fenced.join()).contains("completed");
    closer.join(2_000L);
    second.acquire();
    second.close();
  }

  @Test
  void rejectsGuiConfirmationWhenWriterIsUnavailableWithoutChangingRequestId() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});
    UUID requestId = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        requestId, UUID.randomUUID(), "diamond-usd", OrderSide.BUY, OrderType.LIMIT,
        new BigDecimal("100.00"), null, 1));

    ExchangeRequestSubmitter.SubmissionResult result =
        new RuntimeExchangeRequestSubmitter(runtime, Runnable::run).submit(request).join();

    assertThat(result.requestId()).isEqualTo(requestId);
    assertThat(result.outcome()).isEqualTo(ExchangeRequestSubmitter.Outcome.REJECTED);
    assertThat(result.reference()).isEqualTo("writer unavailable");
  }

  @Test
  void mapsRiskRejectionsToRejectedWithAReadableReason() {
    assertThat(RuntimeExchangeRequestSubmitter.rejectionReason(
        new IllegalStateException("RATE_LIMITED")))
        .isEqualTo(com.ghostchu.quickshop.addon.exchange.core.risk.OrderRiskService.RejectReason.RATE_LIMITED);
    assertThat(RuntimeExchangeRequestSubmitter.rejectionReason(
        new IllegalStateException("database locked")))
        .isNull();
  }

  @Test
  void mapsPreparedWithdrawalsToReviewRequiredWithInventoryHint() {
    UUID requestId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TransferRecord prepared = new TransferRecord(transferId, requestId, accountId,
        TransferType.ITEM_WITHDRAWAL, "diamond-usd", new BigDecimal("1"), TransferStatus.PREPARED,
        "marker", null, java.time.Instant.now(), java.time.Instant.now(), 0);

    ExchangeRequestSubmitter.SubmissionResult result =
        RuntimeExchangeRequestSubmitter.resultForTransfer(requestId, prepared);

    assertThat(result.outcome()).isEqualTo(ExchangeRequestSubmitter.Outcome.REVIEW_REQUIRED);
    assertThat(result.reference()).isEqualTo(transferId.toString());
    assertThat(result.reason()).isEqualTo("INVENTORY_FULL");
  }

  @Test
  void mapsFailedAndCompletedTransfersToRejectedAndAccepted() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    var now = java.time.Instant.now();
    TransferRecord failed = new TransferRecord(UUID.randomUUID(), requestId, accountId,
        TransferType.MONEY_DEPOSIT, "vault", new BigDecimal("1"), TransferStatus.FAILED,
        "economy withdrawal rejected", "economy withdrawal rejected", now, now, 0);
    TransferRecord completed = new TransferRecord(UUID.randomUUID(), requestId, accountId,
        TransferType.MONEY_DEPOSIT, "vault", new BigDecimal("1"), TransferStatus.COMPLETED,
        "ok", null, now, now, 0);

    assertThat(RuntimeExchangeRequestSubmitter.resultForTransfer(requestId, failed).outcome())
        .isEqualTo(ExchangeRequestSubmitter.Outcome.REJECTED);
    assertThat(RuntimeExchangeRequestSubmitter.resultForTransfer(requestId, completed).outcome())
        .isEqualTo(ExchangeRequestSubmitter.Outcome.ACCEPTED);
  }

  @Test
  void rejectsNonConfirmableRequestInsteadOfInventingRequestId() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});

    assertThatThrownBy(() -> new RuntimeExchangeRequestSubmitter(runtime, Runnable::run)
        .submit(ExchangeMenuRequest.page("markets")).join())
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("request is not confirmable");
  }

  @Test
  void closingTheGuiSubmitterRejectsLateConfirmations() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});
    RuntimeExchangeRequestSubmitter submitter = new RuntimeExchangeRequestSubmitter(runtime);
    ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    submitter.close();

    assertThatThrownBy(() -> submitter.submit(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  private static final class TrackingGuard implements SingleWriterGuard {
    private final AtomicBoolean dispatcherClosed;
    private boolean held;
    private boolean closedAfterDispatcher;
    private boolean runningGuardedWork;
    private Runnable onLockLost = () -> {};

    private TrackingGuard(AtomicBoolean dispatcherClosed) {
      this.dispatcherClosed = dispatcherClosed;
    }

    @Override
    public void acquire() {
      if (held) {
        throw new IllegalStateException("exchange writer lock is already held");
      }
      held = true;
    }

    @Override
    public boolean held() {
      return held;
    }

    @Override
    public boolean runWhileHeld(GuardedWork work) throws Exception {
      if (!held) {
        return false;
      }
      runningGuardedWork = true;
      try {
        work.run();
        return true;
      } finally {
        runningGuardedWork = false;
      }
    }

    @Override
    public void close() {
      closedAfterDispatcher = dispatcherClosed.get();
      held = false;
    }

    @Override
    public void onLockLost(Runnable action) {
      onLockLost = action;
    }

    private void loseLock() {
      held = false;
      onLockLost.run();
    }

    private boolean closedAfterDispatcher() {
      return closedAfterDispatcher;
    }

    private boolean runningGuardedWork() {
      return runningGuardedWork;
    }
  }
}
