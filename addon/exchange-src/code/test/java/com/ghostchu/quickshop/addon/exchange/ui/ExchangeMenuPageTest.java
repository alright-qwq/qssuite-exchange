package com.ghostchu.quickshop.addon.exchange.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeMenuPageTest {
  @Test
  void routesEverySupportedCommandTargetToADistinctPage() {
    assertThat(ExchangeMenuPage.forName("markets")).isEqualTo(ExchangeMenuPage.MARKETS);
    assertThat(ExchangeMenuPage.forName("market-detail")).isEqualTo(ExchangeMenuPage.MARKET_DETAIL);
    assertThat(ExchangeMenuPage.forName("order-confirm")).isEqualTo(ExchangeMenuPage.ORDER_CONFIRM);
    assertThat(ExchangeMenuPage.forName("cancel-confirm")).isEqualTo(ExchangeMenuPage.CANCEL_CONFIRM);
    assertThat(ExchangeMenuPage.forName("transfer-confirm")).isEqualTo(ExchangeMenuPage.TRANSFER_CONFIRM);
    assertThat(ExchangeMenuPage.forName("orders")).isEqualTo(ExchangeMenuPage.ORDERS);
    assertThat(ExchangeMenuPage.forName("assets")).isEqualTo(ExchangeMenuPage.ASSETS);
    assertThat(ExchangeMenuPage.forName("history")).isEqualTo(ExchangeMenuPage.HISTORY);
  }

  @Test
  void rejectsUnknownTargetsInsteadOfFallingBackToMarkets() {
    assertThatThrownBy(() -> ExchangeMenuPage.forName("arbitrary"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown exchange menu");
  }
}
