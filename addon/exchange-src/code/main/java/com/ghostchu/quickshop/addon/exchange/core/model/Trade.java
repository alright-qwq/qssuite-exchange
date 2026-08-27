package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Trade(
    UUID tradeId, String marketId, UUID makerOrderId, UUID takerOrderId,
    UUID buyerAccountId, UUID sellerAccountId, BigDecimal price, long quantity,
    BigDecimal makerFee, BigDecimal takerFee, long matchSequence, Instant executedAt) {
  public Trade {
    if (tradeId == null || marketId == null || marketId.isBlank() || makerOrderId == null
        || takerOrderId == null || buyerAccountId == null || sellerAccountId == null || executedAt == null) {
      throw new IllegalArgumentException("trade identity is required");
    }
    if (makerOrderId.equals(takerOrderId) || buyerAccountId.equals(sellerAccountId)) {
      throw new IllegalArgumentException("trade parties must be distinct");
    }
    if (quantity <= 0 || price == null || price.signum() <= 0 || matchSequence <= 0) {
      throw new IllegalArgumentException("invalid trade");
    }
    if (makerFee == null || makerFee.signum() < 0 || takerFee == null || takerFee.signum() < 0) {
      throw new IllegalArgumentException("trade fees must be non-negative");
    }
  }
}
