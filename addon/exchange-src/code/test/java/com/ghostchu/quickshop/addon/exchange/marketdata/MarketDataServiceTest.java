package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTest {
  @Test
  void publishesEveryTradeToAuditButCoalescesPlayerFeedUntilScheduledTick() {
    MarketDataService data = new MarketDataService(new CandleAggregator());
    AtomicInteger audited = new AtomicInteger();
    AtomicInteger playerUpdates = new AtomicInteger();
    UUID player = UUID.randomUUID();
    data.addAuditConsumer(event -> audited.incrementAndGet());
    data.subscribePlayer(player, update -> playerUpdates.incrementAndGet());

    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 1,
        Instant.parse("2026-07-26T00:00:01Z"));
    data.recordTrade("diamond-usd", new BigDecimal("101.00"), 1,
        Instant.parse("2026-07-26T00:00:02Z"));

    assertThat(audited).hasValue(2);
    assertThat(playerUpdates).hasValue(0);
    data.publishPlayerUpdates();
    assertThat(playerUpdates).hasValue(1);
    data.publishPlayerUpdates();
    assertThat(playerUpdates).hasValue(1);
  }

  @Test
  void purgesCandlesOlderThanRetentionThroughTheRepository() throws Exception {
    AtomicReference<Instant> deletedCutoff = new AtomicReference<>();
    ExchangeRepository repository = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(
          com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.TransactionWork<T> work)
          throws SQLException {
        return null;
      }

      @Override
      public void deleteCandlesBefore(String marketId, Instant cutoff) {
        deletedCutoff.set(cutoff);
      }
    };
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);

    data.purgeOldCandles(java.time.Duration.ofDays(30), List.of("diamond-usd"));

    assertThat(deletedCutoff.get()).isNotNull()
        .isBeforeOrEqualTo(Instant.now().minus(java.time.Duration.ofDays(30)));
    assertThat(deletedCutoff.get()).isAfter(Instant.now().minus(java.time.Duration.ofDays(31)));
  }

  @Test
  void exposesLatestTradeAndCurrentMinuteTotalsInQuote() {
    MarketDataService data = new MarketDataService(new CandleAggregator());
    Instant now = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, now);

    MarketQuote quote = data.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("111.00"), MarketStatus.OPEN, now);

    assertThat(quote.lastPrice()).isEqualByComparingTo("110.00");
    assertThat(quote.volume24h()).isEqualTo(3);
    assertThat(quote.notional24h()).isEqualByComparingTo("330.00");
    assertThat(quote.change24h()).isEqualByComparingTo("0");
  }

  @Test
  void rollsCandleTotalsAndOpeningPriceAcrossTwentyFourHours() {
    MarketDataService data = new MarketDataService(new CandleAggregator());
    Instant now = Instant.parse("2026-07-27T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, now.minusSeconds(24 * 60 * 60 - 1));
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, now);

    MarketQuote quote = data.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("111.00"), MarketStatus.OPEN, now);

    assertThat(quote.change24h()).isEqualByComparingTo("0.10");
    assertThat(quote.volume24h()).isEqualTo(5);
    assertThat(quote.notional24h()).isEqualByComparingTo("530.00");
  }

  @Test
  void persistsClosedMinuteWhenTheNextMinuteStarts() {
    RecordingRepository repository = new RecordingRepository();
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, first.plusSeconds(20));

    assertThat(repository.candles).singleElement().satisfies(candle -> {
      assertThat(candle.bucketStart()).isEqualTo(Instant.parse("2026-07-26T00:00:00Z"));
      assertThat(candle.close()).isEqualByComparingTo("100.00");
      assertThat(candle.volume()).isEqualTo(2L);
    });
  }

  @Test
  void includesPersistedCandlesAfterServiceRestart() {
    RecordingRepository repository = new RecordingRepository();
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    MarketDataService beforeRestart = new MarketDataService(new CandleAggregator(), repository);
    beforeRestart.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);
    beforeRestart.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, first.plusSeconds(20));

    MarketDataService afterRestart = new MarketDataService(new CandleAggregator(), repository);
    afterRestart.recordTrade("diamond-usd", new BigDecimal("120.00"), 4, first.plusSeconds(80));

    MarketQuote quote = afterRestart.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("121.00"), MarketStatus.OPEN,
        first.plusSeconds(80));
    assertThat(quote.volume24h()).isEqualTo(6L);
    assertThat(quote.notional24h()).isEqualByComparingTo("680.00");
    assertThat(quote.change24h()).isEqualByComparingTo("0.20");
  }

  @Test
  void doesNotExposeTickerWhileMinuteRolloverIsOnlyPartiallyPersisted() throws Exception {
    BlockingRepository repository = new BlockingRepository();
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<?> rollover = workers.submit(() -> data.recordTrade(
          "diamond-usd", new BigDecimal("110.00"), 3, first.plusSeconds(20)));
      assertThat(repository.persisted.await(5, TimeUnit.SECONDS)).isTrue();

      Future<MarketQuote> quote = workers.submit(() -> data.quote("diamond-usd",
          new BigDecimal("100.00"), new BigDecimal("99.00"), new BigDecimal("111.00"),
          MarketStatus.OPEN, first.plusSeconds(20)));
      assertThat(repository.readStarted.await(5, TimeUnit.SECONDS)).isTrue();
      repository.allowRead.countDown();

      MarketQuote duringRollover = quote.get(5, TimeUnit.SECONDS);
      assertThat(duringRollover.volume24h()).isEqualTo(2L);
      assertThat(duringRollover.notional24h()).isEqualByComparingTo("200.00");
      repository.release.countDown();
      rollover.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void flushesIdleClosedMinuteWithoutWaitingForAnotherTrade() {
    RecordingRepository repository = new RecordingRepository();
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);

    data.flush(first.plusSeconds(20));

    assertThat(repository.candles).singleElement().satisfies(candle -> {
      assertThat(candle.bucketStart()).isEqualTo(Instant.parse("2026-07-26T00:00:00Z"));
      assertThat(candle.volume()).isEqualTo(2L);
    });
  }

  @Test
  void mergesPersistedAndCurrentCandlesForRecentChartData() {
    RecordingRepository repository = new RecordingRepository();
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);
    Instant firstTrade = Instant.parse("2026-07-26T00:00:40Z");
    Instant secondTrade = Instant.parse("2026-07-26T00:01:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, firstTrade);
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, secondTrade);

    assertThat(data.recentCandles("diamond-usd", firstTrade, secondTrade.plusSeconds(60)))
        .extracting(Candle::bucketStart)
        .containsExactly(Instant.parse("2026-07-26T00:00:00Z"),
            Instant.parse("2026-07-26T00:01:00Z"));
  }

  @Test
  void usesOnlyCageExecutableBookLevelsForQuotesButShowsProtectedDepth() {
    OrderBook book = new OrderBook();
    book.add(restingSell("70.00", 1));
    book.add(restingSell("90.00", 2));
    MarketDataService data = new MarketDataService(new CandleAggregator());

    MarketQuote quote = data.quote("diamond-usd", new BigDecimal("100.00"), book,
        RiskLimits.defaults(), MarketStatus.OPEN, Instant.parse("2026-07-26T00:00:40Z"));

    assertThat(quote.bestAsk()).isEqualByComparingTo("90.00");
    assertThat(data.depth(book, OrderSide.SELL, new BigDecimal("100.00"), RiskLimits.defaults()))
        .containsExactly(
            new MarketDataService.DepthLevel(new BigDecimal("70.00"), 1, false),
            new MarketDataService.DepthLevel(new BigDecimal("90.00"), 2, true));
  }

  private static class RecordingRepository implements ExchangeRepository {
    private final List<Candle> candles = new ArrayList<>();

    @Override
    public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
      throw new UnsupportedOperationException("not used by the market data service");
    }

    @Override
    public void upsertCandle(Candle candle) {
      candles.add(candle);
    }

    @Override
    public List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
      return candles.stream().filter(candle -> candle.marketId().equals(marketId)
          && !candle.bucketStart().isBefore(fromInclusive)
          && candle.bucketStart().isBefore(toExclusive)).toList();
    }
  }

  private static final class BlockingRepository extends RecordingRepository {
    private final CountDownLatch persisted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch readStarted = new CountDownLatch(1);
    private final CountDownLatch allowRead = new CountDownLatch(1);

    @Override
    public void upsertCandle(Candle candle) {
      super.upsertCandle(candle);
      persisted.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release candle persistence");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
      readStarted.countDown();
      try {
        if (!allowRead.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release candle query");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      }
      return super.loadCandles(marketId, fromInclusive, toExclusive);
    }
  }

  private static Order restingSell(String price, long quantity) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, 1, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
