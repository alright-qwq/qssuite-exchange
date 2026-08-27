package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;

public record RecoveredMarket(
    OrderBook book, ReferencePriceTracker referencePrices,
    CircuitBreaker circuitBreaker, MarketState state) {
  public long prioritySequence() {
    return state.prioritySequence();
  }

  public long matchSequence() {
    return state.matchSequence();
  }

  public long marketVersion() {
    return state.version();
  }
}
