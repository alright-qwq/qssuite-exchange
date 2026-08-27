package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Immutable order confirmation that fixes a market order's protection boundary. */
public record OrderConfirmation(
    UUID requestId, OrderSide side, String marketId, long quantity,
    BigDecimal slippageBoundary, BigDecimal maximumNotional,
    BigDecimal maximumFee, BigDecimal maximumFrozenCurrency) {
  public OrderConfirmation {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(side, "side");
    if (marketId == null || marketId.isBlank() || quantity <= 0) {
      throw new IllegalArgumentException("market and quantity are required");
    }
    Objects.requireNonNull(slippageBoundary, "slippageBoundary");
    Objects.requireNonNull(maximumNotional, "maximumNotional");
    Objects.requireNonNull(maximumFee, "maximumFee");
    Objects.requireNonNull(maximumFrozenCurrency, "maximumFrozenCurrency");
  }

  public static OrderConfirmation market(
      OrderSide side, String marketId, long quantity, BigDecimal bestExecutablePrice,
      BigDecimal slippage, BigDecimal takerFeeRate, BigDecimal tickSize,
      int priceScale, int currencyScale) {
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(bestExecutablePrice, "bestExecutablePrice");
    Objects.requireNonNull(slippage, "slippage");
    Objects.requireNonNull(takerFeeRate, "takerFeeRate");
    Objects.requireNonNull(tickSize, "tickSize");
    if (quantity <= 0 || bestExecutablePrice.signum() <= 0 || slippage.signum() < 0
        || takerFeeRate.signum() < 0 || tickSize.signum() <= 0
        || priceScale < 0 || currencyScale < 0) {
      throw new IllegalArgumentException("invalid market confirmation inputs");
    }
    BigDecimal multiplier = side == OrderSide.BUY
        ? BigDecimal.ONE.add(slippage) : BigDecimal.ONE.subtract(slippage);
    if (multiplier.signum() <= 0) {
      throw new IllegalArgumentException("sell slippage must leave a positive price boundary");
    }
    BigDecimal rawBoundary = bestExecutablePrice.multiply(multiplier);
    RoundingMode tickRounding = side == OrderSide.BUY ? RoundingMode.DOWN : RoundingMode.UP;
    BigDecimal boundary = rawBoundary.divide(tickSize, 0, tickRounding)
        .multiply(tickSize).setScale(priceScale, RoundingMode.UNNECESSARY);
    BigDecimal notional = boundary.multiply(BigDecimal.valueOf(quantity));
    BigDecimal fee = notional.multiply(takerFeeRate).setScale(currencyScale, RoundingMode.UP);
    BigDecimal frozen = side == OrderSide.BUY ? notional.add(fee) : BigDecimal.ZERO;
    return new OrderConfirmation(UUID.randomUUID(), side, marketId, quantity,
        boundary, notional, fee, frozen);
  }

  /** Plain quantity-times-price estimate shown on the confirmation page. */
  public static BigDecimal estimatedNotional(BigDecimal price, long quantity) {
    Objects.requireNonNull(price, "price");
    if (price.signum() <= 0 || quantity <= 0) {
      throw new IllegalArgumentException("estimated notional requires a positive price and quantity");
    }
    return price.multiply(BigDecimal.valueOf(quantity));
  }
}
