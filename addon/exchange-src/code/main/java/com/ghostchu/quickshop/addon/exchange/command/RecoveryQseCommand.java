package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.Main.ReloadResult;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal entrypoint wired while the exchange runtime is not started. It keeps the
 * {@code /qse reload} recovery path alive after a failed startup so an operator can fix the
 * configuration and retry without restarting the server.
 */
public final class RecoveryQseCommand implements TabExecutor {
  private final AddonMessageService messages;
  private final Function<Player, Locale> locales;
  private final Supplier<ReloadResult> reloadAction;

  public RecoveryQseCommand(AddonMessageService messages, Function<Player, Locale> locales,
                            Supplier<ReloadResult> reloadAction) {
    this.messages = Objects.requireNonNull(messages, "messages");
    this.locales = Objects.requireNonNull(locales, "locales");
    this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label,
                           @NotNull String[] args) {
    if (!(sender instanceof Player player)) {
      return false;
    }
    Locale locale = locales.apply(player);
    if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
      if (!player.hasPermission("quickshop.exchange.admin.reload")) {
        player.sendMessage(messages.message("permission-denied", locale));
        return true;
      }
      player.sendMessage(messages.message("reload-requested", locale));
      ReloadResult result = reloadAction.get();
      player.sendMessage(messages.message(result.success() ? "reload-success" : "reload-failed",
          locale, result.cause() == null ? "" : result.cause()));
      return true;
    }
    player.sendMessage(messages.message("runtime-not-started", locale));
    return true;
  }

  @Override
  public List<String> onTabComplete(@NotNull CommandSender sender, Command command,
                                    @NotNull String alias, @NotNull String[] args) {
    return args.length <= 1 ? List.of("reload") : List.of();
  }
}
