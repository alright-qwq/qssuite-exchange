package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRequestPermissionTest {
  @Test
  void requiresTheCurrentAccountAndTheExactActionPermission() {
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest limit = ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), player, "diamond-usd", OrderSide.BUY, OrderType.LIMIT,
        new BigDecimal("100.00"), null, 1));
    ExchangeMenuRequest market = ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), player, "diamond-usd", OrderSide.SELL, OrderType.MARKET,
        null, new BigDecimal("90.00"), 1));
    ExchangeMenuRequest cancel = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), player, UUID.randomUUID());
    ExchangeMenuRequest deposit = ExchangeMenuRequest.transfer(new ExchangeMenuRequest.TransferDraft(
        UUID.randomUUID(), player, ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT,
        "USD", BigDecimal.ONE, 0, null));

    assertThat(ExchangeRequestPermission.allows(player, limit,
        Set.of("quickshop.exchange.use", "quickshop.exchange.order.limit")::contains)).isTrue();
    assertThat(ExchangeRequestPermission.allows(player, market,
        Set.of("quickshop.exchange.use", "quickshop.exchange.order.limit")::contains)).isFalse();
    assertThat(ExchangeRequestPermission.allows(player, cancel,
        Set.of("quickshop.exchange.use", "quickshop.exchange.order.cancel")::contains)).isTrue();
    assertThat(ExchangeRequestPermission.allows(player, deposit,
        Set.of("quickshop.exchange.use", "quickshop.exchange.deposit")::contains)).isTrue();
    assertThat(ExchangeRequestPermission.allows(UUID.randomUUID(), limit, ignored -> true)).isFalse();
  }

  @Test
  void rechecksTheRolloutPolicyAtFinalConfirmation() {
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest cancel = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), player, UUID.randomUUID());

    assertThat(ExchangeRequestPermission.allows(player, cancel,
        Set.of("quickshop.exchange.use", "quickshop.exchange.order.cancel")::contains,
        new RolloutPolicy(true, Set.of()))).isFalse();
    assertThat(ExchangeRequestPermission.allows(player, cancel,
        Set.of("quickshop.exchange.use", "quickshop.exchange.order.cancel")::contains,
        new RolloutPolicy(true, Set.of(player)))).isTrue();
  }
}
