package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Starts recovery asynchronously after the player has joined. */
public final class TransferLoginListener implements Listener {
  private final Function<UUID, CompletableFuture<?>> recovery;

  public TransferLoginListener(TransferRecoveryService recovery) {
    this(recovery::recoverPlayer);
  }

  public TransferLoginListener(Function<UUID, CompletableFuture<?>> recovery) {
    this.recovery = Objects.requireNonNull(recovery, "recovery");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    recover(event.getPlayer().getUniqueId());
  }

  void recover(UUID accountId) {
    java.util.concurrent.CompletableFuture<?> pending =
        recovery.apply(Objects.requireNonNull(accountId, "accountId"));
    if (pending == null) {
      return;
    }
    pending.whenComplete((ignored, failure) -> {
      if (failure != null) {
        LOGGER.log(java.util.logging.Level.WARNING,
            "Exchange transfer recovery failed for account " + accountId, failure);
      }
    });
  }

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("QuickShop-Exchange.TransferRecovery");
}
