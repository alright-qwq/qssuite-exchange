package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.time.Instant;
import java.util.Optional;

public record TradePermission(boolean allowed, Optional<Instant> haltUntil, int level) {
  public static TradePermission open() {
    return new TradePermission(true, Optional.empty(), 0);
  }

  public static TradePermission halted(Instant until, int level) {
    return new TradePermission(false, Optional.of(until), level);
  }
}
