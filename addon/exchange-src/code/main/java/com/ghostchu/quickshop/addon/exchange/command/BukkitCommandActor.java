package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.addon.exchange.Main.ReloadResult;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import com.ghostchu.quickshop.QuickShop;
import org.bukkit.entity.Player;

/** Bukkit adapter that keeps the command router independent from player and menu APIs. */
public final class BukkitCommandActor implements CommandActor {
  private final Player player;
  private final AddonMessageService messages;
  private final Locale locale;
  private final MenuOpener menus;
  private final Supplier<ReloadResult> reloadAction;

  public BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus) {
    this(player, messages, locale, menus, () -> new ReloadResult(true, null));
  }

  public BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus,
      Supplier<ReloadResult> reloadAction) {
    this.player = Objects.requireNonNull(player, "player");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.menus = Objects.requireNonNull(menus, "menus");
    this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
  }

  @Override
  public UUID accountId() {
    return player.getUniqueId();
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.hasPermission(permission);
  }

  @Override
  public void message(String key, Object... arguments) {
    player.sendMessage(messages.message(key, locale, arguments));
  }

  @Override
  public void executeAtOwner(Runnable action) {
    Objects.requireNonNull(action, "action");
    ExchangeSchedulers.folia().getScheduler().runAtEntityLater(player, action, 1L);
  }

  @Override
  public void openMenu(String menuName, int page) {
    menus.open(menuName, page);
  }

  @Override
  public void openMenu(ExchangeMenuRequest request) {
    menus.open(request);
  }

  @Override
  public void commandFailed() {
    player.sendMessage(messages.message("command-failed", locale));
  }

  @Override
  public void reloadRequested() {
    player.sendMessage(messages.message("reload-requested", locale));
    ReloadResult result = reloadAction.get();
    player.sendMessage(messages.message(result.success() ? "reload-success" : "reload-failed",
        locale, result.cause() == null ? "" : result.cause()));
  }

  @FunctionalInterface
  public interface MenuOpener {
    void open(String menuName, int page);

    default void open(ExchangeMenuRequest request) {
      open(request.menuName(), request.page());
    }
  }
}
