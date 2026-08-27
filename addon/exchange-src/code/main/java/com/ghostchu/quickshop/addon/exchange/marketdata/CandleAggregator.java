package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory UTC-minute OHLCV aggregation; persistence is performed by the caller at rollover. */
public final class CandleAggregator {
  private final Map<Key, Candle> candles = new HashMap<>();

  public synchronized void record(String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    if (marketId == null || marketId.isBlank() || price == null || price.signum() <= 0
        || quantity <= 0 || occurredAt == null) {
      throw new IllegalArgumentException("invalid candle trade");
    }
    Instant bucket = Instant.ofEpochSecond(Math.floorDiv(occurredAt.getEpochSecond(), 60) * 60L);
    Key key = new Key(marketId, bucket);
    Candle previous = candles.get(key);
    BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));
    Candle next = previous == null
        ? new Candle(marketId, bucket, price, price, price, price, quantity, notional)
        : new Candle(marketId, bucket, previous.open(), previous.high().max(price),
            previous.low().min(price), price, Math.addExact(previous.volume(), quantity),
            previous.notional().add(notional));
    candles.put(key, next);
  }

  public synchronized Optional<Candle> snapshot(String marketId, Instant bucketStart) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(bucketStart, "bucketStart");
    Instant bucket = Instant.ofEpochSecond(Math.floorDiv(bucketStart.getEpochSecond(), 60) * 60L);
    return Optional.ofNullable(candles.get(new Key(marketId, bucket)));
  }

  /** Returns UTC-minute candles whose buckets lie in the requested half-open interval. */
  public synchronized List<Candle> snapshots(String marketId, Instant fromInclusive,
                                              Instant toExclusive) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(fromInclusive, "fromInclusive");
    Objects.requireNonNull(toExclusive, "toExclusive");
    if (!fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("candle range must not be empty or reversed");
    }
    Instant from = bucketStart(fromInclusive);
    Instant to = bucketStart(toExclusive);
    return candles.entrySet().stream()
        .filter(entry -> entry.getKey().marketId().equals(marketId))
        .map(Map.Entry::getValue)
        .filter(candle -> !candle.bucketStart().isBefore(from)
            && candle.bucketStart().isBefore(to))
        .sorted(Comparator.comparing(Candle::bucketStart))
        .toList();
  }

  public synchronized void discard(String marketId, Instant bucketStart) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(bucketStart, "bucketStart");
    candles.remove(new Key(marketId, bucketStart(bucketStart)));
  }

  private static Instant bucketStart(Instant instant) {
    return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), 60) * 60L);
  }

  private record Key(String marketId, Instant bucketStart) {}
}
