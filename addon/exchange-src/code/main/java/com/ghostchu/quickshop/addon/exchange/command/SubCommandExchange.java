package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** QuickShop command-manager entry point delegating to the exchange command router. */
public final class SubCommandExchange implements CommandHandler<Player> {
  private final ExchangeCommandRouter router;
  private final Function<Player, CommandActor> actors;

  public SubCommandExchange(ExchangeCommandRouter router, Function<Player, CommandActor> actors) {
    this.router = Objects.requireNonNull(router, "router");
    this.actors = Objects.requireNonNull(actors, "actors");
  }

  @Override
  public void onCommand(@NotNull Player sender, @NotNull String commandLabel,
                        @NotNull CommandParser parser) {
    router.execute(actors.apply(sender), parser.getArgs().toArray(String[]::new));
  }

  @Override
  public List<String> onTabComplete(@NotNull Player sender, @NotNull String commandLabel,
                                    @NotNull CommandParser parser) {
    return router.tabComplete(actors.apply(sender), parser.getArgs().toArray(String[]::new));
  }
}
