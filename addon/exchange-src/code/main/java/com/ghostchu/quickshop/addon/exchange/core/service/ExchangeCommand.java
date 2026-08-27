package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.UUID;

public record ExchangeCommand(String marketId, UUID accountId, UUID requestId, String operation) {
  public ExchangeCommand {
    if (marketId == null || accountId == null || requestId == null || operation == null) {
      throw new IllegalArgumentException("command identity is required");
    }
  }
}
