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

/** Landing page for independently permissioned administrator operations. */
final class AdminPage {
  private final ExchangeUiMessages messages;
  private final AdminAction admin;

  AdminPage(AddonMessageService messages) {
    this(messages, AdminAction.none());
  }

  AdminPage(AddonMessageService messages, AdminAction admin) {
    this.messages = new ExchangeUiMessages(messages);
    this.admin = admin == null ? AdminAction.none() : admin;
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (player == null) return;
    add(page, playerId, player, "quickshop.exchange.admin.market", "COMPASS",
        "ui-admin-market", "ui-admin-market-usage", null, 19);
    add(page, playerId, player, "quickshop.exchange.admin.orders", "PAPER",
        "ui-admin-orders", "ui-admin-orders-usage", null, 21);
    add(page, playerId, player, "quickshop.exchange.admin.recovery", "ANVIL",
        "ui-admin-recovery", "ui-admin-recovery-usage",
        new String[] {"transfer", "review", "list"}, 23);
    add(page, playerId, player, "quickshop.exchange.admin.audit", "BOOK",
        "ui-admin-audit", "ui-admin-audit-usage",
        new String[] {"audit", "status"}, 25);
    add(page, playerId, player, "quickshop.exchange.admin.stock", "PAPER",
        "ui-admin-stock", "ui-admin-stock-usage", null, 27);
  }

  private void add(PlayerInstancePage page, UUID playerId, Player player,
                   String permission, String material, String titleKey, String usageKey,
                   String[] actionArgs, int slot) {
    if (!player.hasPermission(permission)) return;
    String usageText = messages.text(player, usageKey);
    net.kyori.adventure.text.Component usage = messages.component(player, usageKey)
        .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(
            usageText.startsWith("/qse ") ? usageText : "/qse " + usageText));
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(messages.component(player, titleKey))
        .lore(java.util.List.of(usage,
            messages.component(player, actionArgs == null
                ? "ui-admin-click-suggest" : "ui-admin-click-to-run"),
            messages.component(player, "ui-admin-click-usage"))))
        .withActions(
            new RunnableAction(click -> {
              if (actionArgs == null) {
                player.sendMessage(usage);
              } else {
                admin.execute(player, actionArgs);
              }
            }),
            new RunnableAction(click -> player.sendMessage(usage),
                net.tnemc.menu.core.icon.action.ActionType.RIGHT_CLICK))
        .withSlot(slot).build());
  }
}
