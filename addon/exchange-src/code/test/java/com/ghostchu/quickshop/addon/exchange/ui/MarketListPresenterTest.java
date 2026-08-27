package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketListPresenterTest {
  @Test
  void mapsQuotesIntoImmutableRowsInMarketOrder() {
    MarketListPresenter presenter = new MarketListPresenter();
    MarketQuote quote = new MarketQuote("diamond-usd", new BigDecimal("100"),
        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"),
        new BigDecimal("0.01"), 12, new BigDecimal("1200"), MarketStatus.OPEN, Instant.EPOCH);
    List<MarketRow.TradeLore> recent = List.of(
        new MarketRow.TradeLore(new BigDecimal("99.5"), 2, "BUY", true));
    MarketListPresenter.Entry entry = new MarketListPresenter.Entry("diamond-usd", "Diamond",
        quote, null, null, null, null, null, recent);

    assertThat(presenter.rows(List.of(entry)))
        .containsExactly(new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
            new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("0.01"), 12,
            MarketStatus.OPEN, null, null, null, null, null, null, null, null,
            new BigDecimal("1200"), recent));
  }

  @Test
  void carriesRecentTradesIntoRowsAndToleratesMissingTrades() {
    MarketListPresenter presenter = new MarketListPresenter();
    MarketQuote quote = new MarketQuote("alpha", new BigDecimal("10"),
        new BigDecimal("10"), new BigDecimal("9"), new BigDecimal("11"),
        BigDecimal.ZERO, 5, new BigDecimal("50"), MarketStatus.OPEN, Instant.EPOCH);
    List<MarketRow.TradeLore> recent = List.of(
        new MarketRow.TradeLore(new BigDecimal("10"), 3, "SELL", false));

    MarketRow withTrades = presenter.rows(List.of(
        new MarketListPresenter.Entry("alpha", "Alpha", quote, "VIRTUAL_SECURITY", "ALPHA",
            1000L, "OPEN", 100L, recent))).getFirst();
    MarketRow withoutTrades = presenter.rows(List.of(
        new MarketListPresenter.Entry("alpha", "Alpha", quote))).getFirst();

    assertThat(withTrades.recentTrades()).containsExactly(
        new MarketRow.TradeLore(new BigDecimal("10"), 3, "SELL", false));
    assertThat(withoutTrades.recentTrades()).isEmpty();
  }

  @Test
  void sortsMarketsByNotionalChangeOrLastPrice() {
    MarketRow diamond = new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("0.10"), 20,
        MarketStatus.OPEN, null, null, null, null, null, null, null, null,
        new BigDecimal("2000"));
    MarketRow iron = new MarketRow("iron-usd", "Iron", new BigDecimal("50"),
        new BigDecimal("49"), new BigDecimal("51"), new BigDecimal("-0.05"), 50,
        MarketStatus.OPEN, null, null, null, null, null, null, null, null,
        new BigDecimal("5000"));
    List<MarketRow> rows = List.of(diamond, iron);

    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.NOTIONAL))
        .extracting(MarketRow::marketId).containsExactly("iron-usd", "diamond-usd");
    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.CHANGE))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "iron-usd");
    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.LAST))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "iron-usd");
    assertThat(MarketListSnapshot.SortMode.NOTIONAL.next()).isEqualTo(
        MarketListSnapshot.SortMode.CHANGE);
  }

  @Test
  void formatsPercentFractionsToTwoDecimalPlaces() {
    assertThat(MarketListPage.percent(new BigDecimal("0.12345678")))
        .isEqualTo("12.35%");
    assertThat(MarketListPage.percent(new BigDecimal("-0.004")))
        .isEqualTo("-0.4%");
    assertThat(MarketListPage.percent(null)).isEqualTo("-");
    assertThat(MarketListPage.percent(new BigDecimal("0.00001")))
        .isEqualTo("0%");
  }

  @Test
  void sortsNullChangeAndNullLastMarketsWithoutThrowing() {
    MarketRow noTrades = new MarketRow("new-stock", "New Stock", null, null, null, null, 0,
        MarketStatus.OPEN, "VIRTUAL_SECURITY", "NEW", 1000L, "OPEN", null, null, null, null, null);
    MarketRow traded = new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("0.10"), 20,
        MarketStatus.OPEN, null, null, null, null, null, null, null, null,
        new BigDecimal("2000"));
    List<MarketRow> rows = List.of(noTrades, traded);

    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.CHANGE))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "new-stock");
    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.LAST))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "new-stock");
  }

  @Test
  void filtersMarketsByAssetType() {
    MarketRow security = new MarketRow("concept_alpha", "Alpha", new BigDecimal("10"),
        new BigDecimal("9"), new BigDecimal("11"), BigDecimal.ZERO, 10, MarketStatus.OPEN,
        "VIRTUAL_SECURITY", "ALPHA", 1000L, "OPEN", null, null, null, null, null);
    MarketRow item = new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
        new BigDecimal("99"), new BigDecimal("101"), BigDecimal.ZERO, 10, MarketStatus.OPEN);
    List<MarketRow> rows = List.of(security, item);

    assertThat(MarketListSnapshot.filtered(rows, "SECURITY"))
        .extracting(MarketRow::marketId).containsExactly("concept_alpha");
    assertThat(MarketListSnapshot.filtered(rows, "ITEM"))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd");
    assertThat(MarketListSnapshot.filtered(rows, "ALL")).hasSize(2);
  }
}
