package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;

import java.util.List;

public record MatchResult(Order finalOrder, List<Order> changedMakers,
                          List<Trade> trades, boolean rested, boolean selfTradeRejected) {
  public MatchResult {
    changedMakers = List.copyOf(changedMakers);
    trades = List.copyOf(trades);
  }
}
