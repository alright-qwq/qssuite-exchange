package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.Material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRegistryTest {
  @Test
  void loadsConfirmedRiskDefaults() {
    MarketRegistry registry = new MarketRegistry(Map.of("minecraft_diamond/default", definition("0.01")));

    MarketDefinition diamond = registry.require("minecraft_diamond/default");

    assertThat(diamond.risk().priceCageRatio()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().defaultMarketSlippage()).isEqualByComparingTo("0.05");
    assertThat(diamond.risk().maximumMarketSlippage()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().operationsPerSecond()).isEqualTo(5);
    assertThat(diamond.risk().operationsPerMinute()).isEqualTo(60);
  }

  @Test
  void loadsConfirmedRiskDefaultsFromBundledYaml() throws Exception {
    File config = new File(getClass().getClassLoader().getResource("config.yml").toURI());
    File markets = new File(getClass().getClassLoader().getResource("markets.yml").toURI());

    MarketDefinition diamond = MarketRegistry.load(config, markets)
        .require("minecraft_diamond/default");

    assertThat(diamond.risk().priceCageRatio()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().defaultMarketSlippage()).isEqualByComparingTo("0.05");
    assertThat(diamond.risk().maximumMarketSlippage()).isEqualByComparingTo("0.20");
  }

  @Test
  void structuralReloadRequiresPausedEmptyBook() {
    MarketRegistry registry = new MarketRegistry(Map.of("minecraft_diamond/default", definition("0.01")));
    MarketStateReader state = market -> new MarketStateReader.State(MarketStatus.OPEN, 3);

    assertThatThrownBy(() -> registry.reload(
        Map.of("minecraft_diamond/default", definition("0.02")), state))
        .hasMessageContaining("structural change requires PAUSED market with no open orders");
  }

  @Test
  void feeReloadAppendsAnImmutableVersion() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "minecraft_diamond/default", definition("0.01", "0.001", "0.002")));

    registry.reload(Map.of("minecraft_diamond/default",
        definition("0.01", "0.010", "0.020")),
        market -> new MarketStateReader.State(MarketStatus.OPEN, 3));

    MarketRegistry.FeeSchedule schedule = registry.feeSchedule("minecraft_diamond/default");
    assertThat(schedule.activeVersion()).isEqualTo(2);
    assertThat(schedule.versions()).containsOnlyKeys(1L, 2L);
    assertThat(schedule.versions().get(1L).makerRate()).isEqualByComparingTo("0.001");
    assertThat(schedule.versions().get(2L).makerRate()).isEqualByComparingTo("0.010");
  }

  @Test
  void riskOnlyReloadAdvancesRiskVersionWithoutConsultingState() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "minecraft_diamond/default", definition("0.01")));
    boolean[] stateRead = {false};
    MarketStateReader state = market -> {
      stateRead[0] = true;
      return new MarketStateReader.State(MarketStatus.OPEN, 3);
    };

    registry.reload(Map.of("minecraft_diamond/default",
        withRisk(definition("0.01"), new BigDecimal("0.30"))), state);

    assertThat(stateRead[0]).isFalse();
    assertThat(registry.require("minecraft_diamond/default").risk().priceCageRatio())
        .isEqualByComparingTo("0.30");
    assertThat(registry.versions("minecraft_diamond/default"))
        .isEqualTo(new MarketRegistry.Versions(1, 2, 1));
  }

  @Test
  void equalDecimalsWithDifferentScalesReloadWithoutVersionChange() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "minecraft_diamond/default", definition("0.01", "0.001", "0.002")));
    MarketDefinition sameValue = definition("0.01",
        new BigDecimal("0.0010").toString(), new BigDecimal("0.0020").toString());

    registry.reload(Map.of("minecraft_diamond/default", sameValue),
        market -> new MarketStateReader.State(MarketStatus.OPEN, 3));

    assertThat(registry.versions("minecraft_diamond/default"))
        .isEqualTo(new MarketRegistry.Versions(1, 1, 1));
  }

  @Test
  void rejectsTickSizeBeyondConfiguredPriceScale() {
    assertThatThrownBy(() -> definition("0.001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceScale");
  }

  @Test
  void doesNotPublishAnyCandidateWhenAtomicPersistenceFails() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "diamond", definition("diamond", "0.01", "0.001", "0.002", 2),
        "emerald", definition("emerald", "0.01", "0.001", "0.002", 2)),
        states -> { throw new IllegalStateException("database unavailable"); });

    assertThatThrownBy(() -> registry.reload(Map.of(
        "diamond", definition("diamond", "0.02", "0.001", "0.002", 2),
        "emerald", definition("emerald", "0.02", "0.001", "0.002", 2)),
        market -> new MarketStateReader.State(MarketStatus.PAUSED, 0)))
        .hasMessageContaining("database unavailable");

    assertThat(registry.require("diamond").structural().tickSize())
        .isEqualByComparingTo("0.01");
    assertThat(registry.require("emerald").structural().tickSize())
        .isEqualByComparingTo("0.01");
    assertThat(registry.versions("diamond")).isEqualTo(new MarketRegistry.Versions(1, 1, 1));
    assertThat(registry.versions("emerald")).isEqualTo(new MarketRegistry.Versions(1, 1, 1));
  }

  @Test
  void blocksOnlyNewContainerShopsForConfiguredVanillaMaterials() {
    MarketDefinition protectedDiamond = new MarketDefinition("diamond", "Diamond", false,
        new MarketDefinition.ItemDefinition(FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        definition("diamond", "0.01", "0.001", "0.002", 2).structural(),
        definition("diamond", "0.01", "0.001", "0.002", 2).risk(), true);
    MarketRegistry registry = new MarketRegistry(Map.of("diamond", protectedDiamond));

    assertThat(registry.blocksContainerShop(Material.DIAMOND)).isTrue();
    assertThat(registry.blocksContainerShop(Material.EMERALD)).isFalse();
  }

  @Test
  void loadsVirtualSecurityMarketFromYaml(@TempDir Path temp) throws Exception {
    Path config = temp.resolve("config.yml");
    Path markets = temp.resolve("markets.yml");
    Files.writeString(config, riskDefaultsYaml());
    Files.writeString(markets, virtualMarketYaml(false));

    MarketRegistry registry = MarketRegistry.load(config.toFile(), markets.toFile());
    MarketDefinition alpha = registry.require("concept_alpha");

    assertThat(alpha.assetType()).isEqualTo(AssetType.VIRTUAL_SECURITY);
    assertThat(alpha.item()).isNull();
    assertThat(alpha.security().symbol()).isEqualTo("ALPHA");
    assertThat(alpha.security().totalSupply()).isEqualTo(1000);
    assertThat(alpha.security().minimumUnit()).isEqualTo(1);
  }

  @Test
  void loadsDisabledExampleStockFromBundledYaml() throws Exception {
    File config = new File(getClass().getClassLoader().getResource("config.yml").toURI());
    File markets = new File(getClass().getClassLoader().getResource("markets.yml").toURI());

    MarketRegistry registry = MarketRegistry.load(config, markets);
    MarketDefinition alpha = registry.require("concept_alpha");

    assertThat(alpha.assetType()).isEqualTo(AssetType.VIRTUAL_SECURITY);
    assertThat(alpha.enabled()).isFalse();
    assertThat(alpha.security().symbol()).isEqualTo("ALPHA");
    assertThat(alpha.security().totalSupply()).isEqualTo(1000);
  }

  @Test
  void rejectsVirtualSecurityMarketWithItemSection(@TempDir Path temp) throws Exception {
    Path config = temp.resolve("config.yml");
    Path markets = temp.resolve("markets.yml");
    Files.writeString(config, riskDefaultsYaml());
    Files.writeString(markets, virtualMarketYaml(true));

    assertThatThrownBy(() -> MarketRegistry.load(config.toFile(), markets.toFile()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not define an item");
  }

  @Test
  void blocksContainerShopIgnoresVirtualSecurityMarkets() {
    MarketRegistry registry = new MarketRegistry(Map.of("concept_alpha", virtualDefinition("ALPHA")));

    assertThat(registry.blocksContainerShop(Material.DIAMOND)).isFalse();
  }

  @Test
  void reloadVirtualSecurityMarketComparesSecurityMetadata() {
    MarketRegistry registry = new MarketRegistry(Map.of("concept_alpha", virtualDefinition("ALPHA")));
    MarketStateReader state = market -> new MarketStateReader.State(MarketStatus.PAUSED, 0);

    registry.reload(Map.of("concept_alpha", virtualDefinition("ALPHA")), state);

    assertThat(registry.require("concept_alpha").security().symbol()).isEqualTo("ALPHA");
    assertThat(registry.versions("concept_alpha")).isEqualTo(new MarketRegistry.Versions(1, 1, 1));
  }

  private static MarketDefinition withRisk(MarketDefinition definition, BigDecimal priceCageRatio) {
    MarketDefinition.RiskRules risk = definition.risk();
    return new MarketDefinition(definition.marketId(), definition.displayName(),
        definition.enabled(), definition.item(), definition.structural(),
        new MarketDefinition.RiskRules(risk.makerFeeRate(), risk.takerFeeRate(),
            priceCageRatio, risk.defaultMarketSlippage(), risk.maximumMarketSlippage(),
            risk.levelOneMove(), risk.levelOneHaltSeconds(), risk.levelTwoMove(),
            risk.levelTwoHaltSeconds(), risk.maxAccountHolding(), risk.maxFrozenCurrency(),
            risk.maxOpenOrders(), risk.operationsPerSecond(), risk.operationsPerMinute()),
        definition.blockContainerShops(), definition.assetType(), definition.security());
  }

  private static MarketDefinition definition(String tickSize) {
    return definition(tickSize, "0.001", "0.002");
  }

  private static MarketDefinition definition(
      String tickSize, String makerFeeRate, String takerFeeRate) {
    return definition("minecraft_diamond/default", tickSize, makerFeeRate, takerFeeRate, 2);
  }

  private static MarketDefinition definition(
      String marketId, String tickSize, String makerFeeRate, String takerFeeRate,
      int currencyScale) {
    return new MarketDefinition(marketId, "Diamond", false,
        new MarketDefinition.ItemDefinition(FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        new MarketDefinition.StructuralRules("default", new BigDecimal("100.00"),
            BigDecimal.ONE, new BigDecimal("10000.00"), new BigDecimal(tickSize), 2, currencyScale,
            1, 2304, 100),
        new MarketDefinition.RiskRules(new BigDecimal(makerFeeRate), new BigDecimal(takerFeeRate),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
        new BigDecimal("10000000.00"), 100, 5, 60), false);
  }

  private static MarketDefinition virtualDefinition(String symbol) {
    return new MarketDefinition("concept_alpha", "Alpha", false, null,
        new MarketDefinition.StructuralRules("default", new BigDecimal("10.00"),
            BigDecimal.ONE, new BigDecimal("100.00"), new BigDecimal("0.01"), 2, 2,
            1, 1000, 100),
        new MarketDefinition.RiskRules(new BigDecimal("0.001"), new BigDecimal("0.002"),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
            new BigDecimal("10000000.00"), 100, 5, 60), false,
        AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition(symbol, "Alpha Holdings", "Concept stock", "default",
            new BigDecimal("10.00"), 1000, 1));
  }

  private static String riskDefaultsYaml() {
    return """
        risk-defaults:
          price-cage-ratio: '0.20'
          default-market-slippage: '0.05'
          maximum-market-slippage: '0.20'
          level-one-move: '0.10'
          level-one-halt-seconds: 120
          level-two-move: '0.20'
          level-two-halt-seconds: 600
          operations-per-second: 5
          operations-per-minute: 60
        """;
  }

  private static String virtualMarketYaml(boolean withItem) {
    String itemSection = withItem
        ? "    item:\n      mode: VANILLA_MATERIAL\n      material: DIAMOND\n"
        : "";
    return "markets:\n"
        + "  concept_alpha:\n"
        + "    enabled: false\n"
        + "    display-name: Concept Alpha\n"
        + itemSection
        + "    security:\n"
        + "      symbol: ALPHA\n"
        + "      name: Alpha Holdings\n"
        + "      description: Pure ledger concept stock\n"
        + "      base-price: '10.00'\n"
        + "      total-supply: 1000\n"
        + "      minimum-unit: 1\n"
        + "    currency: default\n"
        + "    base-price: '10.00'\n"
        + "    min-price: '1.00'\n"
        + "    max-price: '100.00'\n"
        + "    tick-size: '0.01'\n"
        + "    price-scale: 2\n"
        + "    currency-scale: 2\n"
        + "    min-quantity: 1\n"
        + "    max-quantity: 1000\n"
        + "    discovery-quantity: 100\n"
        + "    maker-fee-rate: '0.001'\n"
        + "    taker-fee-rate: '0.002'\n"
        + "    max-account-holding: 100000\n"
        + "    max-frozen-currency: '10000000.00'\n"
        + "    max-open-orders: 100\n"
        + "    block-container-shops: false\n";
  }
}
