package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.api.event.Phase;
import com.ghostchu.quickshop.api.event.management.ShopCreateEvent;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Blocks only future QuickShop container shops for configured exchange-only vanilla materials. */
public final class ContainerShopPolicyListener implements Listener {
  private final MarketRegistry markets;

  public ContainerShopPolicyListener(MarketRegistry markets) {
    this.markets = Objects.requireNonNull(markets, "markets");
  }

  @EventHandler(ignoreCancelled = true)
  public void onCreate(ShopCreateEvent event) {
    if (!event.isPhase(Phase.PRE_CANCELLABLE) || event.shop().isEmpty()) {
      return;
    }
    if (shouldCancel(event.phase(), event.shop().orElseThrow().getItem().getType())) {
      event.setCancelled(true, Component.text("This item is exchange-only."));
    }
  }

  boolean shouldCancel(Phase phase, Material material) {
    return phase == Phase.PRE_CANCELLABLE && markets.blocksContainerShop(material);
  }
}
