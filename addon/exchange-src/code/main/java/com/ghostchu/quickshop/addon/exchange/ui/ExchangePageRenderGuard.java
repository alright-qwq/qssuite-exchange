package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Prevents delayed entity-thread renders from overwriting a newer exchange page. */
final class ExchangePageRenderGuard {
  private ExchangePageRenderGuard() {}

  static boolean permits(ExchangeMenuContextStore contexts, UUID playerId,
                         ExchangeMenuRequest expected, BooleanSupplier online) {
    return Objects.requireNonNull(contexts, "contexts").isCurrent(
        Objects.requireNonNull(playerId, "playerId"),
        Objects.requireNonNull(expected, "expected"))
        && Objects.requireNonNull(online, "online").getAsBoolean();
  }
}
