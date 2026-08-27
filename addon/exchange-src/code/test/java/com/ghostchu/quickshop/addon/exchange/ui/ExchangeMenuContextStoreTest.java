package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMenuContextStoreTest {
  @Test
  void retainsTheSameRequestUntilPlayerClosesTheMenu() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    store.put(player, request);

    assertThat(store.get(player)).containsSame(request);
    assertThat(store.remove(player)).containsSame(request);
    assertThat(store.get(player)).isEmpty();
  }

  @Test
  void onlyTheCurrentRequestCanBeClaimedOnce() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest first = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), player, UUID.randomUUID());
    ExchangeMenuRequest replacement = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), player, UUID.randomUUID());
    store.put(player, first);

    assertThat(store.isCurrent(player, first)).isTrue();
    assertThat(store.claim(player, first)).isTrue();
    assertThat(store.claim(player, first)).isFalse();

    store.put(player, replacement);
    assertThat(store.isCurrent(player, first)).isFalse();
    assertThat(store.claim(player, first)).isFalse();
    assertThat(store.claim(player, replacement)).isTrue();
  }

  @Test
  void exposesAStableSnapshotOfTrackedPlayersForPluginShutdown() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    store.put(first, ExchangeMenuRequest.page("markets"));
    store.put(second, ExchangeMenuRequest.page("assets"));

    java.util.Set<UUID> snapshot = store.playerIds();
    store.remove(first);

    assertThat(snapshot).containsExactlyInAnyOrder(first, second);
    assertThat(store.playerIds()).containsExactly(second);
  }

  @Test
  void closeDropsAllPendingContexts() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID player = UUID.randomUUID();
    store.put(player, ExchangeMenuRequest.page("markets"));

    store.close();

    assertThat(store.get(player)).isEmpty();
  }

  @Test
  void notifiesTheOwnerWhenNavigationLeavesTheLiveMarketViews() {
    AtomicReference<UUID> unsubscribed = new AtomicReference<>();
    ExchangeMenuContextStore store = new ExchangeMenuContextStore(unsubscribed::set);
    UUID player = UUID.randomUUID();

    store.put(player, ExchangeMenuRequest.market("diamond-usd"));
    store.put(player, ExchangeMenuRequest.page("assets"));

    assertThat(unsubscribed).hasValue(player);
  }
}
