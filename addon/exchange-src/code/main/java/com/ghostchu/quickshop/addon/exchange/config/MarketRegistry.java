package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.io.File;
import java.math.BigDecimal;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import java.util.Collections;

/** Holds market configuration and only permits structural changes on a paused empty book. */
public final class MarketRegistry {
  private final Map<String, Entry> markets = new LinkedHashMap<>();
  private final MarketConfigurationPersistence persistence;

  public MarketRegistry(Map<String, MarketDefinition> definitions) {
    this(definitions, MarketConfigurationPersistence.NONE);
  }

  public MarketRegistry(
      Map<String, MarketDefinition> definitions, MarketConfigurationPersistence persistence) {
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    replaceInitial(definitions);
    restorePersisted(persistence.load(Set.copyOf(markets.keySet())));
  }

  public static MarketRegistry load(File configurationFile, File marketsFile) {
    return load(configurationFile, marketsFile, MarketConfigurationPersistence.NONE);
  }

  public static MarketRegistry load(
      File configurationFile, File marketsFile, MarketConfigurationPersistence persistence) {
    YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configurationFile);
    YamlConfiguration markets = YamlConfiguration.loadConfiguration(marketsFile);
    ConfigurationSection riskDefaults = requiredSection(configuration, "risk-defaults");
    ConfigurationSection configuredMarkets = requiredSection(markets, "markets");
    Map<String, MarketDefinition> definitions = new LinkedHashMap<>();
    for (String marketId : configuredMarkets.getKeys(false)) {
      ConfigurationSection market = requiredSection(configuredMarkets, marketId);
      ConfigurationSection item = market.getConfigurationSection("item");
      ConfigurationSection security = market.getConfigurationSection("security");
      if (security == null && item == null) {
        throw new IllegalArgumentException("missing configuration section: item");
      }
      if (security != null && item != null) {
        throw new IllegalArgumentException("virtual security market must not define an item");
      }
      MarketDefinition.ItemDefinition itemDefinition = null;
      if (item != null) {
        itemDefinition = new MarketDefinition.ItemDefinition(
            FingerprintMode.valueOf(requiredString(item, "mode")),
            requiredString(item, "material"), item.getString("encoded-template"),
            item.getString("fingerprint"));
      }
      definitions.put(marketId, new MarketDefinition(marketId,
          requiredString(market, "display-name"), market.getBoolean("enabled"),
          itemDefinition, structuralRules(market), riskRules(market, riskDefaults),
          market.getBoolean("block-container-shops"),
          security == null ? AssetType.PHYSICAL_ITEM : AssetType.VIRTUAL_SECURITY,
          security == null ? null : new SecurityDefinition(
              requiredString(security, "symbol"), requiredString(security, "name"),
              requiredString(security, "description"), requiredString(market, "currency"),
              decimal(security, "base-price"), security.getLong("total-supply"),
              security.getLong("minimum-unit"))));
    }
    return new MarketRegistry(definitions, persistence);
  }

  public synchronized MarketDefinition require(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return entry.definition;
  }

  public synchronized Set<String> marketIds() {
    return Set.copyOf(markets.keySet());
  }

  /** Returns an immutable snapshot of the currently active definitions. */
  public synchronized Map<String, MarketDefinition> definitions() {
    Map<String, MarketDefinition> copy = new LinkedHashMap<>();
    markets.forEach((marketId, entry) -> copy.put(marketId, entry.definition));
    return Map.copyOf(copy);
  }

  /** Returns whether a configured exchange-only vanilla item may not create a new container shop. */
  public synchronized boolean blocksContainerShop(Material material) {
    if (material == null) {
      return false;
    }
    return markets.values().stream().map(entry -> entry.definition)
        .filter(definition -> definition.item() != null)
        .anyMatch(definition -> definition.blockContainerShops()
            && definition.item().mode() == FingerprintMode.VANILLA_MATERIAL
            && material.name().equalsIgnoreCase(definition.item().material()));
  }

  public synchronized Versions versions(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return new Versions(entry.structuralVersion, entry.riskVersion, entry.feeVersion);
  }

  public synchronized FeeSchedule feeSchedule(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return new FeeSchedule(entry.feeVersion,
        Collections.unmodifiableMap(new LinkedHashMap<>(entry.feeSchedule)));
  }

  public synchronized void reload(
      Map<String, MarketDefinition> replacements, MarketStateReader stateReader) {
    Objects.requireNonNull(replacements, "replacements");
    Objects.requireNonNull(stateReader, "stateReader");
    if (!markets.keySet().equals(replacements.keySet())) {
      throw new IllegalArgumentException("market set cannot change during reload");
    }
    Map<String, Entry> candidates = new LinkedHashMap<>();
    Map<String, MarketConfigurationPersistence.State> persisted = new LinkedHashMap<>();
    for (Map.Entry<String, MarketDefinition> replacement : replacements.entrySet()) {
      Entry current = markets.get(replacement.getKey());
      Entry candidate = new Entry(current);
      MarketDefinition next = replacement.getValue();
      boolean versionChanged = false;
      if (!sameCustodyStructure(current.definition, next)) {
        MarketStateReader.State state = stateReader.read(replacement.getKey());
        if (state.status() != MarketStatus.PAUSED || state.openOrders() != 0) {
          throw new IllegalStateException(
              "structural change requires PAUSED market with no open orders");
        }
        candidate.structuralVersion++;
        versionChanged = true;
      }
      if (!current.definition.risk().equals(next.risk())) {
        candidate.riskVersion++;
        versionChanged = true;
        if (current.definition.risk().makerFeeRate().compareTo(next.risk().makerFeeRate()) != 0
            || current.definition.risk().takerFeeRate().compareTo(next.risk().takerFeeRate()) != 0) {
          candidate.feeVersion++;
          candidate.feeSchedule.put(candidate.feeVersion, feeRates(next));
        }
      }
      candidate.definition = next;
      candidates.put(replacement.getKey(), candidate);
      if (versionChanged) {
        persisted.put(replacement.getKey(), candidate.persistedState());
      }
    }
    if (!persisted.isEmpty()) {
      persistence.persist(Map.copyOf(persisted));
    }
    markets.clear();
    markets.putAll(candidates);
  }

  private void replaceInitial(Map<String, MarketDefinition> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      throw new IllegalArgumentException("at least one market is required");
    }
    definitions.forEach((marketId, definition) -> {
      if (!marketId.equals(definition.marketId())) {
        throw new IllegalArgumentException("market key does not match definition");
      }
      markets.put(marketId, new Entry(definition));
    });
  }

  private void restorePersisted(Map<String, MarketConfigurationPersistence.State> persisted) {
    Objects.requireNonNull(persisted, "persisted");
    if (!markets.keySet().containsAll(persisted.keySet())) {
      throw new IllegalArgumentException("persisted configuration contains an unknown market");
    }
    persisted.forEach((marketId, state) -> markets.get(marketId).restore(state));
  }

  private static ConfigurationSection requiredSection(ConfigurationSection parent, String path) {
    ConfigurationSection section = parent.getConfigurationSection(path);
    if (section == null) {
      throw new IllegalArgumentException("missing configuration section: " + path);
    }
    return section;
  }

  private static String requiredString(ConfigurationSection section, String path) {
    String value = section.getString(path);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing configuration value: " + path);
    }
    return value;
  }

  private static BigDecimal decimal(ConfigurationSection section, String path) {
    return new BigDecimal(requiredString(section, path));
  }

  private static MarketDefinition.StructuralRules structuralRules(
      ConfigurationSection market) {
    return new MarketDefinition.StructuralRules(requiredString(market, "currency"),
        decimal(market, "base-price"), decimal(market, "min-price"),
        decimal(market, "max-price"), decimal(market, "tick-size"),
        market.getInt("price-scale"), market.getInt("currency-scale"),
        market.getLong("min-quantity"), market.getLong("max-quantity"),
        market.getLong("discovery-quantity"));
  }

  private static MarketDefinition.RiskRules riskRules(
      ConfigurationSection market, ConfigurationSection riskDefaults) {
    return new MarketDefinition.RiskRules(decimal(market, "maker-fee-rate"),
        decimal(market, "taker-fee-rate"), decimal(riskDefaults, "price-cage-ratio"),
        decimal(riskDefaults, "default-market-slippage"),
        decimal(riskDefaults, "maximum-market-slippage"),
        decimal(riskDefaults, "level-one-move"), riskDefaults.getLong("level-one-halt-seconds"),
        decimal(riskDefaults, "level-two-move"), riskDefaults.getLong("level-two-halt-seconds"),
        market.getLong("max-account-holding"), decimal(market, "max-frozen-currency"),
        market.getInt("max-open-orders"), riskDefaults.getInt("operations-per-second"),
        riskDefaults.getInt("operations-per-minute"));
  }

  private static boolean sameCustodyStructure(MarketDefinition first, MarketDefinition second) {
    return first.assetType() == second.assetType()
        && Objects.equals(first.item(), second.item())
        && Objects.equals(first.security(), second.security())
        && first.structural().equals(second.structural());
  }

  public record Versions(long structuralVersion, long riskVersion, long feeVersion) {
  }

  public record FeeSchedule(long activeVersion, Map<Long, FeeRates> versions) {
  }

  private static FeeRates feeRates(MarketDefinition definition) {
    return new FeeRates(definition.risk().makerFeeRate(), definition.risk().takerFeeRate());
  }

  private static final class Entry {
    private MarketDefinition definition;
    private long structuralVersion = 1;
    private long riskVersion = 1;
    private long feeVersion = 1;
    private final Map<Long, FeeRates> feeSchedule = new LinkedHashMap<>();

    private Entry(MarketDefinition definition) {
      this.definition = definition;
      this.feeSchedule.put(feeVersion, feeRates(definition));
    }

    private Entry(Entry source) {
      this.definition = source.definition;
      this.structuralVersion = source.structuralVersion;
      this.riskVersion = source.riskVersion;
      this.feeVersion = source.feeVersion;
      this.feeSchedule.putAll(source.feeSchedule);
    }

    private MarketConfigurationPersistence.State persistedState() {
      return new MarketConfigurationPersistence.State(
          structuralVersion, riskVersion, feeVersion,
          definition.structural().currencyScale(), feeSchedule);
    }

    private void restore(MarketConfigurationPersistence.State state) {
      FeeRates active = state.feeVersions().get(state.activeFeeVersion());
      FeeRates configured = feeRates(definition);
      if (state.currencyScale() != definition.structural().currencyScale()
          || active.makerRate().compareTo(configured.makerRate()) != 0
          || active.takerRate().compareTo(configured.takerRate()) != 0) {
        throw new IllegalStateException("persisted fee schedule does not match configuration");
      }
      structuralVersion = state.structuralVersion();
      riskVersion = state.riskVersion();
      feeVersion = state.activeFeeVersion();
      feeSchedule.clear();
      feeSchedule.putAll(state.feeVersions());
    }
  }
}
