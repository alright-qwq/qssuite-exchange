package com.ghostchu.quickshop.addon.exchange.command;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Bukkit `/qse` alias that delegates to the same router as `/quickshop exchange`. */
public final class QseAliasCommand implements TabExecutor {
  private final ExchangeCommandRouter router;
  private final Function<Player, CommandActor> actors;

  public QseAliasCommand(ExchangeCommandRouter router, Function<Player, CommandActor> actors) {
    this.router = Objects.requireNonNull(router, "router");
    this.actors = Objects.requireNonNull(actors, "actors");
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label,
                           @NotNull String[] args) {
    if (!(sender instanceof Player player)) {
      return false;
    }
    router.execute(actors.apply(player), args);
    return true;
  }

  @Override
  public List<String> onTabComplete(@NotNull CommandSender sender, Command command,
                                    @NotNull String alias, @NotNull String[] args) {
    if (!(sender instanceof Player)) {
      return List.of();
    }
    return router.tabComplete(actors.apply((Player) sender), args);
  }
}
