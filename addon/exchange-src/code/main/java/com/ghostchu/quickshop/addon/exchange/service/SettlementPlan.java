package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.math.BigDecimal;
import java.util.List;

public record SettlementPlan(Order taker, List<Order> makers, List<Trade> trades,
                             BigDecimal takerCurrencyRelease, long takerItemRelease) {
  public SettlementPlan {
    makers = List.copyOf(makers);
    trades = List.copyOf(trades);
  }
}
