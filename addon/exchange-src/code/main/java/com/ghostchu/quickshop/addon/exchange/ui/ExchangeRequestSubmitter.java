package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.concurrent.CompletableFuture;

/** Asynchronous boundary between TNML callbacks and fenced exchange mutations. */
@FunctionalInterface
public interface ExchangeRequestSubmitter {
  CompletableFuture<SubmissionResult> submit(ExchangeMenuRequest request);

  record SubmissionResult(java.util.UUID requestId, Outcome outcome, String reference, String reason) {
    public SubmissionResult {
      if (requestId == null || outcome == null || reference == null || reference.isBlank()) {
        throw new IllegalArgumentException("submission result is required");
      }
      reason = reason == null || reason.isBlank() ? null : reason;
    }

    /** Convenience for callers that only have a stable identifier and no readable reason. */
    public SubmissionResult(java.util.UUID requestId, Outcome outcome, String reference) {
      this(requestId, outcome, reference, null);
    }
  }

  enum Outcome { ACCEPTED, REVIEW_REQUIRED, REJECTED, FAILED }
}
