package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.math.BigDecimal;
import java.util.Locale;
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

  @Test
  void localizesRiskRejectionsAndFallsBackToRawReason() {
    AddonMessageService service = new AddonMessageService(Map.of(
        "en-US", Map.of("ui-reject-rate-limited", "Slow down",
            "ui-reject-fallback", "Rejected: <0>"),
        "zh-CN", Map.of("ui-reject-rate-limited", "请稍后再试",
            "ui-reject-fallback", "被拒绝：<0>")));

    assertThat(ExchangeUiMessages.localizeReason(service, Locale.CHINA, "RATE_LIMITED"))
        .isEqualTo("请稍后再试");
    assertThat(ExchangeUiMessages.localizeReason(service, Locale.US, "MARKET_NOT_OPEN"))
        .isEqualTo("Rejected: MARKET_NOT_OPEN");
    assertThat(ExchangeUiMessages.localizeReason(service, Locale.US, "database locked"))
        .isEqualTo("Rejected: database locked");
    assertThat(ExchangeUiMessages.localizeReason(service, Locale.US, null)).isEmpty();
  }
}
