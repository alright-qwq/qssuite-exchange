package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Read-only account asset projection for player views. */
public record AccountAssetBalance(
    Kind kind, String assetId, BigDecimal available, BigDecimal frozen, String displayName,
    String symbol) {

  public enum Kind {
    CURRENCY,
    ITEM,
    SECURITY
  }

  /** Legacy constructor used by existing call sites before the kind enum existed. */
  public AccountAssetBalance(String kind, String assetId, BigDecimal available,
                             BigDecimal frozen) {
    this(kindFrom(kind), assetId, available, frozen, null);
  }

  public AccountAssetBalance(Kind kind, String assetId, BigDecimal available,
                             BigDecimal frozen, String displayName) {
    this(kind, assetId, available, frozen, displayName, null);
  }

  public AccountAssetBalance(Kind kind, String assetId, BigDecimal available,
                             BigDecimal frozen, String displayName, String symbol) {
    Objects.requireNonNull(kind, "kind");
    if (assetId == null || assetId.isBlank() || available == null || frozen == null
        || available.signum() < 0 || frozen.signum() < 0) {
      throw new IllegalArgumentException("invalid account asset balance");
    }
    if (kind == Kind.SECURITY && (symbol == null || symbol.isBlank())) {
      throw new IllegalArgumentException("security balance requires a symbol");
    }
    this.kind = kind;
    this.assetId = assetId;
    this.available = available;
    this.frozen = frozen;
    this.displayName = displayName == null || displayName.isBlank() ? null : displayName;
    this.symbol = symbol == null || symbol.isBlank() ? null : symbol;
  }

  public String kindName() {
    return kind.name().toLowerCase(Locale.ROOT);
  }

  private static Kind kindFrom(String kind) {
    if (kind == null) {
      throw new IllegalArgumentException("invalid account asset balance");
    }
    try {
      return Kind.valueOf(kind.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid account asset balance");
    }
  }
}
