package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.util.List;

/** Immutable market-list value consumed by TNML page rendering only. */
public record MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                        BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                        long volume24h, MarketStatus status, String assetType, String symbol,
                        Long totalSupply, String securityStatus, BigDecimal volatility24h,
                        BigDecimal high24h, BigDecimal low24h, Long issuedSupply,
                        BigDecimal notional24h, List<TradeLore> recentTrades) {
  public MarketRow {
    if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()
        || status == null) {
      throw new IllegalArgumentException("market row identity and status are required");
    }
    recentTrades = List.copyOf(recentTrades == null ? List.of() : recentTrades);
  }

  public MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                   BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                   long volume24h, MarketStatus status) {
    this(marketId, displayName, lastPrice, bestBid, bestAsk, change24h, volume24h, status,
        null, null, null, null, null, null, null, null, null, List.of());
  }

  /** Backwards-compatible projection without recent-trade lore. */
  public MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                   BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                   long volume24h, MarketStatus status, String assetType, String symbol,
                   Long totalSupply, String securityStatus, BigDecimal volatility24h,
                   BigDecimal high24h, BigDecimal low24h, Long issuedSupply,
                   BigDecimal notional24h) {
    this(marketId, displayName, lastPrice, bestBid, bestAsk, change24h, volume24h, status,
        assetType, symbol, totalSupply, securityStatus, volatility24h, high24h, low24h,
        issuedSupply, notional24h, List.of());
  }

  /** Compact UI projection of one recent trade, safe for render-only consumers. */
  public record TradeLore(BigDecimal price, long quantity, String side, boolean buy) {
    public TradeLore {
      if (price == null || price.signum() <= 0 || quantity <= 0 || side == null) {
        throw new IllegalArgumentException("invalid trade lore");
      }
    }
  }
}
