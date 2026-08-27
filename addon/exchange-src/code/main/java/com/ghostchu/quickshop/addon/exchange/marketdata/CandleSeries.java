package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure OHLCV aggregation for chart timeframes built from one-minute candles. */
public final class CandleSeries {
  private CandleSeries() {}

  /** Groups one-minute candles into equal-width buckets aligned to UTC time boundaries. */
  public static List<Candle> aggregate(List<Candle> candles, Duration bucket) {
    Objects.requireNonNull(candles, "candles");
    Objects.requireNonNull(bucket, "bucket");
    long seconds = bucket.getSeconds();
    if (seconds < 60 || seconds % 60 != 0) {
      throw new IllegalArgumentException("candle buckets must be whole minutes");
    }
    List<Candle> sorted = candles.stream()
        .sorted(Comparator.comparing(Candle::bucketStart)).toList();
    List<Candle> aggregated = new ArrayList<>();
    Candle current = null;
    Instant currentBucket = null;
    for (Candle candle : sorted) {
      Instant bucketStart = aligned(candle.bucketStart(), seconds);
      if (!bucketStart.equals(currentBucket)) {
        if (current != null) {
          aggregated.add(current);
        }
        current = new Candle(candle.marketId(), bucketStart, candle.open(), candle.high(),
            candle.low(), candle.close(), candle.volume(), candle.notional());
        currentBucket = bucketStart;
        continue;
      }
      if (current == null) {
        current = new Candle(candle.marketId(), bucketStart, candle.open(), candle.high(),
            candle.low(), candle.close(), candle.volume(), candle.notional());
        currentBucket = bucketStart;
        continue;
      }
      current = new Candle(candle.marketId(), bucketStart, current.open(),
          current.high().max(candle.high()), current.low().min(candle.low()), candle.close(),
          Math.addExact(current.volume(), candle.volume()),
          current.notional().add(candle.notional()));
    }
    if (current != null) {
      aggregated.add(current);
    }
    return List.copyOf(aggregated);
  }

  private static Instant aligned(Instant bucketStart, long bucketSeconds) {
    long epoch = bucketStart.getEpochSecond();
    return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, bucketSeconds));
  }
}
