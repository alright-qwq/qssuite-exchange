package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import java.util.List;

public record MarketSnapshot(
    MarketState state, List<PersistedOrder> openOrders, List<MarketTradeSample> recentTrades,
    long maximumPrioritySequence, long maximumMatchSequence) {
  public MarketSnapshot {
    if (state == null || maximumPrioritySequence < 0 || maximumMatchSequence < 0) {
      throw new IllegalArgumentException("invalid market snapshot");
    }
    openOrders = List.copyOf(openOrders);
    recentTrades = List.copyOf(recentTrades);
  }
}
