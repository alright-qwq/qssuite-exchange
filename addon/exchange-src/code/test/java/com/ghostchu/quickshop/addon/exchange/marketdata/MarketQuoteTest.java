package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketQuoteTest {
  @Test
  void rejectsNegativeRollingVolume() {
    assertThatThrownBy(() -> new MarketQuote("diamond-usd", null, new BigDecimal("100.00"),
        null, null, BigDecimal.ZERO, -1, BigDecimal.ZERO, MarketStatus.OPEN, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void volatilityUsesThe24hHighLowSpreadOverTheLatestClose() {
    BigDecimal volatility = MarketDataService.volatility(List.of(
        new com.ghostchu.quickshop.addon.exchange.marketdata.Candle("diamond-usd",
            Instant.EPOCH, new BigDecimal("100"), new BigDecimal("110"),
            new BigDecimal("90"), new BigDecimal("105"), 3,
            new BigDecimal("300")),
        new com.ghostchu.quickshop.addon.exchange.marketdata.Candle("diamond-usd",
            Instant.EPOCH.plusSeconds(60), new BigDecimal("105"), new BigDecimal("108"),
            new BigDecimal("95"), new BigDecimal("100"), 2,
            new BigDecimal("200"))),
        new BigDecimal("100"));

    assertThat(volatility).isEqualByComparingTo("0.2");
  }

  @Test
  void volatilityRequiresAtLeastTwoCandles() {
    assertThat(MarketDataService.volatility(List.of(), new BigDecimal("100"))).isNull();
  }

  @Test
  void carriesTwentyFourHourHighAndLow() {
    MarketQuote quote = new MarketQuote("diamond-usd", new BigDecimal("100"),
        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"),
        BigDecimal.ZERO, 10, new BigDecimal("1000"), MarketStatus.OPEN, Instant.EPOCH,
        new BigDecimal("0.1"), new BigDecimal("110"), new BigDecimal("90"));

    assertThat(quote.high24h()).isEqualByComparingTo("110");
    assertThat(quote.low24h()).isEqualByComparingTo("90");
  }
}
