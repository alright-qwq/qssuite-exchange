package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.command.AdminCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.QseAliasCommand;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.command.SubCommandExchange;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntime;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory;
import com.ghostchu.quickshop.addon.exchange.runtime.RuntimeExchangeRequestSubmitter;
import com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor;
import com.ghostchu.quickshop.addon.exchange.runtime.ShutdownSequence;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuListener;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuService;
import com.ghostchu.quickshop.api.command.CommandContainer;
import com.ghostchu.quickshop.api.event.QSConfigurationReloadEvent;
import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin implements Listener {
  private ExchangeRuntime runtime;
  private ExchangeRuntimeFactory runtimeFactory;
  private CommandContainer exchangeCommand;
  private PluginCommand qseCommand;
  private ExchangeMenuService menus;
  private ExchangeMenuListener menuListener;
  private DrainingExecutor adminReads;

  static java.util.List<String> firstRunResources() {
    return java.util.List.of("markets.yml", "messages.yml");
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();
    for (String resource : firstRunResources()) {
      if (!new File(getDataFolder(), resource).isFile()) {
        saveResource(resource, false);
      }
    }
    if (!getConfig().getBoolean("enabled", false)) {
      getLogger().info("QuickShop Exchange is disabled in config.yml");
      return;
    }
    try {
      runtimeFactory = new ExchangeRuntimeFactory(this, QuickShop.getInstance());
      runtime = runtimeFactory.create();
      runtime.start();
      registerPlayerEntrypoints();
      Bukkit.getPluginManager().registerEvents(this, this);
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE,
          "Exchange startup failed; addon remains disabled. Fix the configuration and run"
              + " /qse reload or restart the server. Cause:", failure);
    }
  }

  @Override
  public void onDisable() {
    ExchangeRuntime activeRuntime = runtime;
    ShutdownSequence.close(this::unregisterPlayerEntrypoints,
        () -> {
          if (activeRuntime != null) {
            activeRuntime.close();
          }
        }, failure -> getLogger().log(Level.SEVERE, "Exchange shutdown cleanup failed", failure));
    runtime = null;
    runtimeFactory = null;
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuickShopReload(QSConfigurationReloadEvent event) {
    reloadExchangeConfig();
  }

  /** Re-reads exchange configuration and hot-applies operational settings without restarting. */
  public void reloadExchangeConfig() {
    ExchangeRuntime activeRuntime = runtime;
    ExchangeRuntimeFactory factory = runtimeFactory;
    if (activeRuntime == null || factory == null) {
      getLogger().warning("Exchange reload skipped: runtime is not started");
      return;
    }
    try {
      reloadConfig();
      factory.reloadConfig();
      rewirePlayerEntrypoints();
      getLogger().info("Exchange configuration reloaded successfully");
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE,
          "Exchange configuration reload failed; keeping previous settings. Cause:", failure);
    }
  }

  private void registerPlayerEntrypoints() {
    AddonMessageService messages = buildMessages();
    RolloutPolicy rollout = rolloutPolicy();
    installPlayerEntrypoints(messages, rollout);
  }

  private void rewirePlayerEntrypoints() {
    // Build and validate every replacement first so a malformed message/rollout configuration
    // can never leave the plugin without command or GUI entry points.
    AddonMessageService messages = buildMessages();
    RolloutPolicy rollout = rolloutPolicy();
    unregisterPlayerEntrypoints();
    installPlayerEntrypoints(messages, rollout);
  }

  private AddonMessageService buildMessages() {
    return AddonMessageService.load(
        new File(getDataFolder(), "messages.yml"));
  }

  private void installPlayerEntrypoints(AddonMessageService messages, RolloutPolicy rollout) {
    menus = new ExchangeMenuService(runtime.views(), new RuntimeExchangeRequestSubmitter(runtime),
        rollout, messages);
    menuListener = new ExchangeMenuListener(menus);
    Bukkit.getPluginManager().registerEvents(menuListener, this);
    adminReads = new DrainingExecutor("qs-exchange-admin-read-", java.time.Duration.ofSeconds(30));
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID,
        new AdminCommandRouter(runtime.administration(), UUID::randomUUID,
            work -> runtime.runWhileWriting(work::run),
            adminReads, runtime.views()::resolveMarketIdBySymbol),
        rollout, runtime.views()::resolveMarketIdBySymbol,
        runtime.views()::securitySymbols);
    var actors = (java.util.function.Function<org.bukkit.entity.Player,
        com.ghostchu.quickshop.addon.exchange.command.CommandActor>) player ->
        new BukkitCommandActor(player, messages, player.locale(),
            new BukkitCommandActor.MenuOpener() {
              @Override
              public void open(String menu, int page) {
                menus.open(player, menu, page);
              }

              @Override
              public void open(ExchangeMenuRequest request) {
                menus.open(player, request);
              }
            }, this::reloadExchangeConfig);
    exchangeCommand = CommandContainer.builder()
        .prefix("exchange")
        .description(locale -> net.kyori.adventure.text.Component.text(
            messages.message("command-description", java.util.Locale.forLanguageTag(locale))))
        .executor(new SubCommandExchange(router, actors))
        .build();
    QuickShop.getInstance().getCommandManager().registerCmd(exchangeCommand);

    qseCommand = Objects.requireNonNull(getCommand("qse"), "qse command missing from plugin.yml");
    QseAliasCommand alias = new QseAliasCommand(router, actors);
    qseCommand.setExecutor(alias);
    qseCommand.setTabCompleter(alias);
  }

  private RolloutPolicy rolloutPolicy() {
    boolean enabled = getConfig().getBoolean("rollout.whitelist-enabled", true);
    java.util.Set<UUID> allowed = new java.util.HashSet<>();
    for (String value : getConfig().getStringList("rollout.allowed-players")) {
      try {
        allowed.add(UUID.fromString(value.trim()));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("invalid rollout player UUID: " + value, invalid);
      }
    }
    return new RolloutPolicy(enabled, allowed);
  }

  private void unregisterPlayerEntrypoints() {
    ShutdownSequence.closeAll(java.util.List.of(
        () -> {
          CommandContainer command = exchangeCommand;
          exchangeCommand = null;
          if (command != null) {
            QuickShop.getInstance().getCommandManager().unregisterCmd(command);
          }
        },
        () -> {
          PluginCommand command = qseCommand;
          qseCommand = null;
          if (command != null) {
            command.setExecutor(null);
            command.setTabCompleter(null);
          }
        },
        () -> {
          ExchangeMenuService activeMenus = menus;
          menus = null;
          if (activeMenus != null) {
            activeMenus.close();
          }
        },
        () -> {
          ExchangeMenuListener listener = menuListener;
          menuListener = null;
          if (listener != null) {
            HandlerList.unregisterAll(listener);
          }
        },
        () -> {
          DrainingExecutor reads = adminReads;
          adminReads = null;
          if (reads != null) {
            reads.close();
          }
        }), failure -> getLogger().log(Level.SEVERE, "Exchange entrypoint cleanup failed", failure));
  }
}
