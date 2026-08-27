package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import java.sql.SQLException;
import java.util.UUID;

public final class ItemAssetCustody implements AssetCustody {
  public static final ItemAssetCustody INSTANCE = new ItemAssetCustody();

  private ItemAssetCustody() {}

  @Override
  public long holding(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException {
    return tx.existingInventory(accountId, marketId)
        .map(balance -> Math.addExact(balance.availableQuantity(), balance.frozenQuantity()))
        .orElse(0L);
  }

  @Override
  public void lock(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException {
    tx.inventory(accountId, marketId);
  }

  @Override
  public void freeze(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.freezeItems(accountId, marketId, quantity);
  }

  @Override
  public void release(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.releaseItems(accountId, marketId, quantity);
  }

  @Override
  public void consumeFrozen(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.consumeFrozenItems(accountId, marketId, quantity);
  }

  @Override
  public void creditAvailable(ExchangeTransaction tx, UUID accountId, String marketId,
                              long quantity) throws SQLException {
    tx.creditAvailableItems(accountId, marketId, quantity);
  }
}
