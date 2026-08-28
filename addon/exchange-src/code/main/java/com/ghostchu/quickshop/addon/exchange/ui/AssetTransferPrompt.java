package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Creates retryable chat handlers for asset transfer amounts. */
final class AssetTransferPrompt {
  private final ExchangeMenuContextStore contexts;
  private final Supplier<UUID> requestIds;

  AssetTransferPrompt(ExchangeMenuContextStore contexts, Supplier<UUID> requestIds) {
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
  }

  Function<String, Boolean> currency(UUID accountId, ExchangeMenuRequest.TransferKind kind,
                                     String currencyId, Runnable feedback) {
    Objects.requireNonNull(feedback, "feedback");
    return raw -> store(() -> AssetTransferInput.currency(
        requestIds.get(), accountId, kind, currencyId, raw), feedback);
  }

  Function<String, Boolean> item(UUID accountId, ExchangeMenuRequest.TransferKind kind,
                                 String marketId, Runnable feedback) {
    Objects.requireNonNull(feedback, "feedback");
    return raw -> store(() -> AssetTransferInput.item(
        requestIds.get(), accountId, kind, marketId, raw), feedback);
  }

  private boolean store(Supplier<ExchangeMenuRequest> request, Runnable feedback) {
    try {
      ExchangeMenuRequest parsed = request.get();
      contexts.put(parsed.accountId(), parsed);
      return true;
    } catch (IllegalArgumentException invalid) {
      feedback.run();
      return false;
    }
  }
}
