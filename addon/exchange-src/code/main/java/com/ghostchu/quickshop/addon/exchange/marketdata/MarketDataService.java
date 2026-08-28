package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Builds read-only quotes from executed trades and their UTC-minute candles. */
public final class MarketDataService implements AutoCloseable {
  private static final Duration TICKER_WINDOW = Duration.ofHours(24);
  private static final Logger LOGGER = Logger.getLogger(MarketDataService.class.getName());
  private final CandleAggregator candles;
  private final ExchangeRepository repository;
  private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
  private final Map<String, Instant> currentBuckets = new HashMap<>();
  private final List<Consumer<TradeEvent>> auditConsumers = new CopyOnWriteArrayList<>();
  private final Map<UUID, Consumer<PlayerUpdate>> playerConsumers = new ConcurrentHashMap<>();
  private final Set<String> changedMarkets = ConcurrentHashMap.newKeySet();

  public MarketDataService(CandleAggregator candles) {
    this(candles, null);
  }

  public MarketDataService(CandleAggregator candles, ExchangeRepository repository) {
    this.candles = Objects.requireNonNull(candles, "candles");
    this.repository = repository;
  }

  public synchronized void recordTrade(
      String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    Instant bucket = bucketStart(occurredAt);
    Instant current = currentBuckets.get(marketId);
    if (current != null && bucket.isBefore(current)) {
      throw new IllegalArgumentException("market trades must be recorded in chronological order");
    }
    if (current != null && bucket.isAfter(current) && repository != null) {
      persistClosedCandle(marketId, current);
    }
    candles.record(marketId, price, quantity, occurredAt);
    currentBuckets.put(marketId, bucket);
    lastPrices.put(marketId, price);
    changedMarkets.add(marketId);
    TradeEvent event = new TradeEvent(marketId, price, quantity, occurredAt);
    for (Consumer<TradeEvent> consumer : auditConsumers) {
      try {
        consumer.accept(event);
      } catch (RuntimeException ignored) {
        // Audit publication is observational and cannot invalidate a committed trade.
      }
    }
  }

  /** Registers an audit sink that receives every committed trade. */
  public void addAuditConsumer(Consumer<TradeEvent> consumer) {
    auditConsumers.add(Objects.requireNonNull(consumer, "consumer"));
  }

  public void removeAuditConsumer(Consumer<TradeEvent> consumer) {
    auditConsumers.remove(Objects.requireNonNull(consumer, "consumer"));
  }

  /**
   * Registers one player's view refresh callback; a second call for the same player replaces the
   * previous callback because each player has at most one active view. The runtime invokes
   * {@link #publishPlayerUpdates()} every 20 ticks.
   */
  public void subscribePlayer(UUID playerId, Consumer<PlayerUpdate> consumer) {
    playerConsumers.put(Objects.requireNonNull(playerId, "playerId"),
        Objects.requireNonNull(consumer, "consumer"));
  }

  public void unsubscribePlayer(UUID playerId) {
    playerConsumers.remove(Objects.requireNonNull(playerId, "playerId"));
  }

  /** Releases player subscriptions and per-market cache state when the runtime is torn down. */
  @Override
  public void close() {
    playerConsumers.clear();
    auditConsumers.clear();
    changedMarkets.clear();
    synchronized (this) {
      currentBuckets.clear();
    }
    lastPrices.clear();
  }

  /** Publishes at most one coalesced update per subscribed player for the current scheduler tick. */
  public synchronized void publishPlayerUpdates() {
    if (changedMarkets.isEmpty()) {
      return;
    }
    PlayerUpdate update = new PlayerUpdate(Set.copyOf(changedMarkets));
    changedMarkets.clear();
    for (Consumer<PlayerUpdate> consumer : playerConsumers.values()) {
      try {
        consumer.accept(update);
      } catch (RuntimeException ignored) {
        // A disconnected player view must not stop refreshes for other viewers.
      }
    }
  }

  /**
   * Persists every in-memory candle whose UTC minute ended before {@code asOf}, retrying buckets
   * that failed on an earlier rollover. Failed writes keep the candle in memory so quotes remain
   * correct and the next flush tick retries.
   */
  public synchronized void flush(Instant asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (repository == null) {
      return;
    }
    Instant currentBucket = bucketStart(asOf);
    for (String marketId : currentBuckets.keySet()) {
      for (Candle candle : candles.snapshots(marketId, Instant.EPOCH, currentBucket)) {
        persistClosedCandle(marketId, candle.bucketStart());
      }
    }
  }

  /**
   * Deletes persisted candles older than the retention window for every configured market.
   * Runs inside the writer fence; failures are swallowed so the next maintenance tick retries.
   */
  public void purgeOldCandles(Duration retention, Collection<String> marketIds) {
    if (repository == null || retention == null || retention.isZero() || retention.isNegative()
        || marketIds == null || marketIds.isEmpty()) {
      return;
    }
    Instant cutoff = Instant.now().minus(retention);
    for (String marketId : marketIds) {
      try {
        repository.deleteCandlesBefore(marketId, cutoff);
      } catch (SQLException failure) {
        // Best-effort maintenance; the next tick retries.
      }
    }
  }

  public MarketQuote quote(String marketId, BigDecimal referencePrice, BigDecimal bestBid,
                           BigDecimal bestAsk, MarketStatus status, Instant asOf) {
    requireQuoteArguments(marketId, referencePrice, status, asOf);
    Instant from = asOf.minus(TICKER_WINDOW);
    Instant to = asOf.plusSeconds(60);
    List<Candle> ticker = recentCandles(marketId, from, to);
    BigDecimal lastPrice = lastPrices.getOrDefault(marketId, referencePrice);
    long volume = ticker.stream().mapToLong(Candle::volume).reduce(0L, Math::addExact);
    BigDecimal notional = ticker.stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal change = changeRate(ticker);
    BigDecimal volatility = volatility(ticker, lastPrice);
    BigDecimal high24h = ticker.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(null);
    BigDecimal low24h = ticker.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(null);
    return new MarketQuote(marketId, lastPrice, referencePrice, bestBid, bestAsk, change,
        volume, notional, status, asOf, volatility, high24h, low24h);
  }

  /** Spread of the 24h candle close prices as a fraction of the latest close. */
  static BigDecimal volatility(List<Candle> candles, BigDecimal latestClose) {
    if (candles.size() < 2 || latestClose == null || latestClose.signum() <= 0) {
      return null;
    }
    BigDecimal minimum = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(null);
    BigDecimal maximum = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(null);
    if (minimum == null || maximum == null) {
      return null;
    }
    return maximum.subtract(minimum).divide(latestClose, 8, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  /** Close-over-open change rate of the ticker window; zero when there is no usable open. */
  static BigDecimal changeRate(List<Candle> candles) {
    if (candles == null || candles.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal firstOpen = candles.getFirst().open();
    if (firstOpen == null || firstOpen.signum() == 0) {
      // A damaged candle must not take down quote rendering; the UI treats the rate as flat.
      return BigDecimal.ZERO;
    }
    return candles.get(candles.size() - 1).close().subtract(firstOpen)
        .divide(firstOpen, 8, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  /** Returns persisted and current in-memory candles in chronological bucket order. */
  public List<Candle> recentCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
    if (marketId == null || marketId.isBlank() || fromInclusive == null || toExclusive == null
        || !fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("invalid candle range");
    }
    Instant fromBucket = bucketStart(fromInclusive);
    Instant toBucket = bucketStart(toExclusive);
    Map<Instant, Candle> candlesByBucket = new TreeMap<>();
    loadPersistedCandles(marketId, fromBucket, toBucket)
        .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
    candles.snapshots(marketId, fromInclusive, toExclusive)
        .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
    return List.copyOf(candlesByBucket.values());
  }

  public MarketQuote quote(String marketId, BigDecimal referencePrice, OrderBook book,
                           RiskLimits limits, MarketStatus status, Instant asOf) {
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(limits, "limits");
    requireQuoteArguments(marketId, referencePrice, status, asOf);
    BigDecimal bestBid = book.bestExecutable(OrderSide.BUY,
        price -> limits.insideCage(price, referencePrice)).map(Order::limitPrice).orElse(null);
    BigDecimal bestAsk = book.bestExecutable(OrderSide.SELL,
        price -> limits.insideCage(price, referencePrice)).map(Order::limitPrice).orElse(null);
    return quote(marketId, referencePrice, bestBid, bestAsk, status, asOf);
  }

  public List<DepthLevel> depth(OrderBook book, OrderSide side, BigDecimal referencePrice,
                                RiskLimits limits) {
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(referencePrice, "referencePrice");
    Objects.requireNonNull(limits, "limits");
    if (referencePrice.signum() <= 0) {
      throw new IllegalArgumentException("reference price must be positive");
    }
    Map<BigDecimal, Long> quantities = new LinkedHashMap<>();
    for (Order order : book.orders(side)) {
      quantities.merge(order.limitPrice(), order.remainingQuantity(), Math::addExact);
    }
    return quantities.entrySet().stream()
        .map(entry -> new DepthLevel(entry.getKey(), entry.getValue(),
            limits.insideCage(entry.getKey(), referencePrice)))
        .toList();
  }

  private static void requireQuoteArguments(String marketId, BigDecimal referencePrice,
                                             MarketStatus status, Instant asOf) {
    if (marketId == null || marketId.isBlank() || referencePrice == null
        || referencePrice.signum() <= 0 || status == null || asOf == null) {
      throw new IllegalArgumentException("invalid market quote request");
    }
  }

  /**
   * Persists one closed candle. Candle publication is best-effort: a failed write keeps the candle
   * in memory (so committed trades stay visible in charts) and never aborts the calling trade.
   */
  private void persistClosedCandle(String marketId, Instant bucket) {
    Candle candle = candles.snapshot(marketId, bucket).orElse(null);
    if (candle == null) {
      LOGGER.warning("skipping candle persistence for missing in-memory bucket " + marketId
          + " at " + bucket);
      return;
    }
    try {
      repository.upsertCandle(candle);
      candles.discard(marketId, bucket);
    } catch (SQLException failure) {
      LOGGER.log(Level.WARNING, "failed to persist closed candle for " + marketId + " at "
          + bucket + "; will retry on the next flush tick", failure);
    }
  }

  private List<Candle> loadPersistedCandles(String marketId, Instant from, Instant to) {
    if (repository == null) {
      return List.of();
    }
    try {
      return repository.loadCandles(marketId, from, to);
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load market candles", failure);
    }
  }

  private static Instant bucketStart(Instant instant) {
    Objects.requireNonNull(instant, "occurredAt");
    return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), 60) * 60L);
  }

  public record DepthLevel(BigDecimal price, long quantity, boolean executable) {
    public DepthLevel {
      if (price == null || price.signum() <= 0 || quantity <= 0) {
        throw new IllegalArgumentException("invalid depth level");
      }
    }
  }

  public record TradeEvent(String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    public TradeEvent {
      if (marketId == null || marketId.isBlank() || price == null || price.signum() <= 0
          || quantity <= 0 || occurredAt == null) {
        throw new IllegalArgumentException("invalid trade event");
      }
    }
  }

  public record PlayerUpdate(Set<String> marketIds) {
    public PlayerUpdate {
      marketIds = Set.copyOf(Objects.requireNonNull(marketIds, "marketIds"));
      if (marketIds.isEmpty()) {
        throw new IllegalArgumentException("player update requires a changed market");
      }
    }
  }
}
