package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDashboardPresenterTest {
  private final MarketDashboardPresenter presenter = new MarketDashboardPresenter();

  @Test
  void sortsDepthByTradablePriceAndComputesCumulativeBarStrength() {
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0.10", 10),
        List.of(), List.of(
            new MarketDataService.DepthLevel(new BigDecimal("98"), 5, true),
            new MarketDataService.DepthLevel(new BigDecimal("99"), 10, true)),
        List.of(new MarketDataService.DepthLevel(new BigDecimal("102"), 5, true),
            new MarketDataService.DepthLevel(new BigDecimal("101"), 10, true)),
        new BigDecimal("2"), new BigDecimal("0.02"));

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot);

    assertThat(rows.bids()).extracting(MarketDashboardPresenter.DepthRow::price)
        .containsExactly(new BigDecimal("99"), new BigDecimal("98"), null, null, null);
    assertThat(rows.bids()).extracting(MarketDashboardPresenter.DepthRow::cumulativeQuantity)
        .containsExactly(10L, 15L, 0L, 0L, 0L);
    assertThat(rows.bids()).extracting(MarketDashboardPresenter.DepthRow::strength)
        .containsExactly(8, 4, 0, 0, 0);
    assertThat(rows.asks()).extracting(MarketDashboardPresenter.DepthRow::price)
        .containsExactly(new BigDecimal("101"), new BigDecimal("102"), null, null, null);
  }

  @Test
  void totalsExecutableBidAndAskQuantityAcrossTheWholeBook() {
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0.10", 10),
        List.of(), List.of(
            new MarketDataService.DepthLevel(new BigDecimal("98"), 5, true),
            new MarketDataService.DepthLevel(new BigDecimal("97"), 7, false),
            new MarketDataService.DepthLevel(new BigDecimal("96"), 3, true)),
        List.of(new MarketDataService.DepthLevel(new BigDecimal("102"), 4, true),
            new MarketDataService.DepthLevel(new BigDecimal("103"), 6, false),
            new MarketDataService.DepthLevel(new BigDecimal("104"), 2, true)),
        new BigDecimal("2"), new BigDecimal("0.02"));

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot);

    assertThat(rows.executableBidQuantity()).isEqualTo(8);
    assertThat(rows.executableAskQuantity()).isEqualTo(6);
  }

  @Test
  void padsChartsWithExplicitEmptyRowsInsteadOfInventingLiquidityOrTrades() {
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0", 0),
        List.of(), List.of(), List.of(), null, null);

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot);

    assertThat(rows.bids()).hasSize(5).allMatch(MarketDashboardPresenter.DepthRow::empty);
    assertThat(rows.asks()).hasSize(5).allMatch(MarketDashboardPresenter.DepthRow::empty);
    assertThat(rows.candles()).hasSize(9).allMatch(MarketDashboardPresenter.CandleRow::empty);
  }

  @Test
  void treatsOneCandleAsInsufficientTrendData() {
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0", 1),
        List.of(candle("2026-08-26T00:00:00Z", "100", "105", "99", "104", 3)),
        List.of(), List.of(), null, null);

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot);

    assertThat(rows.candles()).hasSize(9).allMatch(MarketDashboardPresenter.CandleRow::empty);
  }

  @Test
  void keepsRecentCandlesChronologicalAndClassifiesPriceDirection() {
    Candle first = candle("2026-08-26T00:00:00Z", "100", "105", "99", "104", 3);
    Candle second = candle("2026-08-26T00:01:00Z", "104", "106", "101", "102", 4);
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0", 7),
        List.of(second, first), List.of(), List.of(), null, null);

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot);

    assertThat(rows.candles().subList(7, 9))
        .extracting(MarketDashboardPresenter.CandleRow::direction)
        .containsExactly(MarketDashboardPresenter.CandleDirection.UP,
            MarketDashboardPresenter.CandleDirection.DOWN);
    assertThat(rows.candles().get(7).candle().bucketStart()).isEqualTo(first.bucketStart());
  }

  @Test
  void aggregatesCandlesIntoTheSelectedTimeframe() {
    List<Candle> minutes = new java.util.ArrayList<>();
    for (int minute = 0; minute < 9; minute++) {
      BigDecimal open = new BigDecimal(100 + minute);
      BigDecimal high = open.add(BigDecimal.ONE);
      BigDecimal low = open.subtract(BigDecimal.ONE);
      BigDecimal close = open.add(new BigDecimal("0.5"));
      minutes.add(new Candle("diamond-usd",
          Instant.parse("2026-08-26T00:00:00Z").plusSeconds(minute * 60L),
          open, high, low, close, minute + 1,
          close.multiply(BigDecimal.valueOf(minute + 1))));
    }
    MarketDashboardSnapshot snapshot = new MarketDashboardSnapshot(row("diamond-usd", "0", 7),
        minutes,
        List.of(), List.of(), null, null);

    MarketDashboardPresenter.DashboardRows rows = presenter.present(snapshot, Duration.ofMinutes(3));

    assertThat(rows.candles()).hasSize(9);
    assertThat(rows.candles().stream().filter(row -> !row.empty())).hasSize(3);
    Candle bucket = rows.candles().stream().filter(row -> !row.empty())
        .map(MarketDashboardPresenter.CandleRow::candle).toList().get(2);
    assertThat(bucket.high()).isEqualByComparingTo("109");
    assertThat(bucket.low()).isEqualByComparingTo("105");
    assertThat(bucket.volume()).isEqualTo(24);
  }

  @Test
  void summarizesMarketBreadthAndUsesNotionalForMostActiveMarket() {
    MarketListPresenter markets = new MarketListPresenter();
    List<MarketListPresenter.Entry> entries = List.of(
        entry("diamond-usd", "Diamond", "0.10", 20, "2000"),
        entry("iron-usd", "Iron", "-0.05", 50, "500"),
        entry("gold-usd", "Gold", "0", 10, "3000"));

    MarketOverviewSnapshot overview = markets.overview(entries);

    assertThat(overview.marketCount()).isEqualTo(3);
    assertThat(overview.risingCount()).isEqualTo(1);
    assertThat(overview.fallingCount()).isEqualTo(1);
    assertThat(overview.totalVolume24h()).isEqualTo(80);
    assertThat(overview.totalNotional24h()).isEqualByComparingTo("5500");
    assertThat(overview.mostActive().marketId()).isEqualTo("gold-usd");
    assertThat(overview.biggestGainer().marketId()).isEqualTo("diamond-usd");
    assertThat(overview.biggestLoser().marketId()).isEqualTo("iron-usd");
  }

  private static MarketDashboardSnapshot snapshot() {
    return new MarketDashboardSnapshot(row("diamond-usd", "0", 0), List.of(), List.of(),
        List.of(), null, null);
  }

  private static MarketRow row(String marketId, String change, long volume) {
    return new MarketRow(marketId, marketId, new BigDecimal("100"), new BigDecimal("99"),
        new BigDecimal("101"), new BigDecimal(change), volume, MarketStatus.OPEN);
  }

  private static Candle candle(String bucket, String open, String high, String low, String close,
                               long volume) {
    return new Candle("diamond-usd", Instant.parse(bucket), new BigDecimal(open),
        new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume,
        new BigDecimal(close).multiply(BigDecimal.valueOf(volume)));
  }

  private static MarketListPresenter.Entry entry(String marketId, String name, String change,
                                                  long volume, String notional) {
    return new MarketListPresenter.Entry(marketId, name, new MarketQuote(marketId,
        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("99"),
        new BigDecimal("101"), new BigDecimal(change), volume, new BigDecimal(notional),
        MarketStatus.OPEN, Instant.EPOCH));
  }
}
