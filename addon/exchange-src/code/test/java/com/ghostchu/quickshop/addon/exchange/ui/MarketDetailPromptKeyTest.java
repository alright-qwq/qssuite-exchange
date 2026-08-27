package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDetailPromptKeyTest {
  @Test
  void selectsBasePromptWhenNoExecutableQuoteExists() {
    assertThat(MarketDetailPage.promptKey(OrderType.LIMIT, false))
        .isEqualTo("ui-order-limit-prompt");
    assertThat(MarketDetailPage.promptKey(OrderType.MARKET, false))
        .isEqualTo("ui-order-market-prompt");
  }

  @Test
  void selectsHintPromptWhenBestPriceIsVisible() {
    assertThat(MarketDetailPage.promptKey(OrderType.LIMIT, true))
        .isEqualTo("ui-order-limit-prompt-hint");
    assertThat(MarketDetailPage.promptKey(OrderType.MARKET, true))
        .isEqualTo("ui-order-market-prompt-hint");
  }
}
