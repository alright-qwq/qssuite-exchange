package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable fee versions persisted with a market. */
public record MarketFeeSchedule(long activeVersion, int currencyScale,
                                Map<Long, FeeRates> versions) {
  public MarketFeeSchedule {
    if (activeVersion <= 0 || currencyScale < 0 || versions == null || versions.isEmpty()) {
      throw new IllegalArgumentException("invalid market fee schedule");
    }
    LinkedHashMap<Long, FeeRates> copied = new LinkedHashMap<>();
    versions.forEach((version, rates) -> {
      if (version == null || version <= 0) {
        throw new IllegalArgumentException("fee schedule version must be positive");
      }
      copied.put(version, Objects.requireNonNull(rates, "fee rates"));
    });
    if (!copied.containsKey(activeVersion)) {
      throw new IllegalArgumentException("active fee version is missing");
    }
    versions = Map.copyOf(copied);
  }

  public FeeRates activeRates() {
    return rates(activeVersion);
  }

  public FeeRates rates(long version) {
    FeeRates rates = versions.get(version);
    if (rates == null) {
      throw new IllegalStateException("fee schedule version is missing: " + version);
    }
    return rates;
  }
}
