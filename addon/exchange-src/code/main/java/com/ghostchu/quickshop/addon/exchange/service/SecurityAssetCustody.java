package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import java.sql.SQLException;
import java.util.UUID;

public final class SecurityAssetCustody implements AssetCustody {
  public static final SecurityAssetCustody INSTANCE = new SecurityAssetCustody();
  private final long minimumUnit;

  private SecurityAssetCustody() {
    this(1);
  }

  public SecurityAssetCustody(long minimumUnit) {
    if (minimumUnit <= 0) {
      throw new IllegalArgumentException("minimum unit must be positive");
    }
    this.minimumUnit = minimumUnit;
  }

  @Override
  public void validateQuantity(long quantity) {
    if (quantity % minimumUnit != 0) {
      throw new IllegalArgumentException(
          "quantity must be a multiple of minimum unit " + minimumUnit);
    }
  }

  @Override
  public long holding(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException {
    return tx.existingSecurityBalance(accountId, marketId)
        .map(balance -> Math.addExact(balance.availableQuantity(), balance.frozenQuantity()))
        .orElse(0L);
  }

  @Override
  public void lock(ExchangeTransaction tx, UUID accountId, String marketId) throws SQLException {
    tx.securityBalance(accountId, marketId);
  }

  @Override
  public void freeze(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.freezeSecurity(accountId, marketId, quantity);
  }

  @Override
  public void release(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.releaseSecurity(accountId, marketId, quantity);
  }

  @Override
  public void consumeFrozen(ExchangeTransaction tx, UUID accountId, String marketId, long quantity)
      throws SQLException {
    tx.consumeFrozenSecurity(accountId, marketId, quantity);
  }

  @Override
  public void creditAvailable(ExchangeTransaction tx, UUID accountId, String marketId,
                              long quantity) throws SQLException {
    tx.creditAvailableSecurity(accountId, marketId, quantity);
  }

  @Override
  public boolean recordsLedgerEntries() {
    return true;
  }
}
