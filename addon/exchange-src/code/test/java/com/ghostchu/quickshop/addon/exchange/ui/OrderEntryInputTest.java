package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEntryInputTest {
  @Test
  void parsesLimitQuantityAndPriceIntoConfirmationRequest() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    ExchangeMenuRequest request = OrderEntryInput.limit(
        requestId, accountId, "diamond/default", OrderSide.BUY, " 2   100.00 ");

    assertThat(request.menuName()).isEqualTo("order-confirm");
    assertThat(request.requestId()).isEqualTo(requestId);
    assertThat(request.order().accountId()).isEqualTo(accountId);
    assertThat(request.order().type()).isEqualTo(OrderType.LIMIT);
    assertThat(request.order().side()).isEqualTo(OrderSide.BUY);
    assertThat(request.order().quantity()).isEqualTo(2);
    assertThat(request.order().price()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(request.order().slippageBoundary()).isNull();
  }

  @Test
  void parsesMarketQuantityAndAbsoluteProtectionBoundary() {
    ExchangeMenuRequest request = OrderEntryInput.market(
        UUID.randomUUID(), UUID.randomUUID(), "diamond/default", OrderSide.SELL, "5 95.00");

    assertThat(request.order().type()).isEqualTo(OrderType.MARKET);
    assertThat(request.order().side()).isEqualTo(OrderSide.SELL);
    assertThat(request.order().quantity()).isEqualTo(5);
    assertThat(request.order().price()).isNull();
    assertThat(request.order().slippageBoundary()).isEqualByComparingTo("95.00");
  }

  @Test
  void rejectsMissingExtraMalformedOrNonPositiveFields() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    assertThatThrownBy(() -> OrderEntryInput.limit(
        requestId, accountId, "diamond/default", OrderSide.BUY, "2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderEntryInput.limit(
        requestId, accountId, "diamond/default", OrderSide.BUY, "2 100 3"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderEntryInput.limit(
        requestId, accountId, "diamond/default", OrderSide.BUY, "lots 100"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderEntryInput.market(
        requestId, accountId, "diamond/default", OrderSide.BUY, "0 105"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderEntryInput.market(
        requestId, accountId, "diamond/default", OrderSide.BUY, "2 -1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
