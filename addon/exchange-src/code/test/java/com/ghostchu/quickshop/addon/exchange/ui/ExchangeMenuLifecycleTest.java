package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMenuLifecycleTest {
  @Test
  void clearsOnlyTheContextForAnExchangeInventoryCloseOrPlayerQuit() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    AtomicInteger cleanups = new AtomicInteger();
    ExchangeMenuLifecycle lifecycle = new ExchangeMenuLifecycle(
        contexts, ignored -> cleanups.incrementAndGet());
    UUID player = UUID.randomUUID();
    contexts.put(player, ExchangeMenuRequest.page("markets"));

    lifecycle.inventoryClosed(player, "Chest");
    assertThat(contexts.get(player)).isPresent();
    assertThat(cleanups).hasValue(0);

    lifecycle.inventoryClosed(player, ExchangeMenu.TITLE);
    assertThat(contexts.get(player)).isEmpty();
    assertThat(cleanups).hasValue(0);

    contexts.put(player, ExchangeMenuRequest.page("markets"));
    lifecycle.playerQuit(player);
    assertThat(contexts.get(player)).isEmpty();
    assertThat(cleanups).hasValue(1);
  }
}
