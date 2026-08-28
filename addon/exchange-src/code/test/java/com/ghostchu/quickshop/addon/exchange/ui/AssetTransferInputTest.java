package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetTransferInputTest {
  @Test
  void parsesCurrencyInputIntoMoneyTransferDraft() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    ExchangeMenuRequest request = AssetTransferInput.currency(
        requestId, accountId, ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT,
        "default", " 12.50 ");

    assertThat(request.menuName()).isEqualTo("transfer-confirm");
    assertThat(request.requestId()).isEqualTo(requestId);
    assertThat(request.transfer().amount()).isEqualByComparingTo(new BigDecimal("12.50"));
    assertThat(request.transfer().quantity()).isZero();
  }

  @Test
  void parsesItemInputIntoQuantityTransferDraft() {
    ExchangeMenuRequest request = AssetTransferInput.item(
        UUID.randomUUID(), UUID.randomUUID(), ExchangeMenuRequest.TransferKind.ITEM_WITHDRAWAL,
        "diamond/default", " 32 ");

    assertThat(request.transfer().quantity()).isEqualTo(32);
    assertThat(request.transfer().marketId()).isEqualTo("diamond/default");
    assertThat(request.transfer().amount()).isNull();
  }

  @Test
  void rejectsMalformedOrNonPositiveInput() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    assertThatThrownBy(() -> AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT, "default", "coins"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT, "default", "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT, "default", "-5"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsCurrencyAmountsWithExcessivePrecisionOrScale() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    assertThatThrownBy(() -> AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT, "default", "0.00001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT, "default", "1000000000000000000000"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsCurrencyAmountsAtTheSupportedPrecisionBoundary() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ExchangeMenuRequest request = AssetTransferInput.currency(requestId, accountId,
        ExchangeMenuRequest.TransferKind.MONEY_WITHDRAWAL, "default", "0.0001");

    assertThat(request.transfer().amount()).isEqualByComparingTo(new BigDecimal("0.0001"));
  }

  @Test
  void rejectsMalformedOrNonPositiveItemInput() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    assertThatThrownBy(() -> AssetTransferInput.item(requestId, accountId,
        ExchangeMenuRequest.TransferKind.ITEM_DEPOSIT, "diamond/default", "-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
