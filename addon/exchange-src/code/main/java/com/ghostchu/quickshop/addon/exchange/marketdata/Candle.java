package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record Candle(String marketId, Instant bucketStart,
                     BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                     long volume, BigDecimal notional) {}
