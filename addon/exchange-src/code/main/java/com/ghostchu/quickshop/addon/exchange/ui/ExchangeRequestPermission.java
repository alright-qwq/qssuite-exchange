package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Revalidates ownership and the exact action permission at GUI confirmation time. */
final class ExchangeRequestPermission {
  private ExchangeRequestPermission() {}

  static boolean allows(UUID playerId, ExchangeMenuRequest request,
                        Predicate<String> permission) {
    return allows(playerId, request, permission, RolloutPolicy.DISABLED);
  }

  static boolean allows(UUID playerId, ExchangeMenuRequest request,
                        Predicate<String> permission, RolloutPolicy rollout) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(permission, "permission");
    Objects.requireNonNull(rollout, "rollout");
    if (!playerId.equals(request.accountId()) || !rollout.allows(playerId)
        || !permission.test("quickshop.exchange.use")) {
      return false;
    }
    if (request.order() != null) {
      return permission.test(request.order().type() == OrderType.MARKET
          ? "quickshop.exchange.order.market" : "quickshop.exchange.order.limit");
    }
    if (request.orderId() != null) {
      return permission.test("quickshop.exchange.order.cancel");
    }
    if (request.transfer() != null) {
      return permission.test(switch (request.transfer().kind()) {
        case MONEY_DEPOSIT, ITEM_DEPOSIT -> "quickshop.exchange.deposit";
        case MONEY_WITHDRAWAL, ITEM_WITHDRAWAL -> "quickshop.exchange.withdraw";
      });
    }
    return false;
  }
}
