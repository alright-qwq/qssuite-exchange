package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import java.sql.SQLException;
import java.util.UUID;

public interface AssetCustody {
  long holding(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException;
  void lock(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException;
  void freeze(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException;
  void release(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException;
  void consumeFrozen(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException;
  void creditAvailable(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException;
  default void validateQuantity(long quantity) {}
  default boolean recordsLedgerEntries() {
    return false;
  }
}
