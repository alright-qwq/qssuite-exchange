package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEntryAccessTest {
  @Test
  void requiresRolloutBasePermissionDedicatedPermissionAndOpenMarket() {
    UUID playerId = UUID.randomUUID();
    OrderEntryAccess access = new OrderEntryAccess(
        new RolloutPolicy(true, Set.of(playerId)));

    assertThat(access.denial(playerId, MarketStatus.OPEN, OrderType.LIMIT,
        permission -> Set.of("quickshop.exchange.use", "quickshop.exchange.order.limit")
            .contains(permission))).isEmpty();

    assertThat(access.denial(UUID.randomUUID(), MarketStatus.OPEN, OrderType.LIMIT,
        permission -> true)).contains("rollout-not-allowed");
    assertThat(access.denial(playerId, MarketStatus.OPEN, OrderType.LIMIT,
        permission -> !permission.equals("quickshop.exchange.use")))
        .contains("permission-denied");
    assertThat(access.denial(playerId, MarketStatus.OPEN, OrderType.MARKET,
        permission -> !permission.equals("quickshop.exchange.order.market")))
        .contains("permission-denied");
    assertThat(access.denial(playerId, MarketStatus.PAUSED, OrderType.LIMIT,
        permission -> true)).contains("market-not-open");
  }
}
