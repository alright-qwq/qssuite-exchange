package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.command.AdminCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.QseAliasCommand;
import com.ghostchu.quickshop.addon.exchange.command.RecoveryQseCommand;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.command.SubCommandExchange;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.platform.ExchangeSchedulers;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntime;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory;
import com.ghostchu.quickshop.addon.exchange.runtime.RuntimeExchangeRequestSubmitter;
import com.ghostchu.quickshop.addon.exchange.runtime.DrainingExecutor;
import com.ghostchu.quickshop.addon.exchange.runtime.ShutdownSequence;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuListener;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeChatInputManager;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuPlatform;
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
  /** Matches {@code config-version} in the bundled config.yml. */
  private static final int CONFIG_VERSION = 2;

  private ExchangeRuntime runtime;
  private ExchangeRuntimeFactory runtimeFactory;
  private CommandContainer exchangeCommand;
  private PluginCommand qseCommand;
  private ExchangeMenuService menus;
  private ExchangeMenuListener menuListener;
  private DrainingExecutor adminReads;
  private boolean mainListenerRegistered;
  private final Object lifecycleLock = new Object();

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
      if (new File(getDataFolder(), "config.yml").isFile()
          && !getConfig().contains("enabled")) {
        getLogger().severe("config.yml exists but could not be parsed (or is missing the"
            + " 'enabled' key); the addon is staying disabled. Fix the YAML and run"
            + " /qse reload to retry without restarting the server.");
      } else {
        getLogger().info("QuickShop Exchange is disabled in config.yml");
      }
      // Keep /qse reload alive even while disabled or after a config parse failure so the
      // operator can flip enabled back on without restarting the server.
      installRecoveryEntrypoints();
      return;
    }
    int configVersion = getConfig().getInt("config-version", 1);
    if (configVersion < CONFIG_VERSION) {
      getLogger().warning("config.yml is version " + configVersion + " but this build expects "
          + CONFIG_VERSION + "; new settings use their defaults. Review the bundled config.yml"
          + " for new options before relying on them.");
    }
    try {
      synchronized (lifecycleLock) {
        startExchange();
      }
    } catch (Exception failure) {
      synchronized (lifecycleLock) {
        cleanupAfterFailedStart();
      }
      getLogger().log(Level.SEVERE,
          "Exchange startup failed; the exchange is not running. Fix the configuration and run"
              + " /qse reload to retry without restarting the server. Cause:", failure);
    }
  }

  /**
   * Creates, starts and wires the exchange runtime. Safe to call again after a failed start or a
   * runtime teardown because it first releases any previous runtime and entry points.
   */
  private void startExchange() throws Exception {
    synchronized (lifecycleLock) {
      // The menu platform and chat prompt manager are process-wide singletons; initialize them
      // once so reloads never register duplicate menu listeners.
      ExchangeSchedulers.initialize(this);
      ExchangeMenuPlatform.initialize(this);
      ExchangeChatInputManager.initialize(this);
      ExchangeRuntimeFactory previousFactory = runtimeFactory;
      if (previousFactory != null) {
        try {
          previousFactory.closeListeners();
        } catch (Exception cleanupFailure) {
          getLogger().log(Level.SEVERE,
              "Exchange previous listener cleanup failed", cleanupFailure);
        }
      }
      ShutdownSequence.close(this::unregisterPlayerEntrypoints,
          () -> {
            if (runtime != null) {
              runtime.close();
            }
          }, failure -> getLogger().log(Level.SEVERE,
              "Exchange previous runtime cleanup failed", failure));
      runtime = null;
      runtimeFactory = new ExchangeRuntimeFactory(this, QuickShop.getInstance(), lifecycleLock);
      ExchangeRuntime started = runtimeFactory.create();
      started.start();
      runtime = started;
      registerPlayerEntrypoints();
      registerMainListener();
    }
  }

  private void registerMainListener() {
    if (mainListenerRegistered) {
      return;
    }
    Bukkit.getPluginManager().registerEvents(this, this);
    mainListenerRegistered = true;
  }

  private void cleanupAfterFailedStart() {
    synchronized (lifecycleLock) {
      ExchangeRuntime failedRuntime = runtime;
      ExchangeRuntimeFactory failedFactory = runtimeFactory;
      runtime = null;
      runtimeFactory = null;
      mainListenerRegistered = false;
      org.bukkit.event.HandlerList.unregisterAll((org.bukkit.event.Listener) this);
      ShutdownSequence.close(this::unregisterPlayerEntrypoints,
          () -> {
            if (failedRuntime != null) {
              failedRuntime.close();
            }
          }, cleanupFailure -> getLogger().log(Level.SEVERE,
              "Exchange startup cleanup failed", cleanupFailure));
      if (failedFactory != null) {
        try {
          failedFactory.closeListeners();
        } catch (Exception cleanupFailure) {
          getLogger().log(Level.SEVERE,
              "Exchange startup listener cleanup failed", cleanupFailure);
        }
      }
      // Keep /qse reload alive so the operator can fix the configuration and recover without
      // restarting the server, even when startup never reached the full entrypoint wiring.
      installRecoveryEntrypoints();
    }
  }

  /**
   * Wires a minimal {@code /qse} executor that only supports reload while the exchange runtime is
   * not started. Replaced by the full entrypoint set once a later start succeeds.
   */
  private void installRecoveryEntrypoints() {
    qseCommand = Objects.requireNonNull(getCommand("qse"), "qse command missing from plugin.yml");
    RecoveryQseCommand recovery = new RecoveryQseCommand(recoveryMessages(),
        player -> player.locale(), this::reloadExchangeConfig);
    qseCommand.setExecutor(recovery);
    qseCommand.setTabCompleter(recovery);
  }

  private AddonMessageService recoveryMessages() {
    try {
      return buildMessages();
    } catch (RuntimeException unreadable) {
      // A missing/unreadable messages file must never take away the recovery entrypoint.
      getLogger().warning("messages.yml unavailable for the recovery command; using built-in"
          + " fallback texts. Cause: " + unreadable.getMessage());
      return new AddonMessageService(java.util.Map.of("en-US", java.util.Map.of(
          "permission-denied", "You do not have permission for this exchange action.",
          "reload-requested", "Exchange configuration reload requested.",
          "reload-success", "Exchange configuration reloaded successfully.",
          "reload-failed", "Exchange reload failed; previous settings are still active."
              + " Cause: <0>",
          "runtime-not-started", "The exchange runtime is not started. Fix the configuration and"
              + " run /qse reload to retry.")));
    }
  }

  @Override
  public void onDisable() {
    synchronized (lifecycleLock) {
      ExchangeChatInputManager chatInputs = ExchangeChatInputManager.getInstance();
      if (chatInputs != null) {
        chatInputs.shutdown();
      }
      mainListenerRegistered = false;
      org.bukkit.event.HandlerList.unregisterAll((org.bukkit.event.Listener) this);
      closeRuntime();
    }
  }

  /**
   * Closes the active runtime and factory listeners, unregisters player entry points and resets
   * the runtime fields. Safe to call when nothing is running; leaves the plugin itself enabled so
   * a later {@code /qse reload} can start the exchange again.
   */
  private void closeRuntime() {
    ExchangeRuntime activeRuntime = runtime;
    ExchangeRuntimeFactory activeFactory = runtimeFactory;
    ShutdownSequence.close(this::unregisterPlayerEntrypoints,
        () -> {
          if (activeRuntime != null) {
            activeRuntime.close();
          }
        }, failure -> getLogger().log(Level.SEVERE, "Exchange shutdown cleanup failed", failure));
    runtime = null;
    runtimeFactory = null;
    if (activeFactory != null) {
      try {
        activeFactory.closeListeners();
      } catch (Exception cleanupFailure) {
        getLogger().log(Level.SEVERE, "Exchange listener cleanup failed", cleanupFailure);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuickShopReload(QSConfigurationReloadEvent event) {
    reloadExchangeConfig();
  }

  /**
   * Re-reads exchange configuration and hot-applies operational settings without restarting.
   * Returns a structured result that callers may surface to the player in their locale.
   */
  public ReloadResult reloadExchangeConfig() {
    synchronized (lifecycleLock) {
      reloadConfig();
      if (!getConfig().getBoolean("enabled", false)) {
        closeRuntime();
        // Keep /qse reload available so the operator can re-enable without a restart.
        installRecoveryEntrypoints();
        getLogger().info("QuickShop Exchange is disabled in config.yml");
        return new ReloadResult(true, null);
      }
      ExchangeRuntime activeRuntime = runtime;
      ExchangeRuntimeFactory factory = runtimeFactory;
      if (activeRuntime == null) {
        getLogger().info("Exchange runtime is not started; attempting a full startup recovery");
        try {
          startExchange();
          return new ReloadResult(true, null);
        } catch (Exception failure) {
          cleanupAfterFailedStart();
          getLogger().log(Level.SEVERE,
              "Exchange startup recovery failed; previous configuration is still in effect. Cause:",
              failure);
          String cause = failure.getMessage() == null
              ? failure.getClass().getSimpleName() : failure.getMessage();
          return new ReloadResult(false, cause);
        }
      }
      try {
        factory.reloadConfig();
        rewirePlayerEntrypoints();
        getLogger().info("Exchange configuration reloaded successfully");
        return new ReloadResult(true, null);
      } catch (Exception failure) {
        getLogger().log(Level.SEVERE,
            "Exchange configuration reload failed; keeping previous settings. Cause:", failure);
        String cause = failure.getMessage() == null
            ? failure.getClass().getSimpleName() : failure.getMessage();
        return new ReloadResult(false, cause);
      }
    }
  }

  /** Structured reload outcome so command actors can localize the message while keeping details. */
  public record ReloadResult(boolean success, String cause) {
    public ReloadResult {
      if (success && cause != null) {
        throw new IllegalArgumentException("successful reload must not carry a cause");
      }
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
    File messagesFile = new File(getDataFolder(), "messages.yml");
    try {
      return AddonMessageService.load(messagesFile);
    } catch (IllegalArgumentException unreadable) {
      if (!messagesFile.isFile()) {
        throw unreadable;
      }
      // A corrupted or structurally incomplete messages.yml must not leave the addon disabled
      // with no way back: back up the broken file, restore the bundled defaults and retry once.
      File backup = new File(getDataFolder(), "messages.yml.corrupted");
      try {
        java.nio.file.Files.move(messagesFile.toPath(), backup.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.io.IOException backupFailure) {
        throw new IllegalStateException(
            "messages.yml is unreadable and could not be backed up: "
                + unreadable.getMessage(), backupFailure);
      }
      getLogger().warning("messages.yml was unreadable; backed up the broken file to "
          + backup.getName() + " and restored the bundled defaults. Cause: "
          + unreadable.getMessage());
      saveResource("messages.yml", true);
      return AddonMessageService.load(messagesFile);
    }
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
