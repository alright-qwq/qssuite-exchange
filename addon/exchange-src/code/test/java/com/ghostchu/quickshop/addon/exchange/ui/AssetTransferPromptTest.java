package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetTransferPromptTest {
  @Test
  void keepsWaitingAfterInvalidInputAndStoresOnlyAValidRequest() {
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    List<String> feedback = new ArrayList<>();
    AssetTransferPrompt prompts = new AssetTransferPrompt(contexts, () -> requestId);
    var handler = prompts.currency(accountId, ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT,
        "default", feedback::add);

    assertThat(handler.apply("not-money")).isFalse();
    assertThat(contexts.get(accountId)).isEmpty();
    assertThat(feedback).containsExactly("Enter a positive money amount.");

    assertThat(handler.apply("25.00")).isTrue();
    assertThat(contexts.get(accountId)).get().satisfies(request -> {
      assertThat(request.menuName()).isEqualTo("transfer-confirm");
      assertThat(request.requestId()).isEqualTo(requestId);
      assertThat(request.transfer().kind())
          .isEqualTo(ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT);
    });
  }

  @Test
  void storesItemWithdrawalWithTheSelectedMarket() {
    UUID accountId = UUID.randomUUID();
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    AssetTransferPrompt prompts = new AssetTransferPrompt(contexts, UUID::randomUUID);

    assertThat(prompts.item(accountId, ExchangeMenuRequest.TransferKind.ITEM_WITHDRAWAL,
        "diamond/default", ignored -> {}).apply("16")).isTrue();

    assertThat(contexts.get(accountId)).get().satisfies(request -> {
      assertThat(request.transfer().marketId()).isEqualTo("diamond/default");
      assertThat(request.transfer().quantity()).isEqualTo(16);
    });
  }
}
