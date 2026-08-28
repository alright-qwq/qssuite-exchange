package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import java.util.Objects;

public record MarketDefinition(
    String marketId, String displayName, boolean enabled,
    ItemDefinition item, StructuralRules structural, RiskRules risk,
    boolean blockContainerShops, AssetType assetType, SecurityDefinition security) {
  public MarketDefinition(String marketId, String displayName, boolean enabled,
                          ItemDefinition item, StructuralRules structural, RiskRules risk,
                          boolean blockContainerShops) {
    this(marketId, displayName, enabled, item, structural, risk, blockContainerShops,
        AssetType.PHYSICAL_ITEM, null);
  }

  public MarketDefinition {
    requireText(marketId, "marketId");
    requireText(displayName, "displayName");
    Objects.requireNonNull(assetType, "assetType");
    Objects.requireNonNull(structural, "structural");
    Objects.requireNonNull(risk, "risk");
    if (assetType == AssetType.PHYSICAL_ITEM) {
      Objects.requireNonNull(item, "item");
      if (security != null) {
        throw new IllegalArgumentException("physical market must not define security metadata");
      }
    } else {
      if (item != null) {
        throw new IllegalArgumentException("virtual security must not define an item");
      }
      Objects.requireNonNull(security, "security");
      if (!structural.currencyId().equals(security.currencyId())
          || structural.basePrice().compareTo(security.basePrice()) != 0) {
        throw new IllegalArgumentException("security metadata must match market currency and base price");
      }
      if (structural.minQuantity() % security.minimumUnit() != 0) {
        throw new IllegalArgumentException("market minimum quantity must align with security unit");
      }
    }
  }

  public record ItemDefinition(FingerprintMode mode, String material,
                               String encodedTemplate, String fingerprint) {
    public ItemDefinition {
      Objects.requireNonNull(mode, "mode");
      requireText(material, "material");
      if (mode == FingerprintMode.STRICT
          && (isBlank(encodedTemplate) || isBlank(fingerprint))) {
        throw new IllegalArgumentException("STRICT market requires template and fingerprint");
      }
    }
  }

  public record StructuralRules(
      String currencyId, BigDecimal basePrice, BigDecimal minPrice, BigDecimal maxPrice,
      BigDecimal tickSize, int priceScale, int currencyScale,
      long minQuantity, long maxQuantity, long discoveryQuantity) {
    public StructuralRules {
      requireText(currencyId, "currencyId");
      requirePositive(basePrice, "basePrice");
      requirePositive(minPrice, "minPrice");
      requirePositive(maxPrice, "maxPrice");
      requirePositive(tickSize, "tickSize");
      if (minPrice.compareTo(maxPrice) >= 0 || priceScale < 0 || currencyScale < 0
          || minQuantity <= 0 || maxQuantity < minQuantity || discoveryQuantity < minQuantity * 10) {
        throw new IllegalArgumentException("invalid structural market rules");
      }
      if (!fitsScale(basePrice, priceScale) || !fitsScale(tickSize, priceScale)
          || !fitsScale(minPrice, priceScale) || !fitsScale(maxPrice, priceScale)) {
        throw new IllegalArgumentException("tick and price bounds must fit priceScale");
      }
      validateTickAlignment(basePrice, tickSize, "basePrice");
      validateTickAlignment(minPrice, tickSize, "minPrice");
      validateTickAlignment(maxPrice, tickSize, "maxPrice");
      if (basePrice.compareTo(minPrice) < 0 || basePrice.compareTo(maxPrice) > 0) {
        throw new IllegalArgumentException("basePrice must be within market price bounds");
      }
    }
  }

  public record RiskRules(
      BigDecimal makerFeeRate, BigDecimal takerFeeRate,
      BigDecimal priceCageRatio, BigDecimal defaultMarketSlippage,
      BigDecimal maximumMarketSlippage, BigDecimal levelOneMove,
      long levelOneHaltSeconds, BigDecimal levelTwoMove, long levelTwoHaltSeconds,
      long maxAccountHolding, BigDecimal maxFrozenCurrency, int maxOpenOrders,
      int operationsPerSecond, int operationsPerMinute) {
    public RiskRules {
      requireNonNegative(makerFeeRate, "makerFeeRate");
      requireNonNegative(takerFeeRate, "takerFeeRate");
      requireNonNegative(priceCageRatio, "priceCageRatio");
      requireNonNegative(defaultMarketSlippage, "defaultMarketSlippage");
      requireNonNegative(maximumMarketSlippage, "maximumMarketSlippage");
      requireNonNegative(levelOneMove, "levelOneMove");
      requireNonNegative(levelTwoMove, "levelTwoMove");
      requirePositive(maxFrozenCurrency, "maxFrozenCurrency");
      if (makerFeeRate.compareTo(BigDecimal.ONE) > 0
          || takerFeeRate.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("fee rates must not exceed 100%");
      }
      if (priceCageRatio.compareTo(BigDecimal.ONE) >= 0
          || levelOneMove.compareTo(BigDecimal.ONE) >= 0
          || levelTwoMove.compareTo(BigDecimal.ONE) >= 0) {
        throw new IllegalArgumentException("price cage and halt move ratios must be below 1");
      }
      if (defaultMarketSlippage.compareTo(maximumMarketSlippage) > 0
          || maximumMarketSlippage.compareTo(new BigDecimal("0.20")) > 0
          || levelOneHaltSeconds <= 0 || levelTwoHaltSeconds <= 0 || maxAccountHolding <= 0
          || maxOpenOrders <= 0 || operationsPerSecond <= 0 || operationsPerMinute <= 0) {
        throw new IllegalArgumentException("invalid market risk rules");
      }
    }
  }

  private static void requireText(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(BigDecimal value, String name) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static boolean fitsScale(BigDecimal value, int scale) {
    return value.stripTrailingZeros().scale() <= scale;
  }

  private static void validateTickAlignment(BigDecimal price, BigDecimal tickSize, String name) {
    BigDecimal ticks = price.divide(tickSize, 0, java.math.RoundingMode.DOWN);
    if (ticks.multiply(tickSize).compareTo(price) != 0) {
      throw new IllegalArgumentException(name + " is not aligned to tickSize");
    }
  }
}
