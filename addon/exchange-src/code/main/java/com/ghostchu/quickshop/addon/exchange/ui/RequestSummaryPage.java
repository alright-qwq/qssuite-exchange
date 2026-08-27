package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import java.util.ArrayList;
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

/** Renders the exact request held for a confirmation or account page. */
final class RequestSummaryPage {
  private final ExchangeViewService views;
  private final ExchangeMenuPage expected;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeRequestSubmitter submitter;
  private final RolloutPolicy rollout;
  private final ExchangeUiMessages messages;

  RequestSummaryPage(ExchangeMenuPage expected, ExchangeMenuContextStore contexts,
                     ExchangeRequestSubmitter submitter, AddonMessageService messages) {
    this(null, expected, contexts, submitter, RolloutPolicy.DISABLED, messages);
  }

  RequestSummaryPage(ExchangeViewService views, ExchangeMenuPage expected,
                     ExchangeMenuContextStore contexts, ExchangeRequestSubmitter submitter,
                     RolloutPolicy rollout, AddonMessageService messages) {
    this.views = views;
    this.expected = expected;
    this.contexts = contexts;
    this.submitter = submitter;
    this.rollout = rollout;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest request = contexts.get(playerId).orElse(null);
    if (request == null || !expected.menuName().equals(request.menuName())) {
      page.getIcons(playerId).clear();
      page.setLockEmptySlots(true);
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-confirm-not-selected")))
          .withSlot(22);
      page.addIcon(playerId, icon.build());
      return;
    }
    render(page, player, request, null, null, false);
    if (request.order() != null && views != null) {
      views.marketQuoteAsync(request.marketId())
          .whenComplete((quote, failure) -> {
            if (failure != null || !contexts.isCurrent(playerId, request)) return;
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) return;
            QuickShop.folia().getScheduler().runAtEntityLater(online,
                () -> {
                  if (ExchangePageRenderGuard.permits(contexts, playerId, request, online::isOnline)) {
                    render(page, online, request, quote, null, false);
                  }
                }, 1L);
          });
    } else if (request.orderId() != null && views != null) {
      views.accountOpenOrder(playerId, request.orderId())
          .whenComplete((order, failure) -> {
            if (!contexts.isCurrent(playerId, request)) return;
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) return;
            QuickShop.folia().getScheduler().runAtEntityLater(online,
                () -> {
                  if (ExchangePageRenderGuard.permits(contexts, playerId, request, online::isOnline)) {
                    render(page, online, request, null,
                        failure == null ? order.orElse(null) : null, true);
                  }
                }, 1L);
          });
    }
  }

  private void render(PlayerInstancePage page, Player player, ExchangeMenuRequest request,
                      com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote,
                      ExchangeTransaction.PersistedOrder cancelOrder, boolean cancelLoaded) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    List<Component> lore = summary(player, request, quote, cancelOrder, cancelLoaded);
    IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
        .customName(messages.component(player, titleKey(request), titleArgument(request)))
        .lore(lore)).withSlot(22);
    page.addIcon(playerId, icon.build());
    if (request.order() != null && request.marketId() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("COMPASS", 1)
              .customName(messages.component(player, "ui-confirm-back-market")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.market(request.marketId()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.MARKET_DETAIL.page(), click.player());
          })).withSlot(0).build());
    } else if (request.transfer() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("CHEST", 1)
              .customName(messages.component(player, "ui-nav-assets")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(
                ExchangeMenuPage.ASSETS.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.ASSETS.page(), click.player());
          })).withSlot(0).build());
    } else if (request.orderId() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("WRITABLE_BOOK", 1)
              .customName(messages.component(player, "ui-nav-orders")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(
                ExchangeMenuPage.ORDERS.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.ORDERS.page(), click.player());
          })).withSlot(0).build());
    }
    if (submitter != null && request.requestId() != null
        && (request.order() != null || request.orderId() != null || request.transfer() != null)) {
      IconBuilder confirm = new IconBuilder(QuickShop.getInstance().stack().of("LIME_CONCRETE", 1)
          .customName(messages.component(player, "ui-confirm-action")));
      confirm.withActions(new RunnableAction(click -> submit(request, playerId))).withSlot(31);
      page.addIcon(playerId, confirm.build());
    }
  }

  private static String titleKey(ExchangeMenuRequest request) {
    if (request.order() != null) return "ui-confirm-order-title";
    if (request.transfer() != null) return "ui-confirm-transfer-title";
    if (request.orderId() != null) return "ui-confirm-cancel-title";
    return "ui-confirm-title";
  }

  private static Object titleArgument(ExchangeMenuRequest request) {
    if (request.order() != null) return request.order().type();
    if (request.transfer() != null) return request.transfer().kind();
    return "";
  }

  private List<Component> summary(Player player, ExchangeMenuRequest request,
                                  com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote,
                                  ExchangeTransaction.PersistedOrder cancelOrder,
                                  boolean cancelLoaded) {
    List<Component> lines = new ArrayList<>();
    if (request.requestId() != null) {
      lines.add(messages.component(player, "ui-confirm-request", request.requestId()));
    }
    if (request.marketId() != null) {
      lines.add(messages.component(player, "ui-confirm-market",
          views == null ? request.marketId()
              : views.marketDisplayName(request.marketId())));
    }
    if (request.order() != null) {
      var order = request.order();
      lines.add(messages.component(player, "ui-confirm-side", order.side()));
      lines.add(messages.component(player, "ui-confirm-quantity", order.quantity()));
      addQuantityLimitLine(lines, player, order);
      if (order.price() != null) {
        lines.add(messages.component(player, "ui-confirm-price", order.price().toPlainString()));
        lines.add(messages.component(player, "ui-confirm-estimated-notional",
            OrderConfirmation.estimatedNotional(order.price(), order.quantity()).toPlainString()));
        if (order.side() == OrderSide.BUY) {
          lines.add(messages.component(player, "ui-confirm-frozen-estimate",
              frozenEstimate(order, order.price()).toPlainString()));
        }
        addFeeLines(lines, player, order, order.price(), quote);
        if (quote != null) {
          java.math.BigDecimal executable = order.side() == OrderSide.BUY
              ? quote.bestAsk() : quote.bestBid();
          if (executable != null) {
            lines.add(messages.component(player, "ui-confirm-current-quote",
                executable.toPlainString()));
            boolean crosses = order.side() == OrderSide.BUY
                ? order.price().compareTo(executable) >= 0
                : order.price().compareTo(executable) <= 0;
            lines.add(messages.component(player, crosses
                ? "ui-confirm-limit-immediate" : "ui-confirm-limit-resting"));
          }
        }
      }
      if (order.slippageBoundary() != null) {
        lines.add(messages.component(player, "ui-confirm-protection",
            order.slippageBoundary().toPlainString()));
        lines.add(messages.component(player, "ui-confirm-estimated-notional",
            OrderConfirmation.estimatedNotional(order.slippageBoundary(), order.quantity())
                .toPlainString()));
        if (order.side() == OrderSide.BUY) {
          lines.add(messages.component(player, "ui-confirm-frozen-estimate",
              frozenEstimate(order, order.slippageBoundary()).toPlainString()));
        }
        addFeeLines(lines, player, order, order.slippageBoundary(), quote);
        if (quote != null) {
          java.math.BigDecimal executable = order.side() == OrderSide.BUY
              ? quote.bestAsk() : quote.bestBid();
          if (executable != null) {
            lines.add(messages.component(player, "ui-confirm-current-quote",
                executable.toPlainString()));
          }
        }
      }
    }
    if (request.transfer() != null) {
      var transfer = request.transfer();
      lines.add(messages.component(player, "ui-confirm-asset", transfer.assetId()));
      if (transfer.amount() != null) {
        lines.add(messages.component(player, "ui-confirm-amount", transfer.amount()));
      }
      if (transfer.quantity() > 0) {
        lines.add(messages.component(player, "ui-confirm-quantity", transfer.quantity()));
      }
    }
    if (request.orderId() != null) {
      lines.add(messages.component(player, "ui-confirm-order", request.orderId()));
      if (cancelOrder != null) {
        var order = cancelOrder.order();
        lines.add(messages.component(player, "ui-confirm-market",
            views == null ? order.marketId() : views.marketDisplayName(order.marketId())));
        lines.add(messages.component(player, "ui-confirm-side", order.side()));
        lines.add(messages.component(player, "ui-confirm-quantity", order.remainingQuantity()));
        lines.add(messages.component(player, order.side() == OrderSide.BUY
            ? "ui-confirm-cancel-release-currency" : "ui-confirm-cancel-release-quantity",
            order.side() == OrderSide.BUY
                ? cancelOrder.reservedCurrency() : cancelOrder.reservedQuantity()));
      } else {
        lines.add(messages.component(player, cancelLoaded
            ? "ui-confirm-cancel-gone" : "ui-confirm-cancel-loading"));
      }
    }
    return List.copyOf(lines);
  }

  private void addQuantityLimitLine(List<Component> lines, Player player,
                                    ExchangeMenuRequest.OrderDraft order) {
    if (views == null) {
      return;
    }
    ExchangeViewService.MarketView market = views.market(order.marketId());
    if (market == null) {
      return;
    }
    var rules = market.service().marketRules();
    lines.add(messages.component(player, "ui-confirm-quantity-limit",
        rules.minQuantity(), rules.maxQuantity()));
    lines.add(messages.component(player, "ui-confirm-price-limit",
        rules.minPrice().toPlainString(), rules.maxPrice().toPlainString()));
    lines.add(messages.component(player, "ui-confirm-tick-size",
        rules.tickSize().toPlainString()));
  }

  private void addFeeLines(List<Component> lines, Player player, ExchangeMenuRequest.OrderDraft order,
                         java.math.BigDecimal boundary,
                         com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote) {
    if (views == null) {
      return;
    }
    ExchangeViewService.MarketView market = views.market(order.marketId());
    if (market == null) {
      return;
    }
    var rules = market.service().marketRules();
    java.math.BigDecimal rate = order.type() == com.ghostchu.quickshop.addon.exchange.core.model.OrderType.MARKET
        ? rules.takerFeeRate() : feeRateForLimit(order, boundary, quote, rules);
    java.math.BigDecimal notional = OrderConfirmation.estimatedNotional(boundary, order.quantity());
    java.math.BigDecimal fee = notional.multiply(rate)
        .setScale(2, java.math.RoundingMode.HALF_UP);
    lines.add(messages.component(player, "ui-confirm-fee-rate",
        rate.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()));
    lines.add(messages.component(player, "ui-confirm-estimated-fee", fee.toPlainString()));
    if (order.side() == OrderSide.SELL) {
      lines.add(messages.component(player, "ui-confirm-estimated-net",
          notional.subtract(fee).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
    }
  }

  private java.math.BigDecimal feeRateForLimit(ExchangeMenuRequest.OrderDraft order,
                                               java.math.BigDecimal boundary,
                                               com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote,
                                               com.ghostchu.quickshop.addon.exchange.core.model.MarketRules rules) {
    if (order.type() != com.ghostchu.quickshop.addon.exchange.core.model.OrderType.LIMIT || quote == null) {
      return rules.makerFeeRate();
    }
    java.math.BigDecimal executable = order.side() == OrderSide.BUY
        ? quote.bestAsk() : quote.bestBid();
    if (executable == null) {
      return rules.makerFeeRate();
    }
    boolean crosses = order.side() == OrderSide.BUY
        ? boundary.compareTo(executable) >= 0
        : boundary.compareTo(executable) <= 0;
    return crosses ? rules.takerFeeRate() : rules.makerFeeRate();
  }

  /** Matches {@code ReservationCalculator}: buy orders freeze notional plus worst-case fees. */
  private java.math.BigDecimal frozenEstimate(ExchangeMenuRequest.OrderDraft order,
                                              java.math.BigDecimal boundary) {
    java.math.BigDecimal notional = OrderConfirmation.estimatedNotional(boundary, order.quantity());
    if (views == null) {
      return notional;
    }
    ExchangeViewService.MarketView market = views.market(order.marketId());
    if (market == null) {
      return notional;
    }
    var rules = market.service().marketRules();
    java.math.BigDecimal maximumRate = rules.makerFeeRate().max(rules.takerFeeRate());
    java.math.BigDecimal fee = notional.multiply(maximumRate)
        .setScale(2, java.math.RoundingMode.HALF_UP);
    return notional.add(fee);
  }

  private void submit(ExchangeMenuRequest request, UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()
        || !ExchangeRequestPermission.allows(playerId, request, player::hasPermission, rollout)) {
      return;
    }
    if (!contexts.claim(playerId, request)) {
      return;
    }
    submitter.submit(request).whenComplete((result, failure) -> {
      Player onlinePlayer = Bukkit.getPlayer(playerId);
      if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(onlinePlayer, () -> {
        if (failure != null) {
          onlinePlayer.sendMessage(messages.component(onlinePlayer, "ui-confirm-submit-failed"));
        } else {
          onlinePlayer.sendMessage(messages.component(onlinePlayer, "ui-confirm-submit-result",
              result.outcome(), result.reference()));
          navigateAfterSubmit(onlinePlayer, request, result);
        }
      }, 1L);
    });
  }

  private void navigateAfterSubmit(Player player, ExchangeMenuRequest request,
                                   ExchangeRequestSubmitter.SubmissionResult result) {
    if (result.outcome() != ExchangeRequestSubmitter.Outcome.ACCEPTED
        && result.outcome() != ExchangeRequestSubmitter.Outcome.REVIEW_REQUIRED) {
      return;
    }
    UUID playerId = player.getUniqueId();
    if (request.order() != null && request.marketId() != null) {
      contexts.put(playerId, ExchangeMenuRequest.market(request.marketId()));
      MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_DETAIL.page(),
          QuickShop.getInstance().createMenuPlayer(player));
    } else if (request.orderId() != null) {
      contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.ORDERS.menuName()));
      MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ORDERS.page(),
          QuickShop.getInstance().createMenuPlayer(player));
    } else if (request.transfer() != null) {
      contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.ASSETS.menuName()));
      MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ASSETS.page(),
          QuickShop.getInstance().createMenuPlayer(player));
    }
  }
}
