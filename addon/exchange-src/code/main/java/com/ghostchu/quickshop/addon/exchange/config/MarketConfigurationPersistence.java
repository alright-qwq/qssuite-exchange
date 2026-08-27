package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Atomic persistence boundary used before a configuration reload becomes visible. */
@FunctionalInterface
public interface MarketConfigurationPersistence {
  MarketConfigurationPersistence NONE = states -> {};

  void persist(Map<String, State> states);

  default Map<String, State> load(Set<String> marketIds) {
    return Map.of();
  }

  record State(long structuralVersion, long riskVersion, long activeFeeVersion,
               int currencyScale, Map<Long, FeeRates> feeVersions) {
    public State {
      if (structuralVersion <= 0 || riskVersion <= 0 || activeFeeVersion <= 0
          || currencyScale < 0 || feeVersions == null
          || !feeVersions.containsKey(activeFeeVersion)) {
        throw new IllegalArgumentException("invalid persisted market configuration");
      }
      feeVersions = Map.copyOf(new LinkedHashMap<>(feeVersions));
    }
  }
}
