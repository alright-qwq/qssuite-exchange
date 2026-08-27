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
import com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.PlayerOperationSerialiser;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService;
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
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Production composition root for the exchange's recoverable single-writer runtime. */
public final class ExchangeRuntimeFactory {
  private final JavaPlugin addon;
  private final QuickShop quickShop;
  private volatile Map<String, PersistentOrderService> markets;
  private volatile MarketDataService marketData;
  private volatile MarketRegistry registry;

  public ExchangeRuntimeFactory(JavaPlugin addon, QuickShop quickShop) {
    this.addon = java.util.Objects.requireNonNull(addon, "addon");
    this.quickShop = java.util.Objects.requireNonNull(quickShop, "quickShop");
  }

  public ExchangeRuntime create() throws Exception {
    Database database = database();
    database.writer().acquire();
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
    MoneyTransferService moneyTransfers = new MoneyTransferService(repository, repository,
        new QuickShopEconomyGateway(quickShop, economyWorld()), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ItemTransferService itemTransfers = new ItemTransferService(repository, repository, inventory,
        marketId -> itemTemplate(registry.require(marketId)), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ExchangeActionService actions = new ExchangeActionService(
        markets, moneyTransfers, itemTransfers,
        marketId -> registry.require(marketId).assetType() == AssetType.VIRTUAL_SECURITY);
    DrainingExecutor recoveryExecutor = new DrainingExecutor(
        "qs-exchange-recovery-", Duration.ofSeconds(30));
    DrainingExecutor recoveryFenceExecutor = new DrainingExecutor(
        "qs-exchange-recovery-fence-", Duration.ofSeconds(30));
    TransferRecoveryService transfers = new TransferRecoveryService(
        repository, repository, inventory, recoveryExecutor);
    Bukkit.getPluginManager().registerEvents(new ContainerShopPolicyListener(registry), addon);

    AutoCloseable dispatcher = () -> {};
    ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("qs-exchange-maintenance-", 0).factory());
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
    ExchangeViewService views = new ExchangeViewService(marketViews, marketData, maintenance,
        repository, java.util.List.copyOf(transferTargets.values()),
        java.time.Duration.ofMillis(guiRefreshMs));
    java.nio.file.Path auditDirectory = requireAuditDirectory(
        addon.getDataFolder().toPath(),
        addon.getConfig().getString("operations.audit-export-directory", "audit"));
    AdminExchangeService administration = new AdminExchangeService(
        markets, repository, new com.ghostchu.quickshop.addon.exchange.operations.AuditExporter(),
        auditDirectory, new SecurityService(repository), inventory, metrics);
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
    maintenance.scheduleWithFixedDelay(detectSuspiciousTrading, 2L, 5L, TimeUnit.MINUTES);
    maintenance.scheduleWithFixedDelay(resumeHalted, 1L, 1L, TimeUnit.MINUTES);
    maintenance.scheduleWithFixedDelay(() -> flushWhileOwned(
        database.writer(), marketData, Instant.now()), 1L, 1L, TimeUnit.MINUTES);
    int candleRetentionDays = Math.max(1, addon.getConfig().getInt(
        "market-data.candle-retention-days", 365));
    java.util.List<String> marketIds = java.util.List.copyOf(registry.marketIds());
    maintenance.scheduleWithFixedDelay(() -> runWhileOwned(
        database.writer(), () -> marketData.purgeOldCandles(
            java.time.Duration.ofDays(candleRetentionDays), marketIds)),
        30L, 24L * 60L, TimeUnit.MINUTES);
    int reconciliationIntervalMinutes = addon.getConfig().getInt(
        "operations.reconciliation-interval-minutes", 1440);
    if (reconciliationIntervalMinutes > 0) {
      maintenance.scheduleWithFixedDelay(() -> runWhileOwned(
          database.writer(), () -> {
            com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport report =
                repository.reconcile();
            if (!report.balanced()) {
              addon.getLogger().warning("Exchange reconciliation detected differences: "
                  + report);
            }
          }), 15L, reconciliationIntervalMinutes, TimeUnit.MINUTES);
    }
    maintenance.scheduleWithFixedDelay(marketData::publishPlayerUpdates,
        1L, 1L, TimeUnit.SECONDS);

      ExchangeRuntime runtime = new ExchangeRuntime(database.writer(),
          () -> recoverMarkets(markets), transfers::recoverAllMoneyTransfers, dispatcher,
          lockLossFence(),
          () -> {
            maintenance.shutdownNow();
            recoveryFenceExecutor.close();
            recoveryExecutor.close();
            playerOperations.close();
            runWhileOwnedOrThrow(database.writer(), () -> marketData.flush(Instant.now()));
          }, views, administration, actions);
      Bukkit.getPluginManager().registerEvents(new TransferLoginListener(accountId ->
          runtime.callAsyncWhileWriting(() -> transfers.recoverPlayer(accountId),
              recoveryFenceExecutor)), addon);
      return runtime;
    } catch (Exception failure) {
      database.writer().close();
      throw failure;
    }
  }

  /** Re-reads markets.yml/config.yml and hot-applies operational risk settings. */
  public void reloadConfig() {
    Map<String, PersistentOrderService> liveMarkets = this.markets;
    MarketRegistry liveRegistry = this.registry;
    if (liveMarkets == null || liveRegistry == null) {
      throw new IllegalStateException("exchange runtime is not started");
    }
    File reloadConfig = new File(addon.getDataFolder(), "config.yml");
    File reloadMarkets = new File(addon.getDataFolder(), "markets.yml");
    MarketRegistry reloaded = MarketRegistry.load(reloadConfig, reloadMarkets);
    if (!liveMarkets.keySet().equals(reloaded.marketIds())) {
      throw new IllegalStateException(
          "market set cannot change during reload; pause markets and restart to apply structural changes");
    }
    for (String marketId : liveMarkets.keySet()) {
      MarketDefinition current = liveRegistry.require(marketId);
      MarketDefinition next = reloaded.require(marketId);
      requireReloadableStructure(current, next);
    }
    // The structure is unchanged, so the state reader is never consulted: reload only advances
    // risk/fee versions, persists them atomically and swaps the live definitions in one step.
    liveRegistry.reload(reloaded.definitions(),
        ignored -> new MarketStateReader.State(MarketStatus.PAUSED, 0));
    for (String marketId : liveMarkets.keySet()) {
      MarketDefinition next = liveRegistry.require(marketId);
      PersistentOrderService service = liveMarkets.get(marketId);
      service.updateRiskLimits(limits(next), accountLimits(next.risk()));
    }
    this.registry = liveRegistry;
  }

  static boolean requireReloadableStructure(
      MarketDefinition current, MarketDefinition next) {
    if (!sameCustodyStructure(current, next) || feeRatesDiffer(current, next)) {
      throw new IllegalArgumentException(
          "structural or fee changes require a paused market with no open orders;"
              + " apply them manually or restart the server");
    }
    return true;
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
          insertMarket(connection, tables, definition);
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

  private static void insertMarket(Connection connection, TableNames tables, MarketDefinition definition)
      throws SQLException {
    MarketRules rules = rules(definition);
    boolean virtual = definition.assetType() == AssetType.VIRTUAL_SECURITY;
    try (PreparedStatement market = connection.prepareStatement(
        "INSERT INTO " + tables.markets()
            + " (market_id,currency_id,item_fingerprint,item_template,asset_type,structural_payload,"
            + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
         PreparedStatement state = connection.prepareStatement(
             "INSERT INTO " + tables.marketState()
                 + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                 + "last_price,halted_until,discovery_quantity,circuit_breaker_level,version)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?)");
         PreparedStatement security = virtual ? connection.prepareStatement(
             "INSERT INTO " + tables.securities()
                 + " (market_id,symbol,name,description,currency_id,base_price,total_supply,"
                 + "issued_supply,minimum_unit,status,recovery_account,created_at,updated_at,version)"
                 + " VALUES (?,?,?,?,?,?,?,0,?,?,NULL,?,?,0)")
             : null) {
      market.setString(1, definition.marketId());
      market.setString(2, definition.structural().currencyId());
      if (virtual) {
        market.setString(3, "");
        market.setString(4, "");
      } else {
        market.setString(3, definition.item().fingerprint() == null
            ? definition.item().material() : definition.item().fingerprint());
        market.setString(4, Optional.ofNullable(definition.item().encodedTemplate()).orElse(""));
      }
      market.setString(5, definition.assetType().name());
      market.setString(6, "{}");
      market.setString(7, "{\"makerFeeRate\":\"" + rules.makerFeeRate().toPlainString()
          + "\",\"takerFeeRate\":\"" + rules.takerFeeRate().toPlainString()
          + "\",\"currencyScale\":" + definition.structural().currencyScale() + "}");
      market.setString(8, "{}");
      market.setLong(9, 1L);
      market.setLong(10, 1L);
      market.setLong(11, Instant.now().toEpochMilli());
      market.executeUpdate();

      state.setString(1, definition.marketId());
      state.setString(2, definition.enabled() ? MarketStatus.OPEN.name() : MarketStatus.CLOSED.name());
      state.setLong(3, 0L);
      state.setLong(4, 0L);
      state.setString(5, rules.basePrice().toPlainString());
      state.setNull(6, Types.DECIMAL);
      state.setNull(7, Types.BIGINT);
      state.setLong(8, 0L);
      state.setInt(9, 0);
      state.setLong(10, 0L);
      state.executeUpdate();

      if (virtual) {
        security.setString(1, definition.marketId());
        security.setString(2, definition.security().symbol());
        security.setString(3, definition.security().name());
        security.setString(4, definition.security().description());
        security.setString(5, definition.security().currencyId());
        security.setString(6, rules.basePrice().toPlainString());
        security.setLong(7, definition.security().totalSupply());
        security.setLong(8, definition.security().minimumUnit());
        security.setString(9, definition.enabled() ? "OPEN" : "CLOSED");
        security.setLong(10, Instant.now().toEpochMilli());
        security.setLong(11, Instant.now().toEpochMilli());
        security.executeUpdate();
      }
    }
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
