package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;

public interface CommandActor {
  UUID accountId();

  boolean hasPermission(String permission);

  void message(String key, Object... arguments);

  void openMenu(String menuName, int page);

  /** Returns a command result to the player from their platform-owned execution context. */
  default void executeAtOwner(Runnable action) {
    action.run();
  }

  /** Opens a page while retaining all typed state needed for a later submission. */
  default void openMenu(ExchangeMenuRequest request) {
    openMenu(request.menuName(), request.page());
  }

  /** Reports a command-level failure that must not propagate to the platform command pipeline. */
  default void commandFailed() {}

  /** Requests an administrator-triggered configuration reload. */
  default void reloadRequested() {}
}
