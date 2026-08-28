package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Shows bounded account-filtered trade, transfer and liability-ledger history. */
final class HistoryPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final AddonMessageService messages;

  HistoryPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
              AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.messages = messages;
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null) return;
    int offset = HistoryPageSnapshot.offset(opened.page());
    HistoryPageSnapshot.combine(
        views.accountTrades(playerId, HistoryPageSnapshot.FETCH_SIZE, offset),
        views.accountTransfers(playerId, HistoryPageSnapshot.FETCH_SIZE, offset),
        views.accountLedger(playerId, HistoryPageSnapshot.FETCH_SIZE, offset))
        .whenComplete((snapshot, failure) -> {
          if (!contexts.isCurrent(playerId, opened)) return;
          Player player = Bukkit.getPlayer(playerId);
          if (player == null || !player.isOnline()) return;
          ExchangeSchedulers.folia().getScheduler().runAtEntityLater(player,
              () -> {
                if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
                  render(page, player, snapshot, failure);
                }
              }, 1L);
        });
  }

  private void render(PlayerInstancePage page, Player player, HistoryPageSnapshot snapshot,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null || snapshot == null || snapshot.failure() != null) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("BARRIER", 1)
          .customName(text(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    int slot = 9;
    if (snapshot.trades().isEmpty() && snapshot.transfers().isEmpty() && snapshot.ledger().isEmpty()) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("PAPER", 1)
          .customName(text(player, "ui-history-empty"))).withSlot(22).build());
    }
    for (var row : snapshot.trades()) {
      if (slot >= 21) break;
      Trade trade = row.trade();
      boolean bought = trade.buyerAccountId().equals(playerId);
      String direction = string(player, bought
          ? "ui-history-trade-buy" : "ui-history-trade-sell");
      java.math.BigDecimal totalFee = trade.makerFee().add(trade.takerFee());
      java.math.BigDecimal myFee = row.feeFor(playerId);
      List<Component> lore = new java.util.ArrayList<>(List.of(
          text(player, "ui-history-trade-id", trade.matchSequence()),
          text(player, "ui-history-trade-quantity", trade.quantity()),
          text(player, "ui-history-trade-notional",
              trade.price().multiply(java.math.BigDecimal.valueOf(trade.quantity()))
                  .stripTrailingZeros().toPlainString()),
          text(player, "ui-history-trade-total-fee", totalFee.toPlainString()),
          text(player, "ui-history-trade-my-fee", myFee == null ? "-" : myFee.toPlainString()),
          text(player, "ui-history-created-at", relativeTime(trade.executedAt()))));
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(
          bought ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE", 1)
          .customName(text(player, "ui-history-trade-title",
              direction + " " + trade.marketId(), trade.price().toPlainString()))
          .lore(lore)).withSlot(slot++).build());
    }
    slot = 21;
    for (TransferRecord transfer : snapshot.transfers()) {
      if (slot >= 33) break;
      String reason = transfer.failureReason() == null ? "" : " " + transfer.failureReason();
      List<Component> lore = List.of(
          text(player, "ui-history-transfer-asset", transfer.assetId()),
          text(player, "ui-history-transfer-amount", transfer.amount().toPlainString()),
          text(player, "ui-history-transfer-status", transfer.status() + reason),
          text(player, "ui-history-created-at", relativeTime(transfer.updatedAt())));
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("HOPPER", 1)
          .customName(text(player, "ui-history-transfer-title", transfer.type())).lore(lore))
          .withSlot(slot++).build());
    }
    slot = 33;
    for (AccountLedgerEntry entry : snapshot.ledger()) {
      if (slot >= 45) break;
      List<Component> lore = List.of(
          text(player, "ui-history-ledger-asset", entry.assetId()),
          text(player, "ui-history-ledger-amount", entry.amount().toPlainString()),
          text(player, "ui-history-ledger-reference", entry.referenceId()),
          text(player, "ui-history-created-at", relativeTime(entry.createdAt())));
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("WRITABLE_BOOK", 1)
          .customName(text(player, "ui-history-ledger-title", entry.journalType())).lore(lore))
          .withSlot(slot++).build());
    }
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null || !"history".equals(opened.menuName())) return;
    if (opened.page() > 1) {
      addNavigation(page, player, 45, "ARROW", "ui-history-previous", opened.page() - 1);
    }
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("CLOCK", 1)
        .customName(text(player, "ui-history-page", opened.page()))).withSlot(49).build());
    if (snapshot.hasNext()) {
      addNavigation(page, player, 53, "ARROW", "ui-history-next", opened.page() + 1);
    }
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String key, int targetPage) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(text(player, key)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuRequest request = ExchangeMenuRequest.page("history", targetPage);
          contexts.put(playerId, request);
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.HISTORY.page(),
              click.player());
        })).withSlot(slot).build());
  }

  private Component text(Player player, String key, Object... arguments) {
    if (messages == null) return Component.text(key);
    Locale locale = player.locale();
    return Component.text(messages.message(key, locale, arguments));
  }

  private String string(Player player, String key, Object... arguments) {
    if (messages == null) return key;
    Locale locale = player.locale();
    return messages.message(key, locale, arguments);
  }

  private String relativeTime(java.time.Instant at) {
    return messages == null ? String.valueOf(at)
        : new ExchangeUiMessages(messages).relativeTime(at);
  }
}
