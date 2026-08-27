package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Bridges player lifecycle events to exchange-only menu state. */
public final class ExchangeMenuListener implements Listener {
  private final ExchangeMenuService menus;

  public ExchangeMenuListener(ExchangeMenuService menus) {
    this.menus = Objects.requireNonNull(menus, "menus");
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    menus.inventoryClosed(event.getPlayer().getUniqueId(), event.getView().getTitle());
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    menus.playerClosed(event.getPlayer().getUniqueId());
  }
}
