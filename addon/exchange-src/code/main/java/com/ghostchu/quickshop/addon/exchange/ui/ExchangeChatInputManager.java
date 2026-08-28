package com.ghostchu.quickshop.addon.exchange.ui;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.QuickShop;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Addon-owned chat prompt manager. Mirrors the main plugin's GuiChatInputManager, but re-opens
 * menus through the addon's own MenuManager so chat-driven prompts survive the menu library being
 * isolated in the addon namespace.
 */
public final class ExchangeChatInputManager implements Listener {
  private static ExchangeChatInputManager instance;

  private final Map<UUID, ChatInputContext> pendingInputs = new ConcurrentHashMap<>();
  private final JavaPlugin plugin;
  private boolean registered;

  private ExchangeChatInputManager(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public static synchronized void initialize(JavaPlugin plugin) {
    if (instance == null || instance.plugin == null) {
      // Create once per plugin lifecycle. Reloads reuse the same manager so the Bukkit listener
      // is never registered twice; an inert plugin-less fallback instance is replaced instead.
      instance = new ExchangeChatInputManager(plugin);
    }
  }

  public static ExchangeChatInputManager getInstance() {
    if (instance == null) {
      // Defensive fallback for tests or an uninitialized runtime; a plugin-less instance never
      // registers listeners, so it stays inert until Main.initialize wires the real one.
      instance = new ExchangeChatInputManager(null);
    }
    return instance;
  }

  /**
   * Registers a chat input handler for a player. The handler returns true when the input is
   * accepted; on acceptance (or failure) the configured menu is re-opened for the player.
   */
  public void requestInput(Player player, Function<String, Boolean> handler, String prompt,
                           String menuName, int menuPage) {
    ensureRegistered();
    pendingInputs.put(player.getUniqueId(), new ChatInputContext(handler, menuName, menuPage));
    if (prompt != null && !prompt.isEmpty()) {
      player.sendMessage(prompt);
    }
  }

  public boolean hasPendingInput(UUID playerId) {
    return pendingInputs.containsKey(playerId);
  }

  public void cancelInput(UUID playerId) {
    cancelInput(playerId, false);
  }

  public void cancelInput(UUID playerId, boolean reopenMenu) {
    ChatInputContext context = pendingInputs.remove(playerId);
    if (reopenMenu && context != null && context.menuName() != null) {
      reopenMenu(playerId, context);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onChat(AsyncPlayerChatEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    ChatInputContext context = pendingInputs.get(playerId);
    if (context == null) {
      return;
    }
    // Swallow the message so it never leaks into public chat.
    event.setCancelled(true);
    String message = event.getMessage().trim();
    Player eventPlayer = event.getPlayer();
    ExchangeSchedulers.folia().getScheduler().runAtEntityLater(eventPlayer, () -> {
      try {
        boolean accepted = context.handler().apply(message);
        if (accepted) {
          pendingInputs.remove(playerId);
          if (context.menuName() != null) {
            reopenMenu(playerId, context);
          }
        }
      } catch (RuntimeException failure) {
        plugin.getLogger().warning("Failed to process exchange chat input for " + playerId
            + "; re-opening the menu. Cause: " + failure);
        pendingInputs.remove(playerId);
        if (context.menuName() != null) {
          reopenMenu(playerId, context);
        }
      }
    }, 1);
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    pendingInputs.remove(event.getPlayer().getUniqueId());
  }

  private void reopenMenu(UUID playerId, ChatInputContext context) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) {
      return;
    }
    // Small delay on the player's region thread so the closed inventory settles first.
    ExchangeSchedulers.folia().getScheduler().runAtEntityLater(player, () -> {
      Player online = Bukkit.getPlayer(playerId);
      if (online == null || !online.isOnline()) {
        return;
      }
      MenuManager.instance().open(context.menuName(), context.menuPage(),
          ExchangeMenuPlatform.menuPlayer(online));
    }, 2);
  }

  private void ensureRegistered() {
    if (!registered && plugin != null) {
      Bukkit.getPluginManager().registerEvents(this, plugin);
      registered = true;
    }
  }

  /** Unregisters the listener and drops all pending prompts (plugin disable). */
  public void shutdown() {
    if (registered) {
      HandlerList.unregisterAll(this);
      registered = false;
    }
    pendingInputs.clear();
  }

  private record ChatInputContext(Function<String, Boolean> handler, String menuName,
                                  int menuPage) {
  }
}
