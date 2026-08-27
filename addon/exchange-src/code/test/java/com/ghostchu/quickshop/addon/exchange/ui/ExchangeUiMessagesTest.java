package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeUiMessagesTest {
  @Test
  void formatsCurrencyWithConfiguredScaleAndFallsBackToTwoDecimals() {
    ExchangeUiMessages messages = new ExchangeUiMessages(
        new AddonMessageService(Map.of("en-US", Map.of())));

    assertThat(messages.formatCurrency(new BigDecimal("123.456"), 3))
        .isEqualTo("123.456");
    assertThat(messages.formatCurrency(new BigDecimal("123.456"), 2))
        .isEqualTo("123.46");
    assertThat(messages.formatCurrency(new BigDecimal("123.456")))
        .isEqualTo("123.46");
    assertThat(messages.formatCurrency(null)).isEqualTo("-");
  }

  @Test
  void remembersLatestPriceScaleForAggregateDisplays() {
    ExchangeUiMessages messages = new ExchangeUiMessages(
        new AddonMessageService(Map.of("en-US", Map.of())));

    messages.notePriceScale(3);

    assertThat(messages.lastPriceScale()).isEqualTo(3);
    assertThat(messages.formatCurrency(new BigDecimal("1.005"))).isEqualTo("1.005");
  }
}
