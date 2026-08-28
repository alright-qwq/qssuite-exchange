package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeChatInputManager;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays configured custody assets, balances and recent transfers for the current player. */
final class AssetsPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final AssetTransferPrompt prompts;
  private final ExchangeUiMessages messages;

  AssetsPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
             AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.prompts = new AssetTransferPrompt(contexts, UUID::randomUUID);
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null) return;
    views.subscribeMarketUpdates(playerId, update -> {
      if (contexts.isCurrent(playerId, opened) && Bukkit.getPlayer(playerId) != null
          && Bukkit.getPlayer(playerId).isOnline()) {
        refresh(page, playerId, opened);
      }
    });
    refresh(page, playerId, opened);
  }

  private void refresh(PlayerInstancePage page, UUID playerId, ExchangeMenuRequest opened) {
    int pageNumber = AssetTransferPaging.page(opened.page());
    int offset = AssetTransferPaging.offset(pageNumber);
    CompletableFuture<List<AccountAssetBalance>> assets = views.accountAssets(playerId);
    CompletableFuture<List<TransferRecord>> transfers =
        views.accountTransfers(playerId, AssetTransferPaging.fetchLimit(), offset);
    CompletableFuture<Map<String, MarketQuote>> quotes = assets.thenApply(balances ->
        balances.stream()
            .filter(balance -> balance.kind() == AccountAssetBalance.Kind.SECURITY
                || balance.kind() == AccountAssetBalance.Kind.ITEM)
            .map(AccountAssetBalance::assetId)
            .toList()).thenCompose(views::marketQuotes);
    AssetPageSnapshot.combine(assets, transfers, quotes)
        .whenComplete((snapshot, failure) -> {
      if (opened == null || !contexts.isCurrent(playerId, opened)) return;
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) return;
      ExchangeSchedulers.folia().getScheduler().runAtEntityLater(player,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
              render(page, player, snapshot, failure, pageNumber);
            }
          }, 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, AssetPageSnapshot snapshot,
                      Throwable failure, int pageNumber) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null || snapshot == null || snapshot.failure() != null) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    addMarketsNavigation(page, player);
    int slot = 9;
    AssetPageRows.Merged merged = AssetPageRows.merge(views.transferTargets(), snapshot.assets());
    addTotalValue(page, player, playerId, merged, snapshot);
    for (AssetPageRows.Row row : merged.rows()) {
      if (slot >= 21) break;
      TransferTarget target = row.target();
      List<Component> lore = List.of(
          messages.component(player, "ui-assets-available",
              messages.formatCurrency(row.available())),
          messages.component(player, "ui-assets-frozen",
              messages.formatCurrency(row.frozen())),
          messages.component(player, "ui-assets-deposit-action"),
          messages.component(player, "ui-assets-withdraw-action"));
      IconBuilder icon = new IconBuilder(ExchangeMenuPlatform.stack().of(
          target.kind() == TransferTarget.Kind.CURRENCY ? "GOLD_INGOT" : "CHEST", 1)
          .customName(Component.text(target.displayName())).lore(lore));
      icon.withActions(
          new RunnableAction(click -> requestTransfer(playerId, target, true), ActionType.LEFT_CLICK),
          new RunnableAction(click -> requestTransfer(playerId, target, false), ActionType.RIGHT_CLICK))
          .withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
    if (merged.rows().size() > 12) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("BOOK", 1)
          .customName(messages.component(player, "ui-assets-more-currency",
              merged.rows().size() - 12)))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.HISTORY.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.HISTORY.page(),
                click.player());
          })).withSlot(20).build());
    }
    slot = 21;
    for (AssetPageRows.SecurityRow security : merged.securities()) {
      if (slot >= 33) break;
      java.util.ArrayList<Component> securityLore = new java.util.ArrayList<>(List.of(
          messages.component(player, "ui-assets-virtual-security"),
          messages.component(player, "ui-assets-symbol", security.symbol()),
          messages.component(player, "ui-assets-available", security.available().toPlainString()),
          messages.component(player, "ui-assets-frozen", security.frozen().toPlainString()),
          messages.component(player, "ui-assets-open-market")));
      java.math.BigDecimal marketValue = marketValue(security, snapshot.quotes());
      if (marketValue != null) {
        securityLore.add(messages.component(player, "ui-assets-market-value",
            messages.formatCurrency(marketValue, marketPriceScale(security.marketId()))));
      }
      IconBuilder icon = new IconBuilder(ExchangeMenuPlatform.stack().of("EMERALD", 1)
          .customName(Component.text(security.displayName())).lore(securityLore));
      icon.withActions(new RunnableAction(click -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()) return;
        contexts.put(playerId, ExchangeMenuRequest.market(security.marketId()));
        MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_DETAIL.page(),
            click.player());
      })).withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
    if (merged.securities().size() > 12) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("MAP", 1)
          .customName(messages.component(player, "ui-assets-more-securities",
              merged.securities().size() - 12)))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.MARKETS.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
                click.player());
          })).withSlot(32).build());
    }
    slot = 33;
    for (TransferRecord transfer : snapshot.transfers()) {
      if (slot >= 45) break;
      String reason = transfer.failureReason() == null ? "" : " " + transfer.failureReason();
      java.util.List<Component> transferLore = List.of(
          messages.component(player, "ui-assets-transfer-kind",
              transfer.type(), transfer.assetId()),
          messages.component(player, "ui-assets-transfer-amount",
              messages.formatCurrency(transfer.amount())),
          messages.component(player, "ui-assets-transfer-status",
              transfer.status() + reason),
          messages.component(player, "ui-history-created-at",
              messages.relativeTime(transfer.updatedAt())));
      String transferMaterial = switch (transfer.status()) {
        case COMPLETED -> "GREEN_CONCRETE";
        case FAILED -> "RED_CONCRETE";
        default -> "HOPPER";
      };
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of(transferMaterial, 1)
          .customName(messages.component(player, "ui-assets-transfer-title", transfer.status()))
          .lore(transferLore)).withSlot(slot++).build());
    }
    if (snapshot.transfers().isEmpty()) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("PAPER", 1)
          .customName(messages.component(player, "ui-assets-transfers-empty"))).withSlot(slot++).build());
    }
    addTransferNavigation(page, player, pageNumber,
        AssetTransferPaging.hasNext(snapshot.transfers().size()));
  }

  private void addTransferNavigation(PlayerInstancePage page, Player player, int currentPage,
                                     boolean hasNext) {
    UUID playerId = player.getUniqueId();
    if (currentPage > 1) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("ARROW", 1)
          .customName(messages.component(player, "ui-history-previous")))
          .withActions(new RunnableAction(click -> openAssetsPage(click.player(), currentPage - 1)))
          .withSlot(45).build());
    }
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("CLOCK", 1)
        .customName(messages.component(player, "ui-assets-transfers-page", currentPage)))
        .withSlot(49).build());
    if (hasNext) {
      page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("ARROW", 1)
          .customName(messages.component(player, "ui-history-next")))
          .withActions(new RunnableAction(click -> openAssetsPage(click.player(), currentPage + 1)))
          .withSlot(53).build());
    }
  }

  private void openAssetsPage(MenuPlayer menuPlayer, int pageNumber) {
    UUID playerId = menuPlayer.identifier();
    contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.ASSETS.menuName(), pageNumber));
    MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ASSETS.page(), menuPlayer);
  }

  private void addTotalValue(PlayerInstancePage page, Player player, UUID playerId,
                             AssetPageRows.Merged merged, AssetPageSnapshot snapshot) {
    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
    java.math.BigDecimal frozen = java.math.BigDecimal.ZERO;
    for (AssetPageRows.Row row : merged.rows()) {
      if (row.target().kind() == TransferTarget.Kind.CURRENCY) {
        total = total.add(row.available()).add(row.frozen());
        frozen = frozen.add(row.frozen());
      } else if (row.target().kind() == TransferTarget.Kind.ITEM) {
        java.math.BigDecimal value = itemValue(row, snapshot.quotes());
        if (value != null) {
          total = total.add(value);
        }
      }
    }
    for (AssetPageRows.SecurityRow security : merged.securities()) {
      java.math.BigDecimal value = marketValue(security, snapshot.quotes());
      if (value != null) {
        total = total.add(value);
      }
    }
    int aggregateScale = aggregateScale(merged);
    java.util.List<Component> lore = new java.util.ArrayList<>(List.of(
        messages.component(player, "ui-assets-total-value-amount",
            messages.formatCurrency(total, aggregateScale))));
    if (frozen.signum() > 0) {
      lore.add(messages.component(player, "ui-assets-total-value-frozen",
          messages.formatCurrency(frozen, aggregateScale)));
    }
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("DIAMOND", 1)
        .customName(messages.component(player, "ui-assets-total-value")).lore(lore))
        .withSlot(4).build());
  }

  private java.math.BigDecimal marketValue(AssetPageRows.SecurityRow security,
                                           Map<String, MarketQuote> quotes) {
    String marketId = security.marketId();
    if (marketId == null || quotes == null) {
      return null;
    }
    MarketQuote quote = quotes.get(marketId);
    if (quote == null || quote.lastPrice() == null) {
      return null;
    }
    java.math.BigDecimal quantity = security.available().add(security.frozen());
    return quote.lastPrice().multiply(quantity);
  }

  private java.math.BigDecimal itemValue(AssetPageRows.Row row,
                                         Map<String, MarketQuote> quotes) {
    String marketId = row.target().marketId();
    if (marketId == null || quotes == null) {
      return null;
    }
    MarketQuote quote = quotes.get(marketId);
    if (quote == null || quote.lastPrice() == null) {
      return null;
    }
    return quote.lastPrice().multiply(row.available().add(row.frozen()));
  }

  private int marketPriceScale(String marketId) {
    if (marketId == null) {
      return -1;
    }
    ExchangeViewService.MarketView market = views.market(marketId);
    int scale = market == null ? -1 : market.service().marketRules().priceScale();
    return scale;
  }

  /** Uses the widest price scale across visible markets so mixed-scale totals do not truncate. */
  private int aggregateScale(AssetPageRows.Merged merged) {
    int scale = 2;
    for (AssetPageRows.Row row : merged.rows()) {
      scale = Math.max(scale, marketPriceScale(row.target().marketId()));
    }
    for (AssetPageRows.SecurityRow security : merged.securities()) {
      scale = Math.max(scale, marketPriceScale(security.marketId()));
    }
    return scale;
  }

  private void addMarketsNavigation(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("COMPASS", 1)
        .customName(messages.component(player, "ui-nav-markets")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.MARKETS.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
              click.player());
        })).withSlot(0).build());
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("WRITABLE_BOOK", 1)
        .customName(messages.component(player, "ui-nav-orders")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.ORDERS.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ORDERS.page(),
              click.player());
        })).withSlot(1).build());
    page.addIcon(playerId, new IconBuilder(ExchangeMenuPlatform.stack().of("CLOCK", 1)
        .customName(messages.component(player, "ui-nav-history")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.HISTORY.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.HISTORY.page(),
              click.player());
        })).withSlot(2).build());
  }

  private void requestTransfer(UUID playerId, TransferTarget target, boolean deposit) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    String permission = deposit ? "quickshop.exchange.deposit" : "quickshop.exchange.withdraw";
    if (!player.hasPermission(permission)) {
      player.sendMessage(messages.component(player, "permission-denied"));
      return;
    }
    ExchangeMenuRequest.TransferKind kind = kind(target, deposit);
    java.util.function.Function<String, Boolean> handler =
        target.kind() == TransferTarget.Kind.CURRENCY
            ? prompts.currency(playerId, kind, target.assetId(),
                () -> player.sendMessage(messages.component(player, "ui-transfer-money-invalid")))
            : prompts.item(playerId, kind, target.marketId(),
                () -> player.sendMessage(messages.component(player, "ui-transfer-item-invalid")));
    String prompt = target.kind() == TransferTarget.Kind.CURRENCY
        ? messages.text(player, "ui-transfer-money-prompt")
        : messages.text(player, "ui-transfer-item-prompt");
    ExchangeChatInputManager.getInstance().requestInput(player, handler, prompt, ExchangeMenu.NAME,
        ExchangeMenuPage.TRANSFER_CONFIRM.page());
    player.closeInventory();
  }

  private static ExchangeMenuRequest.TransferKind kind(TransferTarget target, boolean deposit) {
    if (target.kind() == TransferTarget.Kind.CURRENCY) {
      return deposit ? ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT
          : ExchangeMenuRequest.TransferKind.MONEY_WITHDRAWAL;
    }
    return deposit ? ExchangeMenuRequest.TransferKind.ITEM_DEPOSIT
        : ExchangeMenuRequest.TransferKind.ITEM_WITHDRAWAL;
  }
}
