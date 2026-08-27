package com.ghostchu.quickshop.addon.exchange.ui;

/** Configured currency or market item that can be deposited or withdrawn. */
public record TransferTarget(Kind kind, String assetId, String marketId, String displayName) {
  public TransferTarget {
    if (kind == null || assetId == null || assetId.isBlank()
        || displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("transfer target is required");
    }
    if (kind == Kind.CURRENCY && marketId != null) {
      throw new IllegalArgumentException("currency target cannot have a market");
    }
    if (kind == Kind.ITEM && (marketId == null || marketId.isBlank())) {
      throw new IllegalArgumentException("item target requires a market");
    }
  }

  public static TransferTarget currency(String currencyId) {
    return new TransferTarget(Kind.CURRENCY, currencyId, null, currencyId);
  }

  public static TransferTarget item(String marketId, String displayName) {
    return new TransferTarget(Kind.ITEM, marketId, marketId, displayName);
  }

  public enum Kind { CURRENCY, ITEM }
}
