package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.config.AssetType;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.config.MarketStateReader;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.platform.FoliaInventoryGateway;
import com.ghostchu.quickshop.addon.exchange.platform.ContainerShopPolicyListener;
import com.ghostchu.quickshop.addon.exchange.platform.QuickShopEconomyGateway;
import com.ghostchu.quickshop.addon.exchange.platform.TransferLoginListener;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.service.AssetCustody;
import com.ghostchu.quickshop.addon.exchange.service.ItemAssetCustody;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeActionService;
import com.ghostchu.quickshop.addon.exchange.service.SecurityAssetCustody;
import com.ghostchu.quickshop.addon.exchange.security.SecurityService;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.PlayerOperationSerialiser;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService.MarketView;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Production composition root for the exchange's recoverable single-writer runtime. */
public final class ExchangeRuntimeFactory {
  private final JavaPlugin addon;
  private final QuickShop quickShop;
  private volatile Database database;
  private volatile Map<String, PersistentOrderService> markets;
  private volatile MarketDataService marketData;
  private volatile MarketRegistry registry;
  private volatile JdbcExchangeRepository repository;
  private volatile ExchangeActionService actions;
  private volatile ExchangeViewService views;
  private volatile AdminExchangeService administration;
  private volatile FoliaInventoryGateway inventoryGateway;
  private volatile ScheduledExecutorService maintenance;
  /** Volatile scheduler settings re-read on every reload so they hot-apply without a restart. */
  private volatile int candleRetentionDays = 365;
  private volatile int reconciliationIntervalMinutes = 1440;
  private volatile ScheduledFuture<?> reconciliationTask;
  private volatile org.bukkit.event.Listener containerShopListener;
  private volatile org.bukkit.event.Listener transferLoginListener;
  private final Object lifecycleLock;

  public ExchangeRuntimeFactory(JavaPlugin addon, QuickShop quickShop) {
    this(addon, quickShop, null);
  }

  public ExchangeRuntimeFactory(JavaPlugin addon, QuickShop quickShop, Object lifecycleLock) {
    this.addon = java.util.Objects.requireNonNull(addon, "addon");
    this.quickShop = java.util.Objects.requireNonNull(quickShop, "quickShop");
    this.lifecycleLock = lifecycleLock == null ? this : lifecycleLock;
  }

  public ExchangeRuntime create() throws Exception {
    Database database = database();
    this.database = database;
    database.writer().acquire();
    ScheduledExecutorService uiMaintenance = null;
    try {
      TableNames tables = new TableNames(quickShop.getDbPrefix());
      File marketsFile = new File(addon.getDataFolder(), "markets.yml");
      File configFile = new File(addon.getDataFolder(), "config.yml");
      MarketRegistry configured = MarketRegistry.load(configFile, marketsFile);
      java.util.concurrent.atomic.AtomicReference<JdbcExchangeRepository> bootstrapped =
          new java.util.concurrent.atomic.AtomicReference<>();
      boolean startupOwned = database.writer().runWhileHeld(() -> {
        new MigrationRunner(database.connections(), database.dialect(), tables).migrate();
        JdbcExchangeRepository repository = new JdbcExchangeRepository(
            database.connections(), database.dialect(), tables);
        registerMarkets(database.connections(), tables, configured);
        validateRegisteredMarkets(database.connections(), tables, configured);
        bootstrapped.set(repository);
      });
      if (!startupOwned || bootstrapped.get() == null) {
        throw new IllegalStateException("exchange writer lock was lost during database bootstrap");
      }
      JdbcExchangeRepository repository = bootstrapped.get();
      this.repository = repository;
      MarketRegistry registry = MarketRegistry.load(configFile, marketsFile, repository);
      this.registry = registry;

    MarketDataService marketData = new MarketDataService(new CandleAggregator(), repository);
    this.marketData = marketData;
    com.ghostchu.quickshop.addon.exchange.operations.ExchangeMetrics metrics =
        new com.ghostchu.quickshop.addon.exchange.operations.ExchangeMetrics();
    java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
        lastEventAt = new java.util.concurrent.ConcurrentHashMap<>();
    marketData.addAuditConsumer(event -> {
      long now = System.currentTimeMillis();
      java.util.concurrent.atomic.AtomicLong previous =
          lastEventAt.put(event.marketId(), new java.util.concurrent.atomic.AtomicLong(now));
      if (previous != null) {
        long prior = previous.getAndSet(now);
        metrics.recordMatchingLatency(event.marketId(),
            java.time.Duration.ofMillis(Math.max(0L, now - prior)));
      }
    });
    Map<String, PersistentOrderService> markets = new java.util.LinkedHashMap<>();
    Map<String, MarketDefinition> reloadedDefinitions = new java.util.LinkedHashMap<>();
    for (String marketId : registry.marketIds()) {
      MarketDefinition definition = registry.require(marketId);
      reloadedDefinitions.put(marketId, definition);
      MarketRules rules = rules(definition);
      RiskLimits limits = limits(definition);
      AssetCustody custody = definition.assetType() == AssetType.VIRTUAL_SECURITY
          ? new SecurityAssetCustody(definition.security().minimumUnit())
          : ItemAssetCustody.INSTANCE;
      markets.put(marketId, new PersistentOrderService(
          repository, rules, limits, RecoveryHandler.NO_OP,
          accountLimits(definition.risk()), marketData, custody));
    }
    this.markets = java.util.Map.copyOf(markets);

    PlayerOperationSerialiser playerOperations = new PlayerOperationSerialiser();
    NamespacedKey marker = new NamespacedKey(addon, "exchange-transfer");
    FoliaInventoryGateway inventory = new FoliaInventoryGateway(quickShop, marker);
    inventory.updateTimeout(inventoryAccessTimeout(addon.getConfig()));
    this.inventoryGateway = inventory;
    MoneyTransferService moneyTransfers = new MoneyTransferService(repository, repository,
        new QuickShopEconomyGateway(quickShop, economyWorld()), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ItemTransferService itemTransfers = new ItemTransferService(repository, repository, inventory,
        marketId -> itemTemplate(registry.require(marketId)), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ExchangeActionService actions = new ExchangeActionService(
        markets, moneyTransfers, itemTransfers,
        marketId -> registry.require(marketId).assetType() == AssetType.VIRTUAL_SECURITY);
    this.actions = actions;
    DrainingExecutor recoveryExecutor = new DrainingExecutor(
        "qs-exchange-recovery-", Duration.ofSeconds(30));
    DrainingExecutor recoveryFenceExecutor = new DrainingExecutor(
        "qs-exchange-recovery-fence-", Duration.ofSeconds(30));
    TransferRecoveryService transfers = new TransferRecoveryService(
        repository, repository, inventory, recoveryExecutor);
    ContainerShopPolicyListener containerShop = new ContainerShopPolicyListener(registry);
    this.containerShopListener = containerShop;
    Bukkit.getPluginManager().registerEvents(containerShop, addon);

    AutoCloseable dispatcher = () -> {};
    this.maintenance = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("qs-exchange-maintenance-", 0).factory());
    // UI refreshes and view reads run on their own pool so a slow maintenance scan (detection,
    // reconciliation, candle purge) can never stall player GUI updates.
    uiMaintenance = Executors.newScheduledThreadPool(2,
        Thread.ofPlatform().daemon(true).name("qs-exchange-ui-", 0).factory());
    Map<String, ExchangeViewService.MarketView> marketViews = new java.util.LinkedHashMap<>();
    Map<String, com.ghostchu.quickshop.addon.exchange.ui.TransferTarget> transferTargets =
        new java.util.LinkedHashMap<>();
    for (Map.Entry<String, PersistentOrderService> entry : markets.entrySet()) {
      MarketDefinition definition = registry.require(entry.getKey());
      String assetType = definition.assetType().name();
      String symbol = definition.assetType() == AssetType.VIRTUAL_SECURITY
          ? definition.security().symbol() : null;
      Long totalSupply = definition.assetType() == AssetType.VIRTUAL_SECURITY
          ? definition.security().totalSupply() : null;
      marketViews.put(entry.getKey(), new ExchangeViewService.MarketView(
          entry.getKey(), definition.displayName(), entry.getValue(),
          assetType, symbol, totalSupply,
          () -> {
            if (definition.assetType() != AssetType.VIRTUAL_SECURITY) {
              return null;
            }
            try {
              return repository.inTransaction(
                  tx -> tx.securityDefinition(entry.getKey()).status());
            } catch (SQLException failure) {
              throw new IllegalStateException(
                  "failed to load security status: " + entry.getKey(), failure);
            }
          },
          () -> {
            if (definition.assetType() != AssetType.VIRTUAL_SECURITY) {
              return null;
            }
            try {
              return repository.inTransaction(
                  tx -> tx.securityDefinition(entry.getKey()).issuedSupply());
            } catch (SQLException failure) {
              throw new IllegalStateException(
                  "failed to load issued supply: " + entry.getKey(), failure);
            }
          }));
      String currencyId = definition.structural().currencyId();
      transferTargets.putIfAbsent("currency:" + currencyId,
          com.ghostchu.quickshop.addon.exchange.ui.TransferTarget.currency(currencyId));
      if (definition.assetType() != AssetType.VIRTUAL_SECURITY) {
        transferTargets.put("item:" + entry.getKey(),
            com.ghostchu.quickshop.addon.exchange.ui.TransferTarget.item(
                entry.getKey(), definition.displayName()));
      }
    }
    long guiRefreshMs = Math.max(250L, addon.getConfig().getLong("market-data.gui-refresh-ms", 1000));
    ExchangeViewService views = new ExchangeViewService(marketViews, marketData, uiMaintenance,
        repository, java.util.List.copyOf(transferTargets.values()),
        java.time.Duration.ofMillis(guiRefreshMs));
    this.views = views;
    java.nio.file.Path auditDirectory = requireAuditDirectory(
        addon.getDataFolder().toPath(),
        addon.getConfig().getString("operations.audit-export-directory", "audit"));
    AdminExchangeService administration = new AdminExchangeService(
        markets, repository, new com.ghostchu.quickshop.addon.exchange.operations.AuditExporter(),
        auditDirectory, new SecurityService(repository), inventory, metrics,
        this::addSecurityMarket);
    this.administration = administration;
    Runnable resumeHalted = () -> resumeExpiredHalts(repository, registry.marketIds(), database.writer());
    com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector detector =
        new com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector(Clock.systemUTC());
    Runnable detectSuspiciousTrading = () -> {
      try {
        Instant since = Instant.now().minusSeconds(300);
        var scan = detector.scan(repository.tradesForDetection(since),
            repository.orderActivities(since));
        for (var alert : scan.alerts()) {
          repository.insertAuditAlert(new com.ghostchu.quickshop.addon.exchange.operations.AuditAlert(
              UUID.randomUUID(), alert.marketId(), alert.accountId(), alert.type(),
              alert.severity(), alert.evidence(), alert.at(), null));
          addon.getLogger().warning("Exchange suspicious-trading alert: "
              + alert.type() + "@" + alert.marketId() + " " + alert.evidence());
        }
      } catch (Exception failure) {
        // Detection is best-effort; the next scheduled tick retries without taking the writer fence.
      }
    };
    this.maintenance.scheduleWithFixedDelay(detectSuspiciousTrading, 2L, 5L, TimeUnit.MINUTES);
    this.maintenance.scheduleWithFixedDelay(resumeHalted, 1L, 1L, TimeUnit.MINUTES);
    this.maintenance.scheduleWithFixedDelay(() -> flushWhileOwned(
        database.writer(), marketData, Instant.now()), 1L, 1L, TimeUnit.MINUTES);
    this.candleRetentionDays = Math.max(1, addon.getConfig().getInt(
        "market-data.candle-retention-days", 365));
    this.maintenance.scheduleWithFixedDelay(() -> runWhileOwned(
        database.writer(), () -> marketData.purgeOldCandles(
            java.time.Duration.ofDays(candleRetentionDays),
            java.util.List.copyOf(this.registry.marketIds()))),
        30L, 24L * 60L, TimeUnit.MINUTES);
    this.reconciliationIntervalMinutes = addon.getConfig().getInt(
        "operations.reconciliation-interval-minutes", 1440);
    scheduleReconciliation(this.maintenance, repository);
    uiMaintenance.scheduleWithFixedDelay(marketData::publishPlayerUpdates,
        1L, 1L, TimeUnit.SECONDS);
    final ScheduledExecutorService maintenanceForClose = this.maintenance;
    final ScheduledExecutorService uiMaintenanceForClose = uiMaintenance;

      ExchangeRuntime runtime = new ExchangeRuntime(database.writer(),
          () -> recoverMarkets(markets), transfers::recoverAllMoneyTransfers, dispatcher,
          lockLossFence(),
          () -> {
            maintenanceForClose.shutdownNow();
            uiMaintenanceForClose.shutdownNow();
            recoveryFenceExecutor.close();
            recoveryExecutor.close();
            playerOperations.close();
            runWhileOwnedOrThrow(database.writer(), () -> marketData.flush(Instant.now()));
          }, views, administration, actions);
      TransferLoginListener transferLogin = new TransferLoginListener(accountId ->
          runtime.callAsyncWhileWriting(() -> transfers.recoverPlayer(accountId),
              recoveryFenceExecutor));
      this.transferLoginListener = transferLogin;
      Bukkit.getPluginManager().registerEvents(transferLogin, addon);
      return runtime;
    } catch (Exception failure) {
      unregisterRuntimeListeners();
      if (this.maintenance != null) {
        this.maintenance.shutdownNow();
        this.maintenance = null;
      }
      if (uiMaintenance != null) {
        uiMaintenance.shutdownNow();
      }
      database.writer().close();
      throw failure;
    }
  }

  /** Unregisters runtime listeners so a failed create cannot leave half-wired handlers active. */
  private void unregisterRuntimeListeners() {
    org.bukkit.event.Listener containerShop = this.containerShopListener;
    this.containerShopListener = null;
    if (containerShop != null) {
      org.bukkit.event.HandlerList.unregisterAll(containerShop);
    }
    org.bukkit.event.Listener transferLogin = this.transferLoginListener;
    this.transferLoginListener = null;
    if (transferLogin != null) {
      org.bukkit.event.HandlerList.unregisterAll(transferLogin);
    }
  }

  /** Releases any runtime-registered Bukkit listeners. Safe to call at any lifecycle point. */
  public void closeListeners() {
    unregisterRuntimeListeners();
  }

  /** Resolves the entity-scheduled inventory wait budget from config with a sane default. */
  private static Duration inventoryAccessTimeout(FileConfiguration config) {
    long configured = config.getLong("operations.inventory-access-timeout-ms", 10000);
    if (configured < 250) {
      return Duration.ofMillis(250);
    }
    return Duration.ofMillis(configured);
  }

  /** Re-reads markets.yml/config.yml and hot-applies operational risk settings. */
  public void reloadConfig() {
    synchronized (lifecycleLock) {
      Map<String, PersistentOrderService> liveMarkets = this.markets;
      MarketRegistry liveRegistry = this.registry;
      ExchangeViewService liveViews = this.views;
      if (liveMarkets == null || liveRegistry == null) {
        throw new IllegalStateException("exchange runtime is not started");
      }
      File reloadConfig = new File(addon.getDataFolder(), "config.yml");
      File reloadMarkets = new File(addon.getDataFolder(), "markets.yml");
      MarketRegistry reloaded;
      try {
        // Reload must not restore persisted versions onto the freshly parsed definitions: the live
        // registry already carries the current versions and its own repository persistence, so the
        // reload below persists any version bump. Restoring here would reject legitimate fee/risk
        // changes whose persisted active rate still matches the old file.
        reloaded = MarketRegistry.load(reloadConfig, reloadMarkets);
      } catch (IllegalArgumentException configurationFailure) {
        throw new IllegalArgumentException(
            "exchange configuration is invalid; fix markets.yml/config.yml and retry /qse reload"
                + " (previous settings are still active). Cause: " + configurationFailure.getMessage(),
            configurationFailure);
      }
      if (!liveMarkets.keySet().equals(reloaded.marketIds())) {
        java.util.Set<String> added = new java.util.TreeSet<>(reloaded.marketIds());
        added.removeAll(liveMarkets.keySet());
        java.util.Set<String> removed = new java.util.TreeSet<>(liveMarkets.keySet());
        removed.removeAll(reloaded.marketIds());
        StringBuilder blocked = new StringBuilder("market set cannot change during a hot reload");
        if (!added.isEmpty()) {
          blocked.append("; add new markets while the server is stopped or use the admin"
              + " create-market command instead: ").append(added);
        }
        if (!removed.isEmpty()) {
          blocked.append("; remove markets while the server is stopped after pausing them and"
              + " cancelling open orders: ").append(removed);
        }
        throw new IllegalStateException(
            blocked.toString());
      }
      java.util.List<String> allBlockers = new java.util.ArrayList<>();
      for (String marketId : liveMarkets.keySet()) {
        MarketDefinition current = liveRegistry.require(marketId);
        MarketDefinition next = reloaded.require(marketId);
        allBlockers.addAll(describeReloadBlockers(current, next));
      }
      if (!allBlockers.isEmpty()) {
        throw new IllegalArgumentException(String.join("; ", allBlockers)
            + ". Pause the affected markets, cancel their open orders, then retry /qse reload");
      }
      // Commit the registry first: its persistence write is the only step that can fail, and a
      // failure leaves the registry (and therefore every service/view) untouched. The subsequent
      // in-memory applications are pure swaps that cannot fail once the definitions are committed.
      liveRegistry.reload(reloaded.definitions(),
          ignored -> new MarketStateReader.State(MarketStatus.PAUSED, 0));
      for (String marketId : liveMarkets.keySet()) {
        MarketDefinition next = liveRegistry.require(marketId);
        PersistentOrderService service = liveMarkets.get(marketId);
        service.updateRiskLimits(limits(next), accountLimits(next.risk()));
      }
      this.registry = liveRegistry;
      if (liveViews != null) {
        for (String marketId : liveMarkets.keySet()) {
          MarketDefinition next = liveRegistry.require(marketId);
          String symbol = next.assetType() == AssetType.VIRTUAL_SECURITY
              ? next.security().symbol() : null;
          Long totalSupply = next.assetType() == AssetType.VIRTUAL_SECURITY
              ? next.security().totalSupply() : null;
          liveViews.updateMarketMetadata(marketId, next.displayName(),
              next.assetType().name(), symbol, totalSupply);
        }
        long guiRefreshMs = Math.max(250L, addon.getConfig().getLong(
            "market-data.gui-refresh-ms", 1000));
        liveViews.updateRefreshInterval(java.time.Duration.ofMillis(guiRefreshMs));
      }
      FoliaInventoryGateway liveInventory = this.inventoryGateway;
      if (liveInventory != null) {
        liveInventory.updateTimeout(inventoryAccessTimeout(addon.getConfig()));
      }
      FileConfiguration config = addon.getConfig();
      this.candleRetentionDays = Math.max(1, config.getInt(
          "market-data.candle-retention-days", 365));
      int nextReconciliationInterval = config.getInt(
          "operations.reconciliation-interval-minutes", 1440);
      if (nextReconciliationInterval != this.reconciliationIntervalMinutes) {
        this.reconciliationIntervalMinutes = nextReconciliationInterval;
        ScheduledFuture<?> previous = this.reconciliationTask;
        this.reconciliationTask = null;
        if (previous != null) {
          previous.cancel(false);
        }
        scheduleReconciliation(this.maintenance, this.repository);
      }
    }
  }

  /**
   * Schedules the ledger reconciliation on the maintenance executor. Reload cancels the previous
   * future and reschedules with the new interval, so changing or disabling reconciliation never
   * requires a restart.
   */
  private void scheduleReconciliation(
      ScheduledExecutorService maintenance, JdbcExchangeRepository repository) {
    int interval = this.reconciliationIntervalMinutes;
    if (interval <= 0 || maintenance == null || maintenance.isShutdown()) {
      return;
    }
    this.reconciliationTask = maintenance.scheduleWithFixedDelay(() -> runWhileOwned(
        database.writer(), () -> {
          com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport report =
              repository.reconcile();
          if (!report.balanced()) {
            addon.getLogger().warning("Exchange reconciliation detected differences: "
                + report);
          }
        }), 15L, interval, TimeUnit.MINUTES);
  }

  /**
   * Hot-adds a newly created virtual security market so it can be traded without a restart.
   * The persisted rows are created by {@link SecurityService}; this method only attaches the
   * live order book, registry entry, view and action wiring.
   */
  public void addSecurityMarket(String marketId, boolean replayed) {
    synchronized (lifecycleLock) {
      MarketRegistry liveRegistry = this.registry;
      ExchangeViewService liveViews = this.views;
      ExchangeActionService liveActions = this.actions;
      JdbcExchangeRepository store = this.repository;
      Database database = this.database;
      Map<String, PersistentOrderService> liveMarkets = this.markets;
      if (liveRegistry == null || liveViews == null || liveActions == null || store == null
          || database == null || liveMarkets == null) {
        throw new IllegalStateException("exchange runtime is not started");
      }
      if (liveMarkets.containsKey(marketId)) {
        if (replayed) {
          return;
        }
        throw new IllegalArgumentException("market already exists in runtime: " + marketId);
      }
      boolean marketRowExists;
      try {
        marketRowExists = store.marketExists(marketId);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to verify created market row", failure);
      }
      if (!marketRowExists) {
        throw new IllegalStateException(
            "created market is missing its persisted market row: " + marketId);
      }
      MarketDefinition definition;
      try {
        definition = store.inTransaction(tx -> {
          SecurityDefinitionState security =
              tx.securityDefinition(marketId);
          return SecurityService.buildMarketDefinition(
              marketId, security.symbol(), security.name(), security.description(),
              security.currencyId(), security.basePrice(), security.totalSupply(),
              security.minimumUnit());
        });
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load created security definition", failure);
      }
      MarketDataService marketData = this.marketData;
      MarketRules rules = rules(definition);
      RiskLimits limits = limits(definition);
      AssetCustody custody = new SecurityAssetCustody(definition.security().minimumUnit());
      PersistentOrderService service = new PersistentOrderService(
          store, rules, limits, RecoveryHandler.NO_OP,
          accountLimits(definition.risk()), marketData, custody);
      try {
        service.recoverFromDatabase();
      } catch (SQLException failure) {
        throw new IllegalStateException(
            "failed to recover the newly created order book: " + marketId, failure);
      }
      MarketView view = buildMarketView(definition, service, store);
      if (!replayed) {
        persistMarketToMarketsFile(definition);
      }
      if (this.markets.containsKey(marketId)) {
        if (replayed) {
          return;
        }
        throw new IllegalArgumentException("market already exists in runtime: " + marketId);
      }
      this.registry = liveRegistry;
      liveRegistry.addMarket(definition);
      this.markets = extendMarkets(this.markets, marketId, service);
      this.actions = liveActions.withMarket(marketId, service);
      this.views = liveViews;
      liveViews.addMarket(view);
      AdminExchangeService liveAdministration = this.administration;
      if (liveAdministration != null) {
        liveAdministration.registerMarket(marketId, service);
      }
    }
  }

  private void persistMarketToMarketsFile(MarketDefinition definition) {
    File marketsFile = new File(addon.getDataFolder(), "markets.yml");
    try {
      YamlConfiguration markets = YamlConfiguration.loadConfiguration(marketsFile);
      ConfigurationSection section = markets.getConfigurationSection("markets");
      if (section == null) {
        section = markets.createSection("markets");
      }
      if (section.contains(definition.marketId())) {
        return;
      }
      ConfigurationSection market = section.createSection(definition.marketId());
      market.set("enabled", false);
      market.set("display-name", definition.displayName());
      if (definition.assetType() == AssetType.VIRTUAL_SECURITY) {
        ConfigurationSection security = market.createSection("security");
        security.set("symbol", definition.security().symbol());
        security.set("name", definition.security().name());
        security.set("description", definition.security().description());
        security.set("base-price", definition.security().basePrice().toPlainString());
        security.set("total-supply", definition.security().totalSupply());
        security.set("minimum-unit", definition.security().minimumUnit());
      }
      market.set("currency", definition.structural().currencyId());
      market.set("base-price", definition.structural().basePrice().toPlainString());
      market.set("min-price", definition.structural().minPrice().toPlainString());
      market.set("max-price", definition.structural().maxPrice().toPlainString());
      market.set("tick-size", definition.structural().tickSize().toPlainString());
      market.set("price-scale", definition.structural().priceScale());
      market.set("currency-scale", definition.structural().currencyScale());
      market.set("min-quantity", definition.structural().minQuantity());
      market.set("max-quantity", definition.structural().maxQuantity());
      market.set("discovery-quantity", definition.structural().discoveryQuantity());
      market.set("maker-fee-rate", definition.risk().makerFeeRate().toPlainString());
      market.set("taker-fee-rate", definition.risk().takerFeeRate().toPlainString());
      market.set("max-account-holding", definition.risk().maxAccountHolding());
      market.set("max-frozen-currency", definition.risk().maxFrozenCurrency().toPlainString());
      market.set("max-open-orders", definition.risk().maxOpenOrders());
      market.set("block-container-shops", definition.blockContainerShops());
      markets.set("markets", section);
      markets.save(marketsFile);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "created market could not be persisted to markets.yml: " + definition.marketId(),
          failure);
    }
  }

  private static Map<String, PersistentOrderService> extendMarkets(
      Map<String, PersistentOrderService> current, String marketId,
      PersistentOrderService service) {
    Map<String, PersistentOrderService> extended = new java.util.LinkedHashMap<>(current);
    extended.put(marketId, service);
    return Map.copyOf(extended);
  }

  private MarketView buildMarketView(
      MarketDefinition definition, PersistentOrderService service,
      JdbcExchangeRepository store) {
    String symbol = definition.security() == null ? null : definition.security().symbol();
    Long totalSupply = definition.security() == null ? null : definition.security().totalSupply();
    return new MarketView(definition.marketId(), definition.displayName(), service,
        definition.assetType().name(), symbol, totalSupply,
        () -> {
          try {
            return store.inTransaction(tx -> tx.securityDefinition(definition.marketId()).status());
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "failed to load security status: " + definition.marketId(), failure);
          }
        },
        () -> {
          try {
            return store.inTransaction(
                tx -> tx.securityDefinition(definition.marketId()).issuedSupply());
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "failed to load issued supply: " + definition.marketId(), failure);
          }
        });
  }

  static boolean requireReloadableStructure(
      MarketDefinition current, MarketDefinition next) {
    java.util.List<String> blockers = describeReloadBlockers(current, next);
    if (!blockers.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", blockers)
          + ". Pause the market, cancel its open orders, then retry /qse reload");
    }
    return true;
  }

  /**
   * Explains exactly which fields changed for each market so the operator knows what prevents a
   * hot reload. The returned list is intentionally empty for risk-only changes, which reload
   * applies live without pausing the market.
   */
  static java.util.List<String> describeReloadBlockers(
      MarketDefinition current, MarketDefinition next) {
    java.util.List<String> blockers = new java.util.ArrayList<>();
    if (current.assetType() != next.assetType()) {
      blockers.add(current.marketId() + ": asset type changed from "
          + current.assetType() + " to " + next.assetType());
    }
    if (!java.util.Objects.equals(current.item(), next.item())) {
      blockers.add(current.marketId() + ": item definition changed");
    }
    if (!java.util.Objects.equals(current.security(), next.security())) {
      blockers.add(current.marketId() + ": security metadata changed");
    }
    java.util.List<String> structural = structuralDiffs(current.structural(), next.structural());
    for (String difference : structural) {
      blockers.add(current.marketId() + ": " + difference);
    }
    if (feeRatesDiffer(current, next)) {
      blockers.add(current.marketId() + ": maker/taker fee rates changed");
    }
    return java.util.List.copyOf(blockers);
  }

  private static java.util.List<String> structuralDiffs(
      MarketDefinition.StructuralRules first, MarketDefinition.StructuralRules second) {
    java.util.List<String> differences = new java.util.ArrayList<>();
    if (!first.currencyId().equals(second.currencyId())) {
      differences.add("currency changed from " + first.currencyId() + " to " + second.currencyId());
    }
    if (first.basePrice().compareTo(second.basePrice()) != 0) {
      differences.add("base price changed from " + first.basePrice() + " to " + second.basePrice());
    }
    if (first.minPrice().compareTo(second.minPrice()) != 0) {
      differences.add("min price changed from " + first.minPrice() + " to " + second.minPrice());
    }
    if (first.maxPrice().compareTo(second.maxPrice()) != 0) {
      differences.add("max price changed from " + first.maxPrice() + " to " + second.maxPrice());
    }
    if (first.tickSize().compareTo(second.tickSize()) != 0) {
      differences.add("tick size changed from " + first.tickSize() + " to " + second.tickSize());
    }
    if (first.priceScale() != second.priceScale()) {
      differences.add("price scale changed from " + first.priceScale() + " to " + second.priceScale());
    }
    if (first.currencyScale() != second.currencyScale()) {
      differences.add("currency scale changed from " + first.currencyScale() + " to "
          + second.currencyScale());
    }
    if (first.minQuantity() != second.minQuantity()) {
      differences.add("min quantity changed from " + first.minQuantity() + " to " + second.minQuantity());
    }
    if (first.maxQuantity() != second.maxQuantity()) {
      differences.add("max quantity changed from " + first.maxQuantity() + " to " + second.maxQuantity());
    }
    if (first.discoveryQuantity() != second.discoveryQuantity()) {
      differences.add("discovery quantity changed from " + first.discoveryQuantity() + " to "
          + second.discoveryQuantity());
    }
    return differences;
  }

  private static boolean sameCustodyStructure(MarketDefinition first, MarketDefinition second) {
    return first.assetType() == second.assetType()
        && java.util.Objects.equals(first.item(), second.item())
        && java.util.Objects.equals(first.security(), second.security())
        && sameStructuralRules(first.structural(), second.structural());
  }

  private static boolean sameStructuralRules(MarketDefinition.StructuralRules first,
                                             MarketDefinition.StructuralRules second) {
    return first.currencyId().equals(second.currencyId())
        && first.basePrice().compareTo(second.basePrice()) == 0
        && first.minPrice().compareTo(second.minPrice()) == 0
        && first.maxPrice().compareTo(second.maxPrice()) == 0
        && first.tickSize().compareTo(second.tickSize()) == 0
        && first.priceScale() == second.priceScale()
        && first.currencyScale() == second.currencyScale()
        && first.minQuantity() == second.minQuantity()
        && first.maxQuantity() == second.maxQuantity()
        && first.discoveryQuantity() == second.discoveryQuantity();
  }

  private static boolean feeRatesDiffer(MarketDefinition first, MarketDefinition second) {
    return first.risk().makerFeeRate().compareTo(second.risk().makerFeeRate()) != 0
        || first.risk().takerFeeRate().compareTo(second.risk().takerFeeRate()) != 0;
  }

  static void flushWhileOwned(SingleWriterGuard writer, MarketDataService marketData, Instant at) {
    runWhileOwned(writer, () -> marketData.flush(at));
  }

  static void runWhileOwned(SingleWriterGuard writer, ExchangeRuntime.CheckedRunnable work) {
    try {
      writer.runWhileHeld(work::run);
    } catch (Exception ignored) {
      // The next owned maintenance tick or startup recovery retries durable publication.
    }
  }

  static void runWhileOwnedOrThrow(SingleWriterGuard writer, ExchangeRuntime.CheckedRunnable work)
      throws Exception {
    if (!writer.runWhileHeld(work::run)) {
      throw new IllegalStateException("exchange writer lock is unavailable during final flush");
    }
  }

  static ExchangeRuntime.CheckedRunnable lockLossFence() {
    return () -> {
      // Ownership is already untrusted. Only the runtime's local accepting-writes flag may change;
      // persistent recovery state is established by the next process after it legitimately acquires.
    };
  }

  static Path requireAuditDirectory(Path dataFolder, String configured) {
    if (dataFolder == null || configured == null || configured.isBlank()) {
      throw new IllegalArgumentException("audit export directory is required");
    }
    Path root = dataFolder.toAbsolutePath().normalize();
    if (isWindowsAbsolutePath(configured)) {
      throw new IllegalArgumentException("audit export directory must be relative to addon data");
    }
    Path relative = Path.of(configured);
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException("audit export directory must be relative to addon data");
    }
    Path candidate = root.resolve(relative).normalize();
    if (!candidate.startsWith(root) || candidate.equals(root)) {
      throw new IllegalArgumentException("audit export directory must stay inside addon data");
    }
    return candidate;
  }

  private static boolean isWindowsAbsolutePath(String value) {
    return value.length() >= 3 && Character.isLetter(value.charAt(0))
        && value.charAt(1) == ':'
        && (value.charAt(2) == '/' || value.charAt(2) == '\\');
  }

  static Path requireLocalSqlitePath(Path dataFolder, String jdbcUrl) {
    if (dataFolder == null || jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) {
      throw new IllegalArgumentException("a local SQLite JDBC URL is required");
    }
    String rawPath = jdbcUrl.substring("jdbc:sqlite:".length());
    if (rawPath.isBlank() || rawPath.startsWith("file:") || ":memory:".equals(rawPath)) {
      throw new IllegalArgumentException("SQLite must use a local database file");
    }
    Path root = dataFolder.toAbsolutePath().normalize();
    Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("SQLite database must be inside the addon data folder");
    }
    return candidate;
  }

  private Database database() throws Exception {
    FileConfiguration config = addon.getConfig();
    String mode = config.getString("database.mode", "quickshop");
    if ("sqlite".equalsIgnoreCase(mode)) {
      Path folder = addon.getDataFolder().toPath();
      Files.createDirectories(folder);
      String configured = config.getString("database.sqlite-jdbc-url",
          "jdbc:sqlite:" + folder.resolve("exchange.sqlite").toAbsolutePath());
      Path databaseFile = requireLocalSqlitePath(folder, configured);
      ConnectionProvider connections = new SqliteConnectionProvider(
          () -> java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile));
      return new Database(connections, SqlDialect.SQLITE, new LocalSingleWriterGuard(databaseFile));
    }
    if (!"quickshop".equalsIgnoreCase(mode)) {
      throw new IllegalArgumentException("database.mode must be quickshop or sqlite");
    }
    ConnectionProvider connections = () -> quickShop.getSqlManager().getConnection();
    try (Connection connection = connections.open()) {
      if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
        throw new IllegalStateException(
            "QuickShop Exchange requires MySQL for database.mode=quickshop; use local sqlite otherwise");
      }
    }
    return new Database(connections, SqlDialect.MYSQL,
        new MySqlSingleWriterGuard(connections::open, quickShop.getDbPrefix()));
  }

  private void registerMarkets(ConnectionProvider connections, TableNames tables, MarketRegistry registry)
      throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
        for (String marketId : registry.marketIds()) {
          MarketDefinition definition = registry.require(marketId);
          if (marketExists(connection, tables, marketId)) {
            continue;
          }
          insertMarket(connection, tables, definition, definition.enabled());
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static boolean marketExists(Connection connection, TableNames tables, String marketId)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT market_id FROM " + tables.markets() + " WHERE market_id=?")) {
      query.setString(1, marketId);
      try (ResultSet result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void validateRegisteredMarkets(
      ConnectionProvider connections, TableNames tables, MarketRegistry registry)
      throws SQLException {
    try (Connection connection = connections.open()) {
      for (String marketId : registry.marketIds()) {
        MarketDefinition definition = registry.require(marketId);
        String assetType;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT asset_type FROM " + tables.markets() + " WHERE market_id=?")) {
          query.setString(1, marketId);
          try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
              throw new IllegalStateException(
                  "configured market is missing from the database: " + marketId);
            }
            assetType = result.getString("asset_type");
          }
        }
        if (definition.assetType().name().equals(assetType)) {
          if (definition.assetType() == AssetType.VIRTUAL_SECURITY) {
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT market_id FROM " + tables.securities() + " WHERE market_id=?")) {
              query.setString(1, marketId);
              try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                  throw new IllegalStateException(
                      "virtual security market is missing its security definition: " + marketId);
                }
              }
            }
          }
          continue;
        }
        throw new IllegalStateException(
            "configured market asset type changed: " + marketId + " expected "
                + definition.assetType().name() + " but database has " + assetType);
      }
    }
  }

  static void insertMarket(Connection connection, TableNames tables, MarketDefinition definition,
                           boolean enabled) throws SQLException {
    JdbcExchangeRepository.insertMarket(connection, tables, definition, enabled);
  }

  /** Backwards-compatible helper used by tests that pre-seed disabled markets. */
  static void insertMarket(Connection connection, TableNames tables, MarketDefinition definition)
      throws SQLException {
    insertMarket(connection, tables, definition, definition.enabled());
  }

  private static void recoverMarkets(Map<String, PersistentOrderService> markets)
      throws SQLException {
    for (PersistentOrderService market : markets.values()) {
      market.recoverFromDatabase();
    }
  }

  private static void resumeExpiredHalts(JdbcExchangeRepository repository,
                                          Collection<String> marketIds,
                                          SingleWriterGuard writer) {
    try {
      writer.runWhileHeld(() -> repository.inTransaction(tx -> {
        Instant now = Instant.now();
        for (String marketId : marketIds) {
          MarketState state = tx.marketState(marketId);
          if (state.status() == MarketStatus.HALTED && state.haltedUntil() != null
              && !now.isBefore(state.haltedUntil())) {
            tx.updateMarketState(new MarketState(marketId, MarketStatus.OPEN,
                state.prioritySequence(), state.matchSequence(), state.referencePrice(),
                state.lastPrice(), null, state.discoveryQuantity(), state.circuitBreakerLevel(),
                state.version() + 1), state.version());
          }
        }
        return null;
      }));
    } catch (Exception ignored) {
      // A later maintenance tick retries; CAS versioning prevents stale automatic reopen.
    }
  }

  private ItemStack itemTemplate(MarketDefinition definition) {
    if (definition.assetType() == AssetType.VIRTUAL_SECURITY) {
      throw new IllegalStateException(
          "virtual security markets must not construct item templates: " + definition.marketId());
    }
    if (definition.item().encodedTemplate() != null && !definition.item().encodedTemplate().isBlank()) {
      ItemStack decoded = quickShop.platform().decodeStack(definition.item().encodedTemplate());
      if (decoded == null) {
        throw new IllegalStateException("configured market template cannot be decoded");
      }
      return decoded;
    }
    Material material = Material.matchMaterial(definition.item().material());
    if (material == null || material.isAir()) {
      throw new IllegalStateException("configured market material is invalid");
    }
    return new ItemStack(material);
  }

  private String economyWorld() {
    String world = addon.getConfig().getString("economy.world");
    if (world == null || world.isBlank()) {
      throw new IllegalArgumentException("economy.world is required");
    }
    return world;
  }

  private static MarketRules rules(MarketDefinition definition) {
    MarketDefinition.StructuralRules structural = definition.structural();
    MarketDefinition.RiskRules risk = definition.risk();
    return new MarketRules(definition.marketId(), structural.currencyId(), structural.basePrice(),
        structural.minPrice(), structural.maxPrice(), structural.tickSize(),
        structural.minQuantity(), structural.maxQuantity(), structural.priceScale(),
        risk.makerFeeRate(), risk.takerFeeRate());
  }

  private static RiskLimits limits(MarketDefinition definition) {
    MarketDefinition.RiskRules risk = definition.risk();
    return new RiskLimits(risk.priceCageRatio(), risk.defaultMarketSlippage(),
        risk.maximumMarketSlippage(), risk.levelOneMove(),
        Duration.ofSeconds(risk.levelOneHaltSeconds()), risk.levelTwoMove(),
        Duration.ofSeconds(risk.levelTwoHaltSeconds()));
  }

  static AccountOrderLimits accountLimits(MarketDefinition.RiskRules risk) {
    java.util.Objects.requireNonNull(risk, "risk");
    return new AccountOrderLimits(risk.maxAccountHolding(), risk.maxFrozenCurrency(),
        risk.maxOpenOrders(), risk.operationsPerSecond(), risk.operationsPerMinute());
  }

  private record Database(ConnectionProvider connections, SqlDialect dialect, SingleWriterGuard writer) {}
}
