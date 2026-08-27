package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MarketRules(
    String marketId, String currencyId, BigDecimal basePrice,
    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal tickSize,
    long minQuantity, long maxQuantity, int priceScale,
    BigDecimal makerFeeRate, BigDecimal takerFeeRate) {

  public MarketRules {
    if (marketId == null || marketId.isBlank() || currencyId == null || currencyId.isBlank()) {
      throw new IllegalArgumentException("market and currency are required");
    }
    if (minQuantity <= 0 || maxQuantity < minQuantity || priceScale < 0) {
      throw new IllegalArgumentException("invalid quantity or scale");
    }
    requirePositive(basePrice, "basePrice");
    requirePositive(minPrice, "minPrice");
    requirePositive(maxPrice, "maxPrice");
    requirePositive(tickSize, "tickSize");
    if (minPrice.compareTo(maxPrice) >= 0) {
      throw new IllegalArgumentException("minPrice must be below maxPrice");
    }
    validateConfiguredPrice(basePrice, tickSize, priceScale, "basePrice");
    validateConfiguredPrice(minPrice, tickSize, priceScale, "minPrice");
    validateConfiguredPrice(maxPrice, tickSize, priceScale, "maxPrice");
    if (!hasAllowedNumericScale(tickSize, priceScale)) {
      throw new IllegalArgumentException("tickSize exceeds priceScale");
    }
    if (basePrice.compareTo(minPrice) < 0 || basePrice.compareTo(maxPrice) > 0) {
      throw new IllegalArgumentException("basePrice outside market bounds");
    }
    validateRate(makerFeeRate);
    validateRate(takerFeeRate);
  }

  public void validatePrice(BigDecimal price) {
    if (price == null || !hasAllowedNumericScale(price, priceScale)
        || price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
      throw new IllegalArgumentException("price outside market bounds");
    }
    validateTickAlignment(price, tickSize, "price is not aligned to tickSize");
  }

  private static void validateConfiguredPrice(BigDecimal price, BigDecimal tickSize, int priceScale, String name) {
    if (!hasAllowedNumericScale(price, priceScale)) {
      throw new IllegalArgumentException(name + " exceeds priceScale");
    }
    validateTickAlignment(price, tickSize, name + " is not aligned to tickSize");
  }

  private static void validateTickAlignment(BigDecimal price, BigDecimal tickSize, String message) {
    BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.DOWN);
    if (ticks.multiply(tickSize).compareTo(price) != 0) {
      throw new IllegalArgumentException(message);
    }
  }

  private static boolean hasAllowedNumericScale(BigDecimal value, int priceScale) {
    return value.stripTrailingZeros().scale() <= priceScale;
  }

  public void validateQuantity(long quantity) {
    if (quantity < minQuantity || quantity > maxQuantity) {
      throw new IllegalArgumentException("quantity outside market bounds");
    }
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
  }

  private static void validateRate(BigDecimal rate) {
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("fee rate outside 0..1");
    }
  }
}
