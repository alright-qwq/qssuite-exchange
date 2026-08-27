package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays the first bounded page of a player's currently cancellable orders. */
final class MyOrdersPage {
  static final int PAGE_SIZE = 27;

  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;

  MyOrdersPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
               AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    views.subscribeMarketUpdates(playerId, update -> {
      if (contexts.isCurrent(playerId, opened) && player.isOnline()) {
        refresh(page, player, opened);
      }
    });
    refresh(page, player, opened);
  }

  private void refresh(PlayerInstancePage page, Player player, ExchangeMenuRequest opened) {
    UUID playerId = player.getUniqueId();
    int offset = Math.max(0, (opened == null ? 1 : opened.page()) - 1) * PAGE_SIZE;
    views.accountOrders(playerId, PAGE_SIZE + 1, offset).whenComplete((orders, failure) -> {
      if (!contexts.isCurrent(playerId, opened)) return;
      java.util.Map<String, MarketRow> quotes = new java.util.HashMap<>();
      List<CompletableFuture<Void>> loads = new java.util.ArrayList<>();
      for (ExchangeTransaction.PersistedOrder persisted : orders) {
        String marketId = persisted.order().marketId();
        if (quotes.containsKey(marketId)) continue;
        loads.add(views.marketRow(marketId).handle((row, ignored) -> {
          if (row != null) quotes.put(marketId, row);
          return null;
        }));
      }
      CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((ignored, ignoredFailure) -> {
        if (!contexts.isCurrent(playerId, opened)) return;
        if (player == null || !player.isOnline()) return;
        QuickShop.folia().getScheduler().runAtEntityLater(player,
            () -> {
              if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
                render(page, player, orders, quotes, failure);
              }
            }, 1L);
      });
    });
  }

  private void render(PlayerInstancePage page, Player player,
                      List<ExchangeTransaction.PersistedOrder> orders,
                      java.util.Map<String, MarketRow> quotes, Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    addMarketsNavigation(page, player);
    if (orders.isEmpty()) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
          .customName(messages.component(player, "ui-orders-empty"))).withSlot(22).build());
    }
    int slot = 9;
    for (ExchangeTransaction.PersistedOrder persisted
        : orders.subList(0, Math.min(orders.size(), PAGE_SIZE))) {
      if (slot >= 45) break;
      Order order = persisted.order();
      List<Component> lore = List.of(
          messages.component(player, "ui-order-status", order.status()),
          messages.component(player, "ui-order-remaining", order.remainingQuantity(),
              order.originalQuantity()),
          messages.component(player, "ui-order-price", order.limitPrice() == null
              ? order.slippageBoundary() : order.limitPrice()),
          messages.component(player, order.side() == OrderSide.BUY
              ? "ui-order-frozen-currency" : "ui-order-frozen-quantity",
              order.side() == OrderSide.BUY
                  ? persisted.reservedCurrency()
                  : persisted.reservedQuantity()),
          messages.component(player, "ui-order-time",
              messages.relativeTime(order.createdAt())));
      MarketRow quote = quotes.get(order.marketId());
      if (quote != null && quote.lastPrice() != null) {
        lore = new java.util.ArrayList<>(lore);
        lore.add(messages.component(player, "ui-order-current-price",
            quote.lastPrice().toPlainString()));
        java.math.BigDecimal boundary = order.limitPrice() == null
            ? order.slippageBoundary() : order.limitPrice();
        if (boundary != null && boundary.signum() > 0) {
          java.math.BigDecimal distance = quote.lastPrice().subtract(boundary)
              .divide(boundary, 4, java.math.RoundingMode.HALF_UP)
              .multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros();
          lore.add(messages.component(player, "ui-order-price-distance",
              distance.toPlainString() + "%"));
        }
      }
      boolean buying = order.side() == OrderSide.BUY;
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of(
          buying ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE", 1)
          .customName(messages.component(player, "ui-order-title", order.side(),
              views.marketDisplayName(order.marketId())))
          .lore(lore));
      icon.withActions(new RunnableAction(click -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()
            || !online.hasPermission("quickshop.exchange.use")
            || !online.hasPermission("quickshop.exchange.order.cancel")) {
          return;
        }
        contexts.put(playerId, ExchangeMenuRequest.cancel(UUID.randomUUID(), playerId, order.orderId()));
        MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.CANCEL_CONFIRM.page(),
            click.player());
      })).withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    int currentPage = opened == null ? 1 : opened.page();
    if (currentPage > 1) {
      addNavigation(page, player, 45, "ARROW", "ui-history-previous", currentPage - 1);
    }
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("CLOCK", 1)
        .customName(messages.component(player, "ui-history-page", currentPage))).withSlot(49).build());
    if (orders.size() > PAGE_SIZE) {
      addNavigation(page, player, 53, "ARROW", "ui-history-next", currentPage + 1);
    }
  }

  private void addMarketsNavigation(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("COMPASS", 1)
        .customName(messages.component(player, "ui-nav-markets")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.MARKETS.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
              click.player());
        })).withSlot(0).build());
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String key, int targetPage) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(messages.component(player, key)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuRequest request = ExchangeMenuRequest.page(
              ExchangeMenuPage.ORDERS.menuName(), targetPage);
          contexts.put(playerId, request);
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ORDERS.page(),
              click.player());
        })).withSlot(slot).build());
  }
}
