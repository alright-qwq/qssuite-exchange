package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRiskServiceTest {
  @Test
  void rejectsSixthOperationInSecondAndSixtyFirstInMinute() {
    OrderRateLimiter limiter = new OrderRateLimiter(5, 60);
    UUID account = UUID.randomUUID();
    Instant now = Instant.EPOCH;
    for (int index = 0; index < 5; index++) {
      assertThat(limiter.allow(account, now)).isTrue();
    }
    assertThat(limiter.allow(account, now)).isFalse();
    assertThat(limiter.allow(account, now.plusSeconds(1))).isTrue();
  }

  @Test
  void clockRollbackCannotBypassTheRollingWindow() {
    OrderRateLimiter limiter = new OrderRateLimiter(5, 60);
    UUID account = UUID.randomUUID();
    Instant now = Instant.EPOCH.plusSeconds(100);
    for (int index = 0; index < 5; index++) {
      assertThat(limiter.allow(account, now)).isTrue();
    }

    // A rolled-back clock must be evaluated at the newest previously seen instant and rejected.
    assertThat(limiter.allow(account, now.minusSeconds(30))).isFalse();
    assertThat(limiter.allow(account, now.minusSeconds(30))).isFalse();
  }

  @Test
  void enforcesAccountExposureLimits() {
    AccountRiskSnapshot snapshot = new AccountRiskSnapshot(
        100_000, new BigDecimal("10000000.00"), 100);

    assertThat(snapshot.canAddHolding(1, 100_000)).isFalse();
    assertThat(snapshot.canFreeze(new BigDecimal("0.01"), new BigDecimal("10000000.00"))).isFalse();
    assertThat(snapshot.canOpenOrder(100)).isFalse();
  }

  @Test
  void holdingOverflowCannotBypassTheLimit() {
    AccountRiskSnapshot snapshot = new AccountRiskSnapshot(
        Long.MAX_VALUE, BigDecimal.ZERO, 0);

    assertThat(snapshot.canAddHolding(1, Long.MAX_VALUE)).isFalse();
  }

  @Test
  void negativeOrOverflowingLimitsAreRejected() {
    AccountRiskSnapshot snapshot = new AccountRiskSnapshot(0, BigDecimal.ZERO, 0);

    assertThat(snapshot.canAddHolding(1, -1)).isFalse();
    assertThat(snapshot.canAddHolding(-1, 1)).isFalse();
    assertThat(snapshot.canAddHolding(1, Long.MAX_VALUE)).isTrue();
    assertThat(snapshot.canFreeze(new BigDecimal("0.01"), new BigDecimal("-1"))).isFalse();
    assertThat(snapshot.canFreeze(new BigDecimal("-0.01"), new BigDecimal("1"))).isFalse();
  }

  @Test
  void rejectsMarketOrderWhoseProtectionExceedsMaximumSlippage() {
    OrderRiskService service = new OrderRiskService(new OrderRateLimiter(5, 60));

    assertThat(service.checkMarketSlippage(
        new BigDecimal("125.00"), new BigDecimal("100.00"), new BigDecimal("0.20")))
        .isEqualTo(OrderRiskService.RejectReason.SLIPPAGE_TOO_HIGH);
  }
}
