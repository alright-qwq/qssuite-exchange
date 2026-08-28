package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ExchangeRuntimeFactoryTest {
  @Test
  void acceptsOnlyARegularSQLiteFileUnderTheAddonDataFolder() throws Exception {
    Path dataFolder = Files.createTempDirectory("quickshop-exchange-data-");
    Path database = dataFolder.resolve("exchange.sqlite");

    assertThat(ExchangeRuntimeFactory.requireLocalSqlitePath(
        dataFolder, "jdbc:sqlite:" + database)).isEqualTo(database.toAbsolutePath());
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireLocalSqlitePath(dataFolder, "jdbc:sqlite::memory:"));
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireLocalSqlitePath(dataFolder, "jdbc:sqlite:/tmp/shared.sqlite"));
  }

  @Test
  void confinesAuditExportsToTheAddonDataFolder() throws Exception {
    Path dataFolder = Files.createTempDirectory("quickshop-exchange-data-");

    assertThat(ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, "audit"))
        .isEqualTo(dataFolder.resolve("audit").toAbsolutePath());
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, "../outside"));
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, "C:/outside"));
  }

  @Test
  void maintenanceFlushRunsOnlyInsideTheWriterFence() {
    AtomicBoolean guarded = new AtomicBoolean();
    SingleWriterGuard writer = new SingleWriterGuard() {
      @Override public void acquire() {}
      @Override public boolean held() { return true; }
      @Override public boolean runWhileHeld(GuardedWork work) throws Exception {
        guarded.set(true);
        try {
          work.run();
          return true;
        } finally {
          guarded.set(false);
        }
      }
      @Override public void close() {}
    };
    AtomicBoolean flushed = new AtomicBoolean();

    ExchangeRuntimeFactory.runWhileOwned(writer, () -> {
      assertThat(guarded).isTrue();
      flushed.set(true);
    });

    assertThat(flushed).isTrue();
  }

  @Test
  void finalFlushSkipsGracefullyWhenWriterOwnershipIsUnavailable() {
    SingleWriterGuard writer = new SingleWriterGuard() {
      @Override public void acquire() {}
      @Override public boolean held() { return false; }
      @Override public boolean runWhileHeld(GuardedWork work) { return false; }
      @Override public void close() {}
    };

    ExchangeRuntimeFactory.finalFlushWhileOwned(writer,
        new MarketDataService(new CandleAggregator()), Instant.EPOCH);
  }

  @Test
  void lockLossCallbackIsAWriteFreeLocalFence() throws Exception {
    AtomicBoolean completed = new AtomicBoolean();

    ExchangeRuntime.CheckedRunnable lockLossFence = ExchangeRuntimeFactory.lockLossFence();
    lockLossFence.run();
    completed.set(true);

    assertThat(completed).isTrue();
  }

  @Test
  void reloadRejectsFeeChangesWithoutRestart() {
    MarketDefinition diamond = fixtureDefinition("0.01", "0.001", "0.002");

    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireReloadableStructure(diamond,
            fixtureDefinition("0.01", "0.010", "0.020")))
        .withMessageContaining("fee");
  }

  @Test
  void reloadBlockerDiagnosisNamesTheChangedFieldsPerMarket() {
    MarketDefinition diamond = fixtureDefinition("0.01", "0.001", "0.002");
    MarketDefinition changed = new MarketDefinition(diamond.marketId(), diamond.displayName(),
        diamond.enabled(), diamond.item(),
        new MarketDefinition.StructuralRules("default", new BigDecimal("110.00"),
            BigDecimal.ONE, new BigDecimal("10000.00"), new BigDecimal("0.01"), 2, 2,
            1, 2304, 100),
        diamond.risk(), diamond.blockContainerShops(), diamond.assetType(), diamond.security());

    assertThat(ExchangeRuntimeFactory.describeReloadBlockers(diamond, changed))
        .anyMatch(message -> message.contains("base price changed from 100.00 to 110.00"));
  }

  @Test
  void reloadBlockerDiagnosisIsEmptyForRiskOnlyChanges() {
    MarketDefinition diamond = fixtureDefinition("0.01", "0.001", "0.002");
    MarketDefinition riskOnly = new MarketDefinition(diamond.marketId(), diamond.displayName(),
        diamond.enabled(), diamond.item(), diamond.structural(),
        new MarketDefinition.RiskRules(new BigDecimal("0.001"), new BigDecimal("0.002"),
            new BigDecimal("0.30"), diamond.risk().defaultMarketSlippage(),
            diamond.risk().maximumMarketSlippage(), diamond.risk().levelOneMove(),
            diamond.risk().levelOneHaltSeconds(), diamond.risk().levelTwoMove(),
            diamond.risk().levelTwoHaltSeconds(), diamond.risk().maxAccountHolding(),
            diamond.risk().maxFrozenCurrency(), diamond.risk().maxOpenOrders(),
            diamond.risk().operationsPerSecond(), diamond.risk().operationsPerMinute()),
        diamond.blockContainerShops(), diamond.assetType(), diamond.security());

    assertThat(ExchangeRuntimeFactory.describeReloadBlockers(diamond, riskOnly)).isEmpty();
    assertThat(ExchangeRuntimeFactory.requireReloadableStructure(diamond, riskOnly)).isTrue();
  }

  @Test
  void reloadAllowsRiskOnlyChangesAndIgnoresDecimalScaleDifferences() {
    MarketDefinition diamond = fixtureDefinition("0.01", "0.001", "0.002");
    MarketDefinition riskOnly = fixtureDefinition("0.01",
        "0.0010", "0.0020");
    MarketDefinition.RiskRules oldRisk = riskOnly.risk();
    riskOnly = new MarketDefinition(riskOnly.marketId(), riskOnly.displayName(),
        riskOnly.enabled(), riskOnly.item(), riskOnly.structural(),
        new MarketDefinition.RiskRules(oldRisk.makerFeeRate(), oldRisk.takerFeeRate(),
            new BigDecimal("0.30"), oldRisk.defaultMarketSlippage(),
            oldRisk.maximumMarketSlippage(), oldRisk.levelOneMove(),
            oldRisk.levelOneHaltSeconds(), oldRisk.levelTwoMove(),
            oldRisk.levelTwoHaltSeconds(), oldRisk.maxAccountHolding(),
            oldRisk.maxFrozenCurrency(), oldRisk.maxOpenOrders(),
            oldRisk.operationsPerSecond(), oldRisk.operationsPerMinute()),
        riskOnly.blockContainerShops(), riskOnly.assetType(), riskOnly.security());

    assertThat(ExchangeRuntimeFactory.requireReloadableStructure(diamond, riskOnly)).isTrue();
  }

  @Test
  void mapsConfiguredAccountRiskLimitsIntoTheProductionService() {
    MarketDefinition.RiskRules rules = new MarketDefinition.RiskRules(
        new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("0.20"),
        new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.10"), 120L,
        new BigDecimal("0.20"), 600L, 321L, new BigDecimal("456.78"), 9, 3, 17);

    var limits = ExchangeRuntimeFactory.accountLimits(rules);

    assertThat(limits.maximumHolding()).isEqualTo(321L);
    assertThat(limits.maximumFrozenCurrency()).isEqualByComparingTo("456.78");
    assertThat(limits.maximumOpenOrders()).isEqualTo(9);
    assertThat(limits.operationsPerSecond()).isEqualTo(3);
    assertThat(limits.operationsPerMinute()).isEqualTo(17);
  }

  @Test
  void startupWarnsAboutDatabaseMarketsMissingFromConfig() throws Exception {
    Path databaseFile = Files.createTempFile("quickshop-exchange-orphan-", ".sqlite");
    ConnectionProvider connections = SqliteTestDatabase.at(databaseFile);
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketDefinition configured = fixtureDefinition("0.01", "0.001", "0.002");
    MarketDefinition orphaned = new MarketDefinition("orphaned_market", "Orphaned", false,
        configured.item(), configured.structural(), configured.risk(), false);
    try (Connection connection = connections.open()) {
      JdbcExchangeRepository.insertMarket(connection, tables, configured, false);
      JdbcExchangeRepository.insertMarket(connection, tables, orphaned, false);
    }
    MarketRegistry registry = new MarketRegistry(
        Map.of(configured.marketId(), configured));

    List<String> warnings = new ArrayList<>();
    Logger logger = Logger.getLogger("test.quickshop-exchange-orphan");
    logger.setUseParentHandlers(false);
    logger.addHandler(new Handler() {
      @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
      @Override public void flush() {}
      @Override public void close() {}
    });

    ExchangeRuntimeFactory.warnAboutOrphanedMarkets(connections, tables, registry, logger);

    assertThat(warnings)
        .anyMatch(message -> message.contains("orphaned_market")
            && message.contains("missing from markets.yml"));
  }

  private static MarketDefinition fixtureDefinition(String tickSize, String makerRate,
                                                    String takerRate) {
    return new MarketDefinition("diamond", "Diamond", false,
        new com.ghostchu.quickshop.addon.exchange.config.MarketDefinition.ItemDefinition(
            com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode.VANILLA_MATERIAL,
            "DIAMOND", null, null),
        new MarketDefinition.StructuralRules("default", new BigDecimal("100.00"),
            BigDecimal.ONE, new BigDecimal("10000.00"), new BigDecimal(tickSize), 2, 2,
            1, 2304, 100),
        new MarketDefinition.RiskRules(new BigDecimal(makerRate), new BigDecimal(takerRate),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
            new BigDecimal("10000000.00"), 100, 5, 60), false);
  }
}
