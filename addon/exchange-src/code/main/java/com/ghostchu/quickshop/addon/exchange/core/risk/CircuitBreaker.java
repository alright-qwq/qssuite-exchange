package com.ghostchu.quickshop.addon.exchange.core.risk;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

public final class CircuitBreaker {
  private final RiskLimits limits;
  private int lastLevel;
  private Instant haltedUntil;

  public CircuitBreaker(RiskLimits limits) {
    this.limits = limits;
  }

  public TradePermission onPrice(BigDecimal price, BigDecimal reference, Instant now) {
    if (haltedUntil != null && now.isBefore(haltedUntil)) {
      return TradePermission.halted(haltedUntil, lastLevel);
    }
    BigDecimal move = price.subtract(reference).abs()
        .divide(reference, 12, RoundingMode.HALF_UP);
    if (lastLevel >= 1 && move.compareTo(limits.levelTwoMove()) >= 0) {
      lastLevel = 2;
      haltedUntil = now.plus(limits.levelTwoHalt());
      return TradePermission.halted(haltedUntil, 2);
    }
    if (move.compareTo(limits.levelOneMove()) >= 0) {
      lastLevel = 1;
      haltedUntil = now.plus(limits.levelOneHalt());
      return TradePermission.halted(haltedUntil, 1);
    }
    return TradePermission.open();
  }

  public void resume(Instant now) {
    if (haltedUntil == null || now.isBefore(haltedUntil)) {
      throw new IllegalStateException("halt has not expired");
    }
    haltedUntil = null;
  }

  public CircuitBreaker copy() {
    CircuitBreaker copy = new CircuitBreaker(limits);
    copy.lastLevel = lastLevel;
    copy.haltedUntil = haltedUntil;
    return copy;
  }

  public int level() {
    return lastLevel;
  }

  public Instant haltedUntil() {
    return haltedUntil;
  }

  public static CircuitBreaker restored(
      RiskLimits limits, int level, Instant haltedUntil) {
    if (level < 0 || level > 2 || (level == 0 && haltedUntil != null)) {
      throw new IllegalArgumentException("invalid circuit breaker state");
    }
    CircuitBreaker restored = new CircuitBreaker(limits);
    restored.lastLevel = level;
    restored.haltedUntil = haltedUntil;
    return restored;
  }

  public static CircuitBreaker restored(
      RiskLimits limits, MarketStatus status, BigDecimal referencePrice,
      BigDecimal lastPrice, Instant haltedUntil) {
    Objects.requireNonNull(referencePrice, "referencePrice");
    if (referencePrice.signum() <= 0) {
      throw new IllegalArgumentException("reference price must be positive");
    }
    CircuitBreaker restored = new CircuitBreaker(limits);
    if (lastPrice != null) {
      BigDecimal move = lastPrice.subtract(referencePrice).abs()
          .divide(referencePrice, 12, RoundingMode.HALF_UP);
      if (move.compareTo(limits.levelOneMove()) >= 0) {
        restored.lastLevel = 1;
      }
    }
    if (status == MarketStatus.HALTED) {
      restored.haltedUntil = haltedUntil;
    }
    return restored;
  }
}
