package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.util.List;
import java.util.UUID;

public record OrderReceipt(UUID requestId, UUID orderId, String status, List<Trade> trades) {
  public OrderReceipt {
    trades = List.copyOf(trades);
  }
}
