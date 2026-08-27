package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeMenuRequestTest {
  @Test
  void orderRequestRetainsFixedRequestIdAndAllSubmissionFields() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.order(
        new ExchangeMenuRequest.OrderDraft(requestId, accountId, "diamond-usd", OrderSide.BUY,
            OrderType.LIMIT, new BigDecimal("100.00"), null, 5));

    assertThat(request.requestId()).isEqualTo(requestId);
    assertThat(request.order().accountId()).isEqualTo(accountId);
    assertThat(request.order().price()).isEqualByComparingTo("100.00");
    assertThat(request.order().quantity()).isEqualTo(5);
  }

  @Test
  void rejectsLimitDraftWithoutPrice() {
    assertThatThrownBy(() -> new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", OrderSide.BUY,
        OrderType.LIMIT, null, null, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("price");
  }
}
