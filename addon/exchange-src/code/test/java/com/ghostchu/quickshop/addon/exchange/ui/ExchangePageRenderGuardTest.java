package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangePageRenderGuardTest {
  @Test
  void rejectsAQueuedRenderAfterThePlayerChangesPage() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    UUID playerId = UUID.randomUUID();
    ExchangeMenuRequest opened = ExchangeMenuRequest.market("diamond-usd");
    AtomicBoolean online = new AtomicBoolean(true);
    contexts.put(playerId, opened);
    contexts.put(playerId, ExchangeMenuRequest.page("assets"));

    assertThat(ExchangePageRenderGuard.permits(contexts, playerId, opened, online::get)).isFalse();
  }

  @Test
  void rejectsAQueuedRenderAfterThePlayerDisconnects() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    UUID playerId = UUID.randomUUID();
    ExchangeMenuRequest opened = ExchangeMenuRequest.page("markets");
    AtomicBoolean online = new AtomicBoolean(false);
    contexts.put(playerId, opened);

    assertThat(ExchangePageRenderGuard.permits(contexts, playerId, opened, online::get)).isFalse();
  }
}
