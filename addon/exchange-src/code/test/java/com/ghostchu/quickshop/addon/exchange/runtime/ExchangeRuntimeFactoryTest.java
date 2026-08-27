package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
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
  void finalFlushFailsWhenWriterOwnershipIsUnavailable() {
    SingleWriterGuard writer = new SingleWriterGuard() {
      @Override public void acquire() {}
      @Override public boolean held() { return false; }
      @Override public boolean runWhileHeld(GuardedWork work) { return false; }
      @Override public void close() {}
    };

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        ExchangeRuntimeFactory.runWhileOwnedOrThrow(writer, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writer lock");
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
}
