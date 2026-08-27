package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ExchangeActionServiceTest {
  @Test
  void rejectsAnOrderForAnUnknownMarketBeforeCallingAService() {
    ExchangeMenuRequest.OrderDraft draft = new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), UUID.randomUUID(), "missing", OrderSide.BUY, OrderType.LIMIT,
        new BigDecimal("1.00"), null, 1);
    ExchangeActionService actions = new ExchangeActionService(Map.of(), transfers());

    assertThatThrownBy(() -> actions.submitOrder(draft))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown market");
  }

  @Test
  void rejectsItemTransfersForVirtualSecurityMarkets() {
    ExchangeMenuRequest.TransferDraft draft = new ExchangeMenuRequest.TransferDraft(
        UUID.randomUUID(), UUID.randomUUID(), ExchangeMenuRequest.TransferKind.ITEM_DEPOSIT,
        "concept_alpha", null, 1, "concept_alpha");
    PersistentOrderService physicalService = new PersistentOrderService(
        new com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository() {
          @Override
          public <T> T inTransaction(TransactionWork<T> work) {
            throw new AssertionError();
          }
        },
        com.ghostchu.quickshop.addon.exchange.core.TestFixtures.rules(),
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP,
        com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits.defaults(),
        null, ItemAssetCustody.INSTANCE);
    ExchangeActionService actions = new ExchangeActionService(Map.of("concept_alpha",
        physicalService), transfers(), marketId -> marketId.equals("concept_alpha"));

    assertThatThrownBy(() -> actions.submitTransfer(draft))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("virtual security markets do not support item transfers");
  }

  @Test
  void withMarketRoutesNewMarketToItsOrderServiceAndTreatsItAsVirtual() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    PersistentOrderService original = fixture.service();
    ExchangeActionService actions = new ExchangeActionService(
        Map.of(fixture.rules().marketId(), original), transfers(), marketId -> false);

    ExchangeActionService extended = actions.withMarket("concept_beta", original);

    assertThat(extended.market("concept_beta")).isSameAs(original);
    assertThatThrownBy(() -> extended.submitTransfer(new ExchangeMenuRequest.TransferDraft(
        UUID.randomUUID(), UUID.randomUUID(), ExchangeMenuRequest.TransferKind.ITEM_DEPOSIT,
        "concept_beta", null, 1, "concept_beta")))
        .hasMessageContaining("virtual security markets do not support item transfers");
    assertThatThrownBy(() -> actions.withMarket(fixture.rules().marketId(), original))
        .hasMessageContaining("already exists");
  }

  private static ExchangeActionService.TransferActions transfers() {
    return new ExchangeActionService.TransferActions() {
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          moneyDeposit(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          moneyWithdrawal(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          itemDeposit(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          itemWithdrawal(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
    };
  }
}
