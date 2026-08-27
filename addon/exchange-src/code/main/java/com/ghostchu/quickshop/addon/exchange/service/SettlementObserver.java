package com.ghostchu.quickshop.addon.exchange.service;

@FunctionalInterface
public interface SettlementObserver {
  SettlementObserver NONE = stage -> {};

  void reached(SettlementStage stage);
}
