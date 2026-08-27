package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmationTest {
  @Test
  void freezesAbsoluteBoundaryAtConfirmationTime() {
    OrderConfirmation confirmation = OrderConfirmation.market(
        OrderSide.BUY, "diamond-usd", 5, new BigDecimal("100.00"),
        new BigDecimal("0.05"), new BigDecimal("0.002"), new BigDecimal("0.01"), 2, 2);

    assertThat(confirmation.slippageBoundary()).isEqualByComparingTo("105.00");
    assertThat(confirmation.maximumNotional()).isEqualByComparingTo("525.00");
    assertThat(confirmation.maximumFee()).isEqualByComparingTo("1.05");
    assertThat(confirmation.maximumFrozenCurrency()).isEqualByComparingTo("526.05");
  }
}
