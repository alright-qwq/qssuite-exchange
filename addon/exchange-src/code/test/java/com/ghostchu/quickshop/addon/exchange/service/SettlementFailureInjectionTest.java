package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementFailureInjectionTest {
  @ParameterizedTest
  @EnumSource(SettlementStage.class)
  void rollsBackEverySettlementStage(SettlementStage failingStage) throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    seedCompletedTrade(fixture);
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(limitOrder(seller, OrderSide.SELL));
    ExchangeServiceFixture.DatabaseState before = fixture.databaseState();
    long versionBefore = fixture.marketVersion();
    assertThat(fixture.journalInvariantViolations()).isEmpty();
    AtomicInteger recoveryCalls = new AtomicInteger();
    PersistentOrderService failing = fixture.service(stage -> {
      if (stage == failingStage) {
        throw new InjectedFailure(stage.name());
      }
    }, (marketId, failure) -> recoveryCalls.incrementAndGet());

    assertThatThrownBy(() -> failing.place(limitOrder(buyer, OrderSide.BUY)))
        .isInstanceOf(InjectedFailure.class)
        .hasMessage(failingStage.name());

    assertThat(fixture.databaseState()).isEqualTo(before);
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
    assertThat(fixture.marketVersion()).isEqualTo(versionBefore + 1);
    assertThat(fixture.journalInvariantViolations()).isEmpty();
    assertThat(recoveryCalls).hasValue(1);
  }

  @org.junit.jupiter.api.Test
  void preservesRollbackFailureOnTheInjectedException() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(limitOrder(seller, OrderSide.SELL));
    SQLException rollbackFailure = new SQLException("rollback failed");
    PersistentOrderService failing = fixture.serviceWithRollbackSuppression(stage -> {
      if (stage == SettlementStage.AFTER_RESERVATION) {
        throw new InjectedFailure(stage.name());
      }
    }, rollbackFailure);

    assertThatThrownBy(() -> failing.place(limitOrder(buyer, OrderSide.BUY)))
        .isInstanceOf(InjectedFailure.class)
        .satisfies(failure -> assertThat(failure.getSuppressed())
            .containsExactly(rollbackFailure));
  }

  @org.junit.jupiter.api.Test
  void detectsATradeWhoseJournalsAreMissing() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.insertUnjournaledTrade();

    assertThat(fixture.journalInvariantViolations())
        .anyMatch(violation -> violation.contains("missing trade journals"));
  }

  @org.junit.jupiter.api.Test
  void rollsBackLevelTwoAlertInsertedBeforeTheLastStage() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    executeTrade(fixture, fixture.service(), "110.00");
    fixture.resumeMarket();
    executeTrade(fixture, fixture.service(), "100.00");
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(limitOrder(seller, OrderSide.SELL, "120.12"));
    ExchangeServiceFixture.DatabaseState before = fixture.databaseState();
    long versionBefore = fixture.marketVersion();
    PersistentOrderService failing = fixture.service(stage -> {
      if (stage == SettlementStage.AFTER_REQUEST_RESULT) {
        throw new InjectedFailure(stage.name());
      }
    }, RecoveryHandler.NO_OP);

    assertThatThrownBy(() -> failing.place(limitOrder(buyer, OrderSide.BUY, "120.12")))
        .isInstanceOf(InjectedFailure.class);

    assertThat(fixture.databaseState()).isEqualTo(before);
    assertThat(fixture.highAlertCount()).isZero();
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
    assertThat(fixture.marketVersion()).isEqualTo(versionBefore + 1);
  }

  private static void seedCompletedTrade(ExchangeServiceFixture fixture) throws Exception {
    executeTrade(fixture, fixture.service(), "100.00");
  }

  private static void executeTrade(
      ExchangeServiceFixture fixture, PersistentOrderService service, String price)
      throws Exception {
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    service.place(limitOrder(seller, OrderSide.SELL, price));
    service.place(limitOrder(buyer, OrderSide.BUY, price));
  }

  private static OrderRequest limitOrder(UUID accountId, OrderSide side) {
    return limitOrder(accountId, side, "100.00");
  }

  private static OrderRequest limitOrder(UUID accountId, OrderSide side, String price) {
    return new OrderRequest(UUID.randomUUID(), accountId, "diamond-usd", side,
        "LIMIT", new BigDecimal(price), null, 1);
  }
}
