package com.ghostchu.quickshop.addon.exchange.service;

public enum SettlementStage {
  AFTER_RESERVATION,
  AFTER_ORDER_INSERT,
  AFTER_MAKER_UPDATE,
  AFTER_TRADE_INSERT,
  AFTER_BALANCE_UPDATE,
  AFTER_LEDGER_INSERT,
  AFTER_REQUEST_RESULT
}
