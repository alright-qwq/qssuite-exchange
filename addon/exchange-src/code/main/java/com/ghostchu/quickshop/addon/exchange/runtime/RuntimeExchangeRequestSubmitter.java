package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.risk.OrderRiskService;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeRequestSubmitter;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Submits GUI-held requests through the runtime's writer fence. */
public final class RuntimeExchangeRequestSubmitter implements ExchangeRequestSubmitter, AutoCloseable {
  private final ExchangeRuntime runtime;
  private final Executor executor;
  private final AutoCloseable executorOwner;
  private final AtomicBoolean closed = new AtomicBoolean();

  public RuntimeExchangeRequestSubmitter(ExchangeRuntime runtime) {
    this(runtime, new DrainingExecutor("qs-exchange-submit-", java.time.Duration.ofSeconds(30)), true);
  }

  RuntimeExchangeRequestSubmitter(ExchangeRuntime runtime, Executor executor) {
    this(runtime, executor, false);
  }

  private RuntimeExchangeRequestSubmitter(ExchangeRuntime runtime, Executor executor,
                                          boolean ownsExecutor) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.executorOwner = ownsExecutor ? (AutoCloseable) executor : () -> {};
  }

  @Override
  public CompletableFuture<SubmissionResult> submit(ExchangeMenuRequest request) {
    Objects.requireNonNull(request, "request");
    if (closed.get()) {
      throw new IllegalStateException("exchange request submitter is closed");
    }
    if (request.requestId() == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("request is not confirmable"));
    }
    if (request.order() != null) {
      return CompletableFuture.supplyAsync(() -> order(request), executor);
    }
    if (request.orderId() != null) {
      return CompletableFuture.supplyAsync(() -> cancel(request), executor);
    }
    if (request.transfer() != null) {
      return transfer(request);
    }
    return CompletableFuture.completedFuture(new SubmissionResult(
        request.requestId(), Outcome.REJECTED, "request is not confirmable", null));
  }

  private SubmissionResult order(ExchangeMenuRequest request) {
    try {
      Optional<OrderReceipt> receipt = runtime.callWhileWriting(
          () -> runtime.actions().submitOrder(request.order()));
      if (receipt.isEmpty()) return unavailable(request);
      return new SubmissionResult(request.requestId(), Outcome.ACCEPTED,
          receipt.orElseThrow().orderId().toString(), null);
    } catch (Exception failure) {
      OrderRiskService.RejectReason rejection = rejectionReason(failure);
      if (rejection != null) {
        return new SubmissionResult(request.requestId(), Outcome.REJECTED,
            rejection.name(), null);
      }
      return new SubmissionResult(request.requestId(), Outcome.FAILED,
          failure.getClass().getSimpleName(), failure.getMessage());
    }
  }

  private SubmissionResult cancel(ExchangeMenuRequest request) {
    try {
      Optional<OrderReceipt> receipt = runtime.callWhileWriting(
          () -> runtime.actions().cancel(request.accountId(), request.requestId(), request.orderId()));
      if (receipt.isEmpty()) return unavailable(request);
      return new SubmissionResult(request.requestId(), Outcome.ACCEPTED,
          receipt.orElseThrow().orderId().toString(), null);
    } catch (IllegalArgumentException failure) {
      return new SubmissionResult(request.requestId(), Outcome.REJECTED,
          failure.getMessage(), failure.getMessage());
    } catch (Exception failure) {
      return new SubmissionResult(request.requestId(), Outcome.FAILED,
          failure.getClass().getSimpleName(), failure.getMessage());
    }
  }

  private CompletableFuture<SubmissionResult> transfer(ExchangeMenuRequest request) {
    return runtime.callAsyncWhileWriting(
        () -> runtime.actions().submitTransfer(request.transfer()), executor)
        .handle((completed, failure) -> {
          if (failure != null) {
            return new SubmissionResult(request.requestId(), Outcome.FAILED,
                failure.getClass().getSimpleName(), failure.getMessage());
          }
          if (completed.isEmpty()) {
            return unavailable(request);
          }
          return resultForTransfer(request.requestId(), completed.orElseThrow());
        });
  }

  static SubmissionResult resultForTransfer(UUID requestId, TransferRecord transfer) {
    if (transfer.status() == TransferStatus.PREPARED) {
      // The withdrawal was prepared but could not be delivered yet (e.g. the player's
      // inventory has no space); the transfer stays ready to claim. Surface this as a
      // review-style outcome so the player is told to free inventory instead of seeing
      // a plain "accepted" that implies the item arrived.
      return new SubmissionResult(requestId, Outcome.REVIEW_REQUIRED,
          transfer.transferId().toString(), "INVENTORY_FULL");
    }
    Outcome outcome = transfer.status() == TransferStatus.REVIEW_REQUIRED
        ? Outcome.REVIEW_REQUIRED : transfer.status() == TransferStatus.FAILED
        ? Outcome.REJECTED : Outcome.ACCEPTED;
    return new SubmissionResult(requestId, outcome,
        transfer.transferId().toString(), transfer.failureReason());
  }

  private static SubmissionResult unavailable(ExchangeMenuRequest request) {
    return new SubmissionResult(request.requestId(), Outcome.REJECTED,
        "writer unavailable", null);
  }

  static OrderRiskService.RejectReason rejectionReason(Throwable failure) {
    if (!(failure instanceof IllegalStateException rejected)) {
      return null;
    }
    String message = rejected.getMessage();
    if (message == null) {
      return null;
    }
    for (OrderRiskService.RejectReason reason : OrderRiskService.RejectReason.values()) {
      if (reason.name().equals(message)) {
        return reason;
      }
    }
    return null;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      executorOwner.close();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("failed to close exchange request submitter", failure);
    }
  }
}
