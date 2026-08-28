package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Objects;
import org.bukkit.entity.Player;

/** Executes an audited administrator command from the admin menu on behalf of a player. */
@FunctionalInterface
public interface AdminAction {
  void execute(Player player, String[] args);

  static AdminAction none() {
    return (player, args) -> {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(args, "args");
    };
  }
}
