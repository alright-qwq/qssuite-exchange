package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Duration;

public record RiskLimits(
    BigDecimal cageRatio, BigDecimal defaultSlippage, BigDecimal maximumSlippage,
    BigDecimal levelOneMove, Duration levelOneHalt,
    BigDecimal levelTwoMove, Duration levelTwoHalt) {

  public static RiskLimits defaults() {
    return new RiskLimits(new BigDecimal("0.20"), new BigDecimal("0.05"),
        new BigDecimal("0.20"), new BigDecimal("0.10"), Duration.ofMinutes(2),
        new BigDecimal("0.20"), Duration.ofMinutes(10));
  }

  public boolean insideCage(BigDecimal price, BigDecimal reference) {
    BigDecimal lower = reference.multiply(BigDecimal.ONE.subtract(cageRatio));
    BigDecimal upper = reference.multiply(BigDecimal.ONE.add(cageRatio));
    return price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;
  }
}
