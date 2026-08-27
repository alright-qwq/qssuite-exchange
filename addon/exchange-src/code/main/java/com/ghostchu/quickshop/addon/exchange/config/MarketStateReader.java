package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;

@FunctionalInterface
public interface MarketStateReader {
  State read(String marketId);

  record State(MarketStatus status, int openOrders) {
    public State {
      if (status == null || openOrders < 0) {
        throw new IllegalArgumentException("invalid market state");
      }
    }
  }
}
