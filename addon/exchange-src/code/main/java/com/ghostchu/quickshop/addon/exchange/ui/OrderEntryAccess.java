package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Pure access policy shared by GUI order-entry actions. */
final class OrderEntryAccess {
  private final RolloutPolicy rollout;

  OrderEntryAccess(RolloutPolicy rollout) {
    this.rollout = Objects.requireNonNull(rollout, "rollout");
  }

  Optional<String> denial(UUID playerId, MarketStatus status, OrderType type,
                          Predicate<String> permission) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(permission, "permission");
    if (!rollout.allows(playerId)) {
      return Optional.of("rollout-not-allowed");
    }
    if (!permission.test("quickshop.exchange.use")) {
      return Optional.of("permission-denied");
    }
    String dedicated = type == OrderType.MARKET
        ? "quickshop.exchange.order.market" : "quickshop.exchange.order.limit";
    if (!permission.test(dedicated)) {
      return Optional.of("permission-denied");
    }
    if (status != MarketStatus.OPEN) {
      return Optional.of("market-not-open");
    }
    return Optional.empty();
  }
}
