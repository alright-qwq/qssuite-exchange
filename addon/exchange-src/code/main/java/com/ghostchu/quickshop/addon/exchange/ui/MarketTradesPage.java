package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Paginated market-wide trade history. */
final class MarketTradesPage {
  static final int PAGE_SIZE = 27;

  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;

  MarketTradesPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
                   AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null || !"market-trades".equals(opened.menuName())
        || opened.marketId() == null) {
      return;
    }
    int offset = Math.max(0, opened.page() - 1) * PAGE_SIZE;
    java.util.concurrent.CompletableFuture<MarketRow> header =
        views.marketRow(opened.marketId());
    views.marketTradePage(opened.marketId(), PAGE_SIZE + 1, offset)
        .thenCombine(header.handle((row, rowFailure) -> row), (trades, row) -> new PageData(trades, row))
        .whenComplete((data, failure) -> {
      if (!contexts.isCurrent(playerId, opened)) return;
      Player online = Bukkit.getPlayer(playerId);
      if (online == null || !online.isOnline()) return;
      ExchangeSchedulers.folia().getScheduler().runAtEntityLater(online,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, opened, online::isOnline)) {
              render(page, online, opened, data == null ? List.of() : data.trades(),
                  failure, data == null ? null : data.row());
            }
          }, 1L);
        });
  }

  private void render(PlayerInstancePage page, Player player, ExchangeMenuRequest opened,
                      List<ExchangeRepository.MarketTradeRow> trades, Throwable failure,
                      MarketRow header) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    String title = messages.text(player, "ui-market-trades-page-title",
        views.marketDisplayName(opened.marketId()));
    java.util.ArrayList<Component> headerLore = new java.util.ArrayList<>();
    if (header != null) {
      if (header.lastPrice() != null) {
        headerLore.add(messages.component(player, "ui-market-trades-header-last",
            header.lastPrice().toPlainString()));
      }
      String bid = header.bestBid() == null ? "-" : header.bestBid().toPlainString();
      String ask = header.bestAsk() == null ? "-" : header.bestAsk().toPlainString();
      headerLore.add(messages.component(player, "ui-market-trades-header-bid-ask", bid, ask));
      if (header.change24h() != null) {
        headerLore.add(messages.component(player, "ui-market-trades-header-change",
            header.change24h().multiply(java.math.BigDecimal.valueOf(100))
                .stripTrailingZeros().toPlainString()));
      }
    }
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("BOOK", 1)
        .customName(Component.text(title)).lore(headerLore)).withSlot(4).build());
    addNavigation(page, player, 0, "COMPASS", "ui-nav-markets", ExchangeMenuPage.MARKETS);
    addNavigation(page, player, 2, "PAPER", "ui-market-back-detail", ExchangeMenuPage.MARKET_DETAIL);
    if (trades.isEmpty()) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("PAPER", 1)
          .customName(messages.component(player, "ui-market-recent-empty"))).withSlot(22).build());
    }
    int slot = 9;
    for (ExchangeRepository.MarketTradeRow trade : trades.subList(0, Math.min(trades.size(), PAGE_SIZE))) {
      if (slot >= 45) break;
      boolean buy = trade.takerSide() == OrderSide.BUY;
      List<Component> lore = List.of(
          messages.component(player, "ui-market-recent-trade-id", trade.matchSequence()),
          messages.component(player, "ui-market-recent-trade-quantity", trade.quantity()),
          messages.component(player, "ui-market-recent-trade-notional",
              trade.price().multiply(java.math.BigDecimal.valueOf(trade.quantity()))
                  .stripTrailingZeros().toPlainString()),
          messages.component(player, "ui-market-recent-trade-time",
              messages.relativeTime(trade.executedAt())));
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(
          buy ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE", 1)
          .customName(messages.component(player, "ui-market-recent-trade-title",
              buy ? messages.text(player, "ui-market-recent-active-buy")
                  : messages.text(player, "ui-market-recent-active-sell"),
              trade.price().toPlainString()))
          .lore(lore)).withSlot(slot++).build());
    }
    if (opened.page() > 1) {
      addPageNavigation(page, player, 45, "ARROW", "ui-history-previous",
          opened.marketId(), opened.page() - 1);
    }
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("CLOCK", 1)
        .customName(messages.component(player, "ui-history-page", opened.page()))).withSlot(49).build());
    if (trades.size() > PAGE_SIZE) {
      addPageNavigation(page, player, 53, "ARROW", "ui-history-next",
          opened.marketId(), opened.page() + 1);
    }
  }

  private void addPageNavigation(PlayerInstancePage page, Player player, int slot,
                                 String material, String key, String marketId, int targetPage) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(messages.component(player, key)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuRequest request = ExchangeMenuRequest.marketTrades(marketId, targetPage);
          contexts.put(playerId, request);
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_TRADES.page(),
              click.player());
        })).withSlot(slot).build());
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String key, ExchangeMenuPage target) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1)
        .customName(messages.component(player, key)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuRequest request = target == ExchangeMenuPage.MARKET_DETAIL
              ? contexts.get(playerId).map(ExchangeMenuRequest::marketId)
                  .map(marketId -> ExchangeMenuRequest.market(marketId)).orElse(null)
              : ExchangeMenuRequest.page(target.menuName());
          if (request != null) {
            contexts.put(playerId, request);
            MenuManager.instance().open(ExchangeMenu.NAME, target.page(), click.player());
          }
        })).withSlot(slot).build());
  }

  private record PageData(List<ExchangeRepository.MarketTradeRow> trades, MarketRow row) {}
}
