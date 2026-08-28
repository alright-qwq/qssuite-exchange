package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.entity.Player;

/**
 * Shared top navigation for every exchange page so players always find the same
 * markets/assets/orders/history buttons in the same slots.
 */
final class MenuNavigation {
  private final ExchangeMenuContextStore contexts;

  MenuNavigation(ExchangeMenuContextStore contexts) {
    this.contexts = contexts;
  }

  void addHeader(PlayerInstancePage page, Player player, ExchangeUiMessages messages) {
    add(page, player, 0, "COMPASS", "ui-nav-markets", ExchangeMenuPage.MARKETS, messages);
    add(page, player, 1, "CHEST", "ui-nav-assets", ExchangeMenuPage.ASSETS, messages);
    add(page, player, 2, "WRITABLE_BOOK", "ui-nav-orders", ExchangeMenuPage.ORDERS, messages);
    add(page, player, 5, "CLOCK", "ui-nav-history", ExchangeMenuPage.HISTORY, messages);
  }

  private void add(PlayerInstancePage page, Player player, int slot, String material,
                   String title, ExchangeMenuPage target, ExchangeUiMessages messages) {
    java.util.UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(messages.component(player, title)))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(target.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, target.page(), click.player());
        })).withSlot(slot).build());
  }
}
