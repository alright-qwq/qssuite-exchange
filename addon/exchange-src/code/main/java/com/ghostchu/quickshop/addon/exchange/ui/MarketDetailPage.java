package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.menu.shared.GuiChatInputManager;
import java.util.List;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays a selected quote and exposes permission-gated limit and protected market entry. */
final class MarketDetailPage {
  private static final java.util.List<Duration> TIMEFRAMES = List.of(
      Duration.ofMinutes(9), Duration.ofMinutes(135), Duration.ofHours(9),
      Duration.ofHours(36));
  private static final java.util.List<String> TIMEFRAME_KEYS = List.of(
      "ui-trend-timeframe-1m", "ui-trend-timeframe-15m", "ui-trend-timeframe-1h",
      "ui-trend-timeframe-4h");

  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final OrderEntryPrompt prompts;
  private final OrderEntryAccess access;
  private final ExchangeUiMessages messages;
  private final MarketDashboardPresenter presenter = new MarketDashboardPresenter();
  private final java.util.Map<UUID, Duration> timeframes = new java.util.concurrent.ConcurrentHashMap<>();

  MarketDetailPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
                   RolloutPolicy rollout, AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.prompts = new OrderEntryPrompt(contexts, UUID::randomUUID);
    this.access = new OrderEntryAccess(rollout);
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest request = contexts.get(playerId).orElse(null);
    if (request == null || request.marketId() == null) {
      renderFailure(page, player, playerId, "ui-market-not-selected");
      return;
    }
    views.subscribeMarketUpdates(playerId, update -> {
      if (update.marketIds().contains(request.marketId()) && contexts.isCurrent(playerId, request)
          && player.isOnline()) {
        refresh(page, player, request);
      }
    });
    refresh(page, player, request);
  }

  private void refresh(PlayerInstancePage page, Player player, ExchangeMenuRequest request) {
    UUID playerId = player.getUniqueId();
    Duration window = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    views.marketDashboard(request.marketId(), window)
        .thenCombine(views.accountAssets(playerId).handle((assets, ignored) ->
            assets == null ? List.<AccountAssetBalance>of() : assets),
            (dashboard, assets) -> new PageData(dashboard, assets, List.of()))
        .thenCombine(views.accountOrders(playerId, 100, 0).handle((orders, ignored) ->
            orders == null ? List.<com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder>of()
                : orders), (data, orders) -> new PageData(data.dashboard(), data.assets(), orders))
        .whenComplete((data, failure) -> {
          if (!ExchangePageRenderGuard.permits(contexts, playerId, request, player::isOnline)) return;
          QuickShop.folia().getScheduler().runAtEntityLater(player,
              () -> {
                if (ExchangePageRenderGuard.permits(contexts, playerId, request, player::isOnline)) {
                  render(page, player, data == null ? null : data.dashboard(),
                      data == null ? List.of() : data.assets(),
                      data == null ? List.of() : data.orders(), failure);
                }
              }, 1L);
        });
  }

  private record PageData(MarketDashboardSnapshot dashboard, List<AccountAssetBalance> assets,
                          List<com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder> orders) {}

  private void render(PlayerInstancePage page, Player player, MarketDashboardSnapshot dashboard,
                      List<AccountAssetBalance> assets,
                      List<com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder> orders,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null || dashboard == null) {
      renderFailure(page, player, playerId, "ui-data-unavailable");
      return;
    }
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    MarketRow row = dashboard.market();
    String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
    String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
    String spread = dashboard.spread() == null ? "-" : dashboard.spread().toPlainString();
    String spreadPercent = percent(dashboard.spreadPercent());
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>(List.of(
        messages.component(player, "ui-market-last", row.lastPrice() == null ? "-"
            : row.lastPrice().toPlainString()),
        messages.component(player, "ui-market-bid", bid),
        messages.component(player, "ui-market-ask", ask),
        messages.component(player, "ui-market-spread", spread, spreadPercent),
        messages.component(player, "ui-market-change-percent", percent(row.change24h()))
            .color(changeColor(row.change24h())),
        messages.component(player, "ui-market-notional", notional(dashboard)),
        messages.component(player, "ui-market-volatility", percent(row.volatility24h())),
        messages.component(player, "ui-market-high-low",
            row.high24h() == null ? "-" : row.high24h().toPlainString(),
            row.low24h() == null ? "-" : row.low24h().toPlainString()),
        messages.component(player, "ui-market-volume", row.volume24h()),
        messages.component(player, "ui-market-status", row.status().name())));
    if (row.assetType() != null) {
      lore.add(messages.component(player, "ui-market-asset-type", row.assetType()));
    }
    if (row.symbol() != null) {
      lore.add(messages.component(player, "ui-market-symbol", row.symbol()));
    }
    if (row.totalSupply() != null) {
      lore.add(messages.component(player, "ui-market-total-supply", row.totalSupply()));
    }
    if (row.issuedSupply() != null) {
      lore.add(messages.component(player, "ui-market-issued-supply", row.issuedSupply(),
          row.totalSupply()));
      if ("VIRTUAL_SECURITY".equals(row.assetType()) && row.lastPrice() != null) {
        java.math.BigDecimal floatCap = row.lastPrice().multiply(
            java.math.BigDecimal.valueOf(row.issuedSupply()));
        lore.add(messages.component(player, "ui-market-float-cap",
            floatCap.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
      }
    }
    if (row.securityStatus() != null) {
      lore.add(messages.component(player, "ui-market-security-status", row.securityStatus()));
    }
    addPlayerBalances(lore, player, row, assets);
    addOpenOrderCount(lore, player, row, orders);
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BOOK", 1)
        .customName(Component.text(row.displayName())).lore(lore)).withSlot(4).build());
    addNavigation(page, player, 0, "COMPASS", "ui-nav-markets", ExchangeMenuPage.MARKETS);
    addNavigation(page, player, 1, "CHEST", "ui-nav-assets", ExchangeMenuPage.ASSETS);
    addNavigation(page, player, 2, "WRITABLE_BOOK", "ui-nav-orders", ExchangeMenuPage.ORDERS);
    addTimeframeControl(page, player,
        contexts.get(playerId).orElse(null));
    addTradeSummary(page, player, dashboard);
    Duration window = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    MarketDashboardPresenter.DashboardRows rows = presenter.present(dashboard, window);
    addExecutableDepthSummary(page, player, rows);
    renderDepth(page, player, rows, row);
    renderCandles(page, player, rows);
    renderRecentTrades(page, player, dashboard);
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.LIMIT, "LIME_CONCRETE", 29,
        "ui-order-limit-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.LIMIT, "RED_CONCRETE", 33,
        "ui-order-limit-sell", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.MARKET, "GOLD_BLOCK", 38,
        "ui-order-market-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.MARKET, "ORANGE_CONCRETE", 42,
        "ui-order-market-sell", ActionType.LEFT_CLICK);
  }

  private void addTimeframeControl(PlayerInstancePage page, Player player,
                                   ExchangeMenuRequest request) {
    if (request == null) {
      return;
    }
    UUID playerId = player.getUniqueId();
    Duration current = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    int index = Math.max(0, TIMEFRAMES.indexOf(current));
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("CLOCK", 1)
        .customName(messages.component(player, TIMEFRAME_KEYS.get(index))))
        .withActions(new RunnableAction(click -> {
          int next = (index + 1) % TIMEFRAMES.size();
          timeframes.put(playerId, TIMEFRAMES.get(next));
          refresh(page, player, request);
        })).withSlot(5).build());
  }

  private void addPlayerBalances(List<Component> lore, Player player, MarketRow row,
                                 List<AccountAssetBalance> assets) {
    if (assets == null || assets.isEmpty()) {
      return;
    }
    String marketId = row.marketId();
    ExchangeViewService.MarketView market = views.market(marketId);
    if (market == null) {
      return;
    }
    String currencyId = market.service().marketRules().currencyId();
    AccountAssetBalance currency = assets.stream()
        .filter(balance -> balance.kind() == AccountAssetBalance.Kind.CURRENCY
            && currencyId.equals(balance.assetId()))
        .findFirst().orElse(null);
    if (currency != null) {
      lore.add(messages.component(player, "ui-market-my-currency",
          currency.available().toPlainString(), currency.frozen().toPlainString()));
      if (row.lastPrice() != null && row.lastPrice().signum() > 0
          && currency.available().signum() > 0) {
        var rules = market.service().marketRules();
        java.math.BigDecimal worstCasePrice = row.lastPrice().multiply(
            java.math.BigDecimal.ONE.add(rules.makerFeeRate().max(rules.takerFeeRate())));
        java.math.BigDecimal maxQuantity = currency.available()
            .divide(worstCasePrice, 0, java.math.RoundingMode.FLOOR);
        long stepped = Math.max(0L, maxQuantity.longValue()
            / rules.minQuantity() * rules.minQuantity());
        if (stepped > 0) {
          lore.add(messages.component(player, "ui-market-afford-buy",
              row.lastPrice().toPlainString(), stepped));
        }
      }
    }
    if ("VIRTUAL_SECURITY".equals(row.assetType())) {
      AccountAssetBalance security = assets.stream()
          .filter(balance -> balance.kind() == AccountAssetBalance.Kind.SECURITY
              && marketId.equals(balance.assetId()))
          .findFirst().orElse(null);
      if (security != null) {
        lore.add(messages.component(player, "ui-market-my-security",
            security.available().toPlainString(), security.frozen().toPlainString()));
        long minimumUnit = market.service().marketRules().minQuantity();
        long sellable = security.available().longValue() / minimumUnit * minimumUnit;
        if (sellable > 0) {
          lore.add(messages.component(player, "ui-market-afford-sell", sellable));
        }
      }
      return;
    }
    AccountAssetBalance holding = assets.stream()
        .filter(balance -> balance.kind() == AccountAssetBalance.Kind.ITEM
            && marketId.equals(balance.assetId()))
        .findFirst().orElse(null);
    if (holding != null) {
      lore.add(messages.component(player, "ui-market-my-items",
          holding.available().toPlainString(), holding.frozen().toPlainString()));
      long sellable = holding.available().longValue();
      if (sellable > 0) {
        lore.add(messages.component(player, "ui-market-afford-sell", sellable));
      }
    }
  }

  private void addOpenOrderCount(List<Component> lore, Player player, MarketRow row,
                                 List<com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder> orders) {
    if (orders == null) {
      return;
    }
    long count = orders.stream()
        .filter(persisted -> row.marketId().equals(persisted.order().marketId()))
        .count();
    ExchangeViewService.MarketView market = views.market(row.marketId());
    int limit = market == null ? 0
        : market.service().accountOrderLimits().maximumOpenOrders();
    if (limit > 0) {
      lore.add(messages.component(player, "ui-market-my-open-orders", count, limit));
    } else if (count > 0) {
      lore.add(messages.component(player, "ui-market-my-open-orders-count", count));
    }
  }

  private void renderDepth(PlayerInstancePage page, Player player,
                           MarketDashboardPresenter.DashboardRows rows, MarketRow market) {
    for (int index = 0; index < rows.bids().size(); index++) {
      addDepthIcon(page, player, rows.bids().get(index), true, 9 + index, market);
      addDepthIcon(page, player, rows.asks().get(index), false, 14 + index, market);
    }
  }

  private void addExecutableDepthSummary(PlayerInstancePage page, Player player,
                                         MarketDashboardPresenter.DashboardRows rows) {
    page.addIcon(player.getUniqueId(), new IconBuilder(
        QuickShop.getInstance().stack().of("STRUCTURE_BLOCK", 1)
            .customName(messages.component(player, "ui-depth-executable-summary",
                rows.executableBidQuantity(), rows.executableAskQuantity())))
        .withSlot(7).build());
  }

  private void addDepthIcon(PlayerInstancePage page, Player player,
                            MarketDashboardPresenter.DepthRow row, boolean bid, int slot,
                            MarketRow market) {
    if (row.empty()) {
      page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(
          "BLACK_STAINED_GLASS_PANE", 1).customName(messages.component(player, "ui-depth-empty")))
          .withSlot(slot).build());
      return;
    }
    String material = row.executable() ? (bid ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE")
        : "GRAY_STAINED_GLASS_PANE";
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>(List.of(
        messages.component(player, "ui-depth-price", row.price().toPlainString()),
        messages.component(player, "ui-depth-quantity", row.quantity()),
        messages.component(player, "ui-depth-cumulative", row.cumulativeQuantity()),
        messages.component(player, "ui-depth-notional",
            row.price().multiply(java.math.BigDecimal.valueOf(row.quantity()))
                .stripTrailingZeros().toPlainString()),
        messages.component(player, row.executable() ? "ui-depth-executable" : "ui-depth-protected")));
    java.math.BigDecimal distance = distancePercent(row.price(), market.lastPrice());
    if (distance != null) {
      lore.add(messages.component(player, "ui-depth-distance", percent(distance)));
    }
    page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material,
        Math.max(1, row.strength())).customName(messages.component(player,
            bid ? "ui-depth-bid" : "ui-depth-ask")).lore(lore)).withSlot(slot).build());
  }

  private void renderCandles(PlayerInstancePage page, Player player,
                             MarketDashboardPresenter.DashboardRows rows) {
    for (int index = 0; index < rows.candles().size(); index++) {
      MarketDashboardPresenter.CandleRow row = rows.candles().get(index);
      int slot = 19 + index;
      if (row.empty()) {
        page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(
            "GRAY_STAINED_GLASS_PANE", 1)
            .customName(messages.component(player, "ui-trend-empty"))).withSlot(slot).build());
        continue;
      }
      String material = switch (row.direction()) {
        case UP -> "LIME_STAINED_GLASS_PANE";
        case DOWN -> "RED_STAINED_GLASS_PANE";
        case FLAT -> "YELLOW_STAINED_GLASS_PANE";
      };
      var candle = row.candle();
      java.math.BigDecimal change = candle.close().subtract(candle.open());
      java.math.BigDecimal changePercent = candle.open().signum() == 0
          ? java.math.BigDecimal.ZERO
          : change.multiply(java.math.BigDecimal.valueOf(100))
              .divide(candle.open(), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
      List<Component> lore = new java.util.ArrayList<>(List.of(
          messages.component(player, "ui-trend-time", candle.bucketStart().toString()),
          messages.component(player, "ui-trend-open", candle.open().toPlainString()),
          messages.component(player, "ui-trend-high", candle.high().toPlainString()),
          messages.component(player, "ui-trend-low", candle.low().toPlainString()),
          messages.component(player, "ui-trend-close", candle.close().toPlainString()),
          messages.component(player, "ui-trend-change", change.stripTrailingZeros().toPlainString(),
              changePercent.toPlainString()),
          messages.component(player, "ui-trend-volume", candle.volume())));
      page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material,
          Math.max(1, row.strength())).customName(messages.component(player,
              "ui-trend-title", messages.text(player, directionKey(row.direction())))).lore(lore))
          .withSlot(slot).build());
    }
  }

  private void addTradeSummary(PlayerInstancePage page, Player player,
                               MarketDashboardSnapshot dashboard) {
    ExchangeRepository.MarketTradeSummary summary = dashboard.tradeSummary24h();
    if (summary == null) {
      return;
    }
    List<Component> lore = List.of(
        messages.component(player, "ui-market-trades-24h", summary.tradeCount()),
        messages.component(player, "ui-market-trades-buy-sell", summary.buyCount(),
            summary.sellCount()),
        messages.component(player, "ui-market-volume", summary.volume()));
    page.addIcon(player.getUniqueId(), new IconBuilder(
        QuickShop.getInstance().stack().of("BARREL", 1)
            .customName(messages.component(player, "ui-market-trades-title")).lore(lore))
        .withSlot(6).build());
  }

  private void renderRecentTrades(PlayerInstancePage page, Player player,
                                  MarketDashboardSnapshot dashboard) {
    List<ExchangeRepository.MarketTradeRow> trades = dashboard.recentTrades();
    if (trades.isEmpty()) {
      page.addIcon(player.getUniqueId(), new IconBuilder(
          QuickShop.getInstance().stack().of("GRAY_STAINED_GLASS_PANE", 1)
              .customName(messages.component(player, "ui-market-recent-empty")))
          .withSlot(45).build());
      return;
    }
    page.addIcon(player.getUniqueId(), new IconBuilder(
        QuickShop.getInstance().stack().of("PAPER", 1)
            .customName(messages.component(player, "ui-market-recent-trades")))
        .withSlot(45).build());
    for (int index = 0; index < Math.min(6, trades.size()); index++) {
      ExchangeRepository.MarketTradeRow trade = trades.get(index);
      int slot = 46 + index;
      boolean buy = trade.takerSide() == OrderSide.BUY;
      List<Component> lore = List.of(
          messages.component(player, "ui-market-recent-trade-id", trade.matchSequence()),
          messages.component(player, "ui-market-recent-trade-quantity", trade.quantity()),
          messages.component(player, "ui-market-recent-trade-notional",
              trade.price().multiply(java.math.BigDecimal.valueOf(trade.quantity()))
                  .stripTrailingZeros().toPlainString()),
          messages.component(player, "ui-market-recent-trade-time",
              messages.relativeTime(trade.executedAt())));
      page.addIcon(player.getUniqueId(), new IconBuilder(
          QuickShop.getInstance().stack().of(buy ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE", 1)
              .customName(messages.component(player, "ui-market-recent-trade-title",
                  buy ? messages.text(player, "ui-market-recent-active-buy")
                      : messages.text(player, "ui-market-recent-active-sell"),
                  trade.price().toPlainString()))
              .lore(lore)).withSlot(slot).build());
    }
    addNavigation(page, player, 53, "ARROW", "ui-market-recent-more",
        ExchangeMenuPage.MARKET_TRADES);
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String title, ExchangeMenuPage target) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(messages.component(player, title)))
        .withActions(new RunnableAction(click -> {
          if (target == ExchangeMenuPage.MARKET_TRADES) {
            contexts.get(playerId).map(ExchangeMenuRequest::marketId)
                .ifPresent(marketId -> {
                  ExchangeMenuRequest request =
                      ExchangeMenuRequest.marketTrades(marketId, 1);
                  contexts.put(playerId, request);
                  MenuManager.instance().open(ExchangeMenu.NAME, target.page(),
                      click.player());
                });
          } else {
            contexts.put(playerId, ExchangeMenuRequest.page(target.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME, target.page(), click.player());
          }
        })).withSlot(slot).build());
  }

  private static net.kyori.adventure.text.format.NamedTextColor changeColor(BigDecimal change) {
    if (change == null) {
      return net.kyori.adventure.text.format.NamedTextColor.GRAY;
    }
    return change.signum() > 0 ? net.kyori.adventure.text.format.NamedTextColor.GREEN
        : change.signum() < 0 ? net.kyori.adventure.text.format.NamedTextColor.RED
        : net.kyori.adventure.text.format.NamedTextColor.YELLOW;
  }

  private static String notional(MarketDashboardSnapshot dashboard) {
    return dashboard.notional24h() == null ? "-"
        : dashboard.notional24h().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static java.math.BigDecimal distancePercent(java.math.BigDecimal price,
                                                      java.math.BigDecimal reference) {
    if (price == null || reference == null || reference.signum() <= 0) {
      return null;
    }
    return price.subtract(reference).abs().divide(reference, 8, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  private static String directionKey(MarketDashboardPresenter.CandleDirection direction) {
    return switch (direction) {
      case UP -> "ui-trend-up";
      case DOWN -> "ui-trend-down";
      case FLAT -> "ui-trend-flat";
    };
  }

  static String percent(BigDecimal fraction) {
    return fraction == null ? "-"
        : fraction.multiply(java.math.BigDecimal.valueOf(100))
            .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            + "%";
  }

  private void addOrderIcon(PlayerInstancePage page, Player player, MarketRow row,
                            OrderSide side, OrderType type, String material, int slot,
                            String title, ActionType actionType) {
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>(type == OrderType.LIMIT
        ? List.of(messages.component(player, "ui-order-limit-format"),
            messages.component(player, "ui-order-limit-example"))
        : List.of(messages.component(player, "ui-order-market-format"),
            messages.component(player, "ui-order-market-fixed-boundary")));
    if ("VIRTUAL_SECURITY".equals(row.assetType())) {
      lore.add(messages.component(player, "ui-order-virtual-hint"));
    }
    page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(messages.component(player, title)).lore(lore))
        .withActions(new RunnableAction(click -> requestOrder(player, row, side, type), actionType))
        .withSlot(slot).build());
  }

  private void requestOrder(Player player, MarketRow row, OrderSide side, OrderType type) {
    String denial = access.denial(player.getUniqueId(), row.status(), type, player::hasPermission)
        .orElse(null);
    if (denial != null) {
      player.sendMessage(messages.component(player, denial));
      return;
    }
    Function<String, Boolean> handler = type == OrderType.LIMIT
        ? prompts.limit(player.getUniqueId(), row.marketId(), side,
            ignored -> player.sendMessage(messages.component(player, "ui-order-limit-invalid")))
        : prompts.market(player.getUniqueId(), row.marketId(), side,
            ignored -> player.sendMessage(messages.component(player, "ui-order-market-invalid")));
    String prompt = orderPrompt(player, row, side, type);
    GuiChatInputManager.getInstance().requestInput(player, handler, prompt, ExchangeMenu.NAME,
        ExchangeMenuPage.ORDER_CONFIRM.page());
    player.closeInventory();
  }

  private String orderPrompt(Player player, MarketRow row, OrderSide side, OrderType type) {
    String best = side == OrderSide.BUY
        ? row.bestAsk() == null ? null : row.bestAsk().toPlainString()
        : row.bestBid() == null ? null : row.bestBid().toPlainString();
    String key = promptKey(type, best != null);
    if (best == null) {
      return messages.text(player, key);
    }
    return messages.text(player, key, best, side == OrderSide.BUY ? "buy" : "sell");
  }

  static String promptKey(OrderType type, boolean hasBest) {
    String base = type == OrderType.LIMIT ? "ui-order-limit-prompt" : "ui-order-market-prompt";
    return hasBest ? base + "-hint" : base;
  }

  private void renderFailure(PlayerInstancePage page, Player player, UUID playerId, String key) {
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    Component title = player == null ? Component.text(key) : messages.component(player, key);
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
        .customName(title)).withSlot(22).build());
  }
}
