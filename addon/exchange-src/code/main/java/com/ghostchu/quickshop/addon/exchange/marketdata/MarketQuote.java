package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Read-only market quote used by player views and operational consumers. */
public record MarketQuote(String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
                          BigDecimal bestBid, BigDecimal bestAsk,
                          BigDecimal change24h, long volume24h, BigDecimal notional24h,
                          MarketStatus status, Instant asOf, BigDecimal volatility24h,
                          BigDecimal high24h, BigDecimal low24h) {
  public MarketQuote(String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
                     BigDecimal bestBid, BigDecimal bestAsk,
                     BigDecimal change24h, long volume24h, BigDecimal notional24h,
                     MarketStatus status, Instant asOf) {
    this(marketId, lastPrice, referencePrice, bestBid, bestAsk,
        change24h, volume24h, notional24h, status, asOf, null, null, null);
  }

  public MarketQuote {
    if (marketId == null || marketId.isBlank() || referencePrice == null
        || referencePrice.signum() <= 0 || change24h == null || volume24h < 0
        || notional24h == null || notional24h.signum() < 0 || status == null || asOf == null) {
      throw new IllegalArgumentException("invalid market quote");
    }
    if (volatility24h != null && volatility24h.signum() < 0) {
      throw new IllegalArgumentException("volatility must be non-negative");
    }
    if (high24h != null && high24h.signum() <= 0) {
      throw new IllegalArgumentException("24h high must be positive");
    }
    if (low24h != null && low24h.signum() <= 0) {
      throw new IllegalArgumentException("24h low must be positive");
    }
  }
}
