package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Creates retryable chat handlers for limit and protected market orders. */
final class OrderEntryPrompt {
  private final ExchangeMenuContextStore contexts;
  private final Supplier<UUID> requestIds;

  OrderEntryPrompt(ExchangeMenuContextStore contexts, Supplier<UUID> requestIds) {
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
  }

  Function<String, Boolean> limit(UUID accountId, String marketId, OrderSide side,
                                  Runnable feedback) {
    Objects.requireNonNull(feedback, "feedback");
    return raw -> store(() -> OrderEntryInput.limit(
        requestIds.get(), accountId, marketId, side, raw), feedback);
  }

  Function<String, Boolean> market(UUID accountId, String marketId, OrderSide side,
                                   Runnable feedback) {
    Objects.requireNonNull(feedback, "feedback");
    return raw -> store(() -> OrderEntryInput.market(
        requestIds.get(), accountId, marketId, side, raw), feedback);
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
