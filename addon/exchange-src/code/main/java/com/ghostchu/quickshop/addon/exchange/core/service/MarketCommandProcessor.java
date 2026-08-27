package com.ghostchu.quickshop.addon.exchange.core.service;

@FunctionalInterface
public interface MarketCommandProcessor {
  /**
   * Processes one serialized market command. Implementations must stop promptly when the current
   * thread is interrupted so dispatcher shutdown can establish quiescence before resources close.
   */
  CommandResult process(ExchangeCommand command);
}
