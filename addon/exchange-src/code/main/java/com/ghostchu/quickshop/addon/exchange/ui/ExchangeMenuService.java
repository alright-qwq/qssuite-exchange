package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.manager.MenuManager;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.bukkit.entity.Player;

/** Owns exchange viewer lifecycle without stopping QuickShop's global menu manager. */
public final class ExchangeMenuService implements AutoCloseable {
  private final ExchangeMenu menu;
  private final ExchangeViewService views;
  private final ExchangeRequestSubmitter submitter;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeMenuLifecycle lifecycle;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  public ExchangeMenuService(ExchangeViewService views) {
    this(views, null, RolloutPolicy.DISABLED, null);
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter) {
    this(views, submitter, RolloutPolicy.DISABLED, null);
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter,
                             RolloutPolicy rollout, AddonMessageService messages) {
    this(views, submitter, rollout, messages, AdminAction.none());
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter,
                             RolloutPolicy rollout, AddonMessageService messages,
                             AdminAction admin) {
    this.views = Objects.requireNonNull(views, "views");
    this.submitter = submitter;
    contexts = new ExchangeMenuContextStore(this.views::unsubscribeMarketUpdates);
    lifecycle = new ExchangeMenuLifecycle(contexts, playerId -> {
      ExchangeChatInputManager.getInstance().cancelInput(playerId);
      MenuManager.instance().removeViewer(playerId);
      this.views.unsubscribeMarketUpdates(playerId);
    });
    menu = new ExchangeMenu(this.views, contexts, submitter,
        Objects.requireNonNull(rollout, "rollout"), messages,
        admin == null ? AdminAction.none() : admin);
    MenuManager.instance().addMenu(menu);
  }

  public void open(Player player, String requestedMenu, int requestedPage) {
    open(player, ExchangeMenuRequest.page(requestedMenu, requestedPage));
  }

  public void open(Player player, ExchangeMenuRequest request) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(request, "request");
    contexts.put(player.getUniqueId(), request);
    try {
      MenuViewer viewer = new MenuViewer(player.getUniqueId());
      MenuManager.instance().addViewer(viewer);
      MenuPlayer menuPlayer = ExchangeMenuPlatform.menuPlayer(player);
      int page = ExchangeMenuPage.forName(request.menuName()).page();
      MenuManager.instance().open(ExchangeMenu.NAME, page, menuPlayer);
    } catch (Throwable failure) {
      contexts.remove(player.getUniqueId());
      MenuManager.instance().removeViewer(player.getUniqueId());
      // Wrap Errors (classloading/linkage conflicts, e.g. a shaded menu API mismatch) into a
      // checked-style runtime exception so command routing can surface a friendly failure instead
      // of letting the Error escape into the platform command executor.
      throw new IllegalStateException("failed to open exchange menu", failure);
    }
  }

  public java.util.Optional<ExchangeMenuRequest> requestFor(UUID playerId) {
    return contexts.get(playerId);
  }

  public void playerClosed(UUID playerId) {
    lifecycle.playerQuit(playerId);
  }

  public void inventoryClosed(UUID playerId, String title) {
    lifecycle.inventoryClosed(playerId, title);
    if (ExchangeMenu.TITLE.equals(title)) {
      views.unsubscribeMarketUpdates(playerId);
      // A chat prompt opened from a menu must not outlive the menu: otherwise the player's next
      // chat message would be swallowed by a stale prompt after they pressed ESC. The close that
      // launched the prompt (requestInput -> closeInventory) is suppressed exactly once.
      ExchangeChatInputManager.getInstance().cancelInputAfterMenuClose(playerId);
    }
  }

  static void closeInventoryAtOwner(Player player, BiConsumer<Player, Runnable> scheduler) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(scheduler, "scheduler");
    try {
      scheduler.accept(player, player::closeInventory);
    } catch (RuntimeException ignored) {
      // During plugin disable, the platform may already reject new entity tasks. Never fall back
      // to cross-thread inventory access; viewer and context cleanup still completes locally.
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closeOnce();
    }
  }

  private void closeOnce() {
    for (UUID playerId : contexts.playerIds()) {
      views.unsubscribeMarketUpdates(playerId);
      ExchangeChatInputManager.getInstance().cancelInput(playerId);
      Player player = org.bukkit.Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        closeInventoryAtOwner(player,
            (owner, action) -> ExchangeSchedulers.folia().getScheduler().runAtEntityLater(owner, action, 1L));
      }
      MenuManager.instance().removeViewer(playerId);
    }
    if (submitter instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException("failed to close exchange request submitter", failure);
      }
    }
    contexts.close();
    // Viewers are per-player state; never stop or reset the global QuickShop menu manager.
  }
}
