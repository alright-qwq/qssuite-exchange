package com.ghostchu.quickshop.addon.exchange.ui;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.QuickShop;
import java.util.Objects;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.item.bukkit.BukkitItemStack;
import net.tnemc.item.paper.PaperItemStack;
import net.tnemc.menu.bukkit.BukkitMenuHandler;
import net.tnemc.menu.bukkit.BukkitPlayer;
import net.tnemc.menu.core.MenuHandler;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.folia.FoliaMenuHandler;
import net.tnemc.menu.folia.FoliaPlayer;
import net.tnemc.menu.paper.PaperMenuHandler;
import net.tnemc.menu.paper.PaperPlayer;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the addon-local TNML menu platform. Every menu class the addon renders with lives in the
 * addon's relocated namespace, so no TNML type ever crosses the plugin classloader boundary and
 * the menu cannot collide with the QuickShop-Hikari shaded copy.
 */
public final class ExchangeMenuPlatform {
  private static JavaPlugin plugin;
  private static Kind kind;
  private static MenuHandler handler;

  private enum Kind { FOLIA, PAPER, BUKKIT }

  private ExchangeMenuPlatform() {
  }

  /** Creates and registers the platform handlers exactly once; repeated calls are ignored. */
  public static synchronized void initialize(JavaPlugin addon) {
    if (handler != null) {
      return;
    }
    plugin = Objects.requireNonNull(addon, "addon");
    if (ExchangeSchedulers.folia().isFolia()) {
      kind = Kind.FOLIA;
      handler = new FoliaMenuHandler(addon, true);
    } else if (ExchangeSchedulers.folia().isPaper()) {
      kind = Kind.PAPER;
      handler = new PaperMenuHandler(addon, true);
    } else {
      kind = Kind.BUKKIT;
      handler = new BukkitMenuHandler(addon, true);
    }
  }

  /** Returns a fresh item stack builder matching the runtime platform. */
  public static AbstractItemStack<?> stack() {
    if (ExchangeSchedulers.folia().isPaper()) {
      return new PaperItemStack();
    }
    return new BukkitItemStack();
  }

  /** Wraps a player in the addon's own menu player adapter. */
  public static MenuPlayer menuPlayer(OfflinePlayer player) {
    Objects.requireNonNull(player, "player");
    if (handler == null) {
      throw new IllegalStateException("exchange menu platform is not initialized");
    }
    return switch (kind) {
      case FOLIA -> new FoliaPlayer(player, plugin);
      case PAPER -> new PaperPlayer(player, plugin);
      case BUKKIT -> new BukkitPlayer(player, plugin);
    };
  }
}
