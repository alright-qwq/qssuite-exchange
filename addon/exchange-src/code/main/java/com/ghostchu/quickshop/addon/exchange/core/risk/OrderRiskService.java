package com.ghostchu.quickshop.addon.exchange.core.risk;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Combines market, rate, price and account exposure checks at the order-entry boundary. */
public final class OrderRiskService {
  private final OrderRateLimiter limiter;

  public OrderRiskService(OrderRateLimiter limiter) {
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  public RejectReason check(
      UUID accountId, Instant now, MarketStatus marketStatus, MarketRules rules,
      RiskLimits riskLimits, BigDecimal price, BigDecimal referencePrice,
      long addedHolding, BigDecimal addedFrozen, AccountRiskSnapshot snapshot,
      long maximumHolding, BigDecimal maximumFrozen, int maximumOpenOrders,
      boolean selfTrade) {
    if (marketStatus != MarketStatus.OPEN) return RejectReason.MARKET_NOT_OPEN;
    if (!limiter.allow(accountId, now)) return RejectReason.RATE_LIMITED;
    if (selfTrade) return RejectReason.SELF_TRADE;
    try {
      rules.validatePrice(price);
    } catch (IllegalArgumentException ignored) {
      return RejectReason.PRICE_OUTSIDE_CAGE;
    }
    if (!riskLimits.insideCage(price, referencePrice)) return RejectReason.PRICE_OUTSIDE_CAGE;
    if (!snapshot.canAddHolding(addedHolding, maximumHolding)) return RejectReason.HOLDING_LIMIT;
    if (!snapshot.canFreeze(addedFrozen, maximumFrozen)) return RejectReason.FROZEN_LIMIT;
    if (!snapshot.canOpenOrder(maximumOpenOrders)) return RejectReason.OPEN_ORDER_LIMIT;
    return null;
  }

  public RejectReason checkRateLimit(UUID accountId, Instant now) {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(now, "now");
    return limiter.allow(accountId, now) ? null : RejectReason.RATE_LIMITED;
  }

  public RejectReason checkExposure(
      long addedHolding, BigDecimal addedFrozen, AccountRiskSnapshot snapshot,
      AccountOrderLimits limits, boolean opensOrder) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(limits, "limits");
    if (!snapshot.canAddHolding(addedHolding, limits.maximumHolding())) {
      return RejectReason.HOLDING_LIMIT;
    }
    if (!snapshot.canFreeze(addedFrozen, limits.maximumFrozenCurrency())) {
      return RejectReason.FROZEN_LIMIT;
    }
    if (opensOrder && !snapshot.canOpenOrder(limits.maximumOpenOrders())) {
      return RejectReason.OPEN_ORDER_LIMIT;
    }
    return null;
  }

  /** Returns a rejection when a market order's protection price exceeds the configured bound. */
  public RejectReason checkMarketSlippage(
      BigDecimal protectionPrice, BigDecimal referencePrice, BigDecimal maximumSlippage) {
    if (protectionPrice == null || referencePrice == null || maximumSlippage == null
        || protectionPrice.signum() <= 0 || referencePrice.signum() <= 0
        || maximumSlippage.signum() < 0) {
      throw new IllegalArgumentException("market slippage inputs must be valid");
    }
    BigDecimal slippage = protectionPrice.subtract(referencePrice).abs()
        .divide(referencePrice, MathContext.DECIMAL128);
    return slippage.compareTo(maximumSlippage) > 0 ? RejectReason.SLIPPAGE_TOO_HIGH : null;
  }

  public enum RejectReason {
    MARKET_NOT_OPEN, RATE_LIMITED, PRICE_OUTSIDE_CAGE, SLIPPAGE_TOO_HIGH,
    HOLDING_LIMIT, FROZEN_LIMIT, OPEN_ORDER_LIMIT, SELF_TRADE
  }
}
