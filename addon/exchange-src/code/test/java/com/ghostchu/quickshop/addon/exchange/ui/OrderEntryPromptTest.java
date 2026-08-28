package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEntryPromptTest {
  @Test
  void keepsWaitingAfterInvalidLimitInputAndStoresOnlyValidDraft() {
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    List<String> feedback = new ArrayList<>();
    OrderEntryPrompt prompts = new OrderEntryPrompt(contexts, () -> requestId);
    var handler = prompts.limit(
        accountId, "diamond/default", OrderSide.BUY, () -> feedback.add("localized-invalid"));

    assertThat(handler.apply("not-an-order")).isFalse();
    assertThat(contexts.get(accountId)).isEmpty();
    assertThat(feedback).containsExactly("localized-invalid");

    assertThat(handler.apply("2 100.00")).isTrue();
    assertThat(contexts.get(accountId)).get().satisfies(request -> {
      assertThat(request.menuName()).isEqualTo("order-confirm");
      assertThat(request.requestId()).isEqualTo(requestId);
      assertThat(request.order().marketId()).isEqualTo("diamond/default");
      assertThat(request.order().side()).isEqualTo(OrderSide.BUY);
    });
  }

  @Test
  void storesMarketOrderWithAbsoluteProtectionBoundary() {
    UUID accountId = UUID.randomUUID();
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    OrderEntryPrompt prompts = new OrderEntryPrompt(contexts, UUID::randomUUID);

    assertThat(prompts.market(accountId, "diamond/default", OrderSide.SELL,
        () -> {}).apply("3 90.00")).isTrue();

    assertThat(contexts.get(accountId)).get().satisfies(request -> {
      assertThat(request.order().quantity()).isEqualTo(3);
      assertThat(request.order().slippageBoundary()).isEqualByComparingTo("90.00");
    });
  }
}
