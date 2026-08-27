package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Objects;
import java.util.UUID;

/** Removes player-owned exchange state when the viewer is no longer usable. */
public final class ExchangeMenuLifecycle {
  private final ExchangeMenuContextStore contexts;
  private final java.util.function.Consumer<UUID> quitCleanup;

  public ExchangeMenuLifecycle(ExchangeMenuContextStore contexts) {
    this(contexts, ignored -> {});
  }

  public ExchangeMenuLifecycle(ExchangeMenuContextStore contexts,
                               java.util.function.Consumer<UUID> quitCleanup) {
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.quitCleanup = Objects.requireNonNull(quitCleanup, "quitCleanup");
  }

  public void inventoryClosed(UUID playerId, String title) {
    if (ExchangeMenu.TITLE.equals(title)) {
      contexts.remove(Objects.requireNonNull(playerId, "playerId"));
    }
  }

  public void playerQuit(UUID playerId) {
    UUID required = Objects.requireNonNull(playerId, "playerId");
    contexts.remove(required);
    quitCleanup.accept(required);
  }
}
