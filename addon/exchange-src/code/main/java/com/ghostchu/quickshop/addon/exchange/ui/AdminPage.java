package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Read-only landing page for independently permissioned administrator operations. */
final class AdminPage {
  private final ExchangeUiMessages messages;

  AdminPage(AddonMessageService messages) {
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (player == null) return;
    add(page, playerId, player, "quickshop.exchange.admin.market", "COMPASS",
        "ui-admin-market", "ui-admin-market-usage", 19);
    add(page, playerId, player, "quickshop.exchange.admin.orders", "PAPER",
        "ui-admin-orders", "ui-admin-orders-usage", 21);
    add(page, playerId, player, "quickshop.exchange.admin.recovery", "ANVIL",
        "ui-admin-recovery", "ui-admin-recovery-usage", 23);
    add(page, playerId, player, "quickshop.exchange.admin.audit", "BOOK",
        "ui-admin-audit", "ui-admin-audit-usage", 25);
    add(page, playerId, player, "quickshop.exchange.admin.stock", "PAPER",
        "ui-admin-stock", "ui-admin-stock-usage", 27);
  }

  private void add(PlayerInstancePage page, UUID playerId, Player player,
                   String permission, String material, String titleKey, String usageKey,
                   int slot) {
    if (!player.hasPermission(permission)) return;
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(messages.component(player, titleKey))
        .lore(java.util.List.of(messages.component(player, usageKey))))
        .withActions(new RunnableAction(click -> player.sendMessage(
            messages.component(player, usageKey))))
        .withSlot(slot).build());
  }
}
