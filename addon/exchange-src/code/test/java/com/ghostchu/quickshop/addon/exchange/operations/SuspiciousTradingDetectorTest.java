package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.Alert;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.OrderActivity;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.TradeActivity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTradingDetectorTest {
  private final Instant now = Instant.parse("2026-08-27T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final String market = "concept-stock";

  @Test
  void flagsReciprocalTradesWithinTheWindow() {
    SuspiciousTradingDetector detector = new SuspiciousTradingDetector(clock);

    var result = detector.scan(List.of(
        trade(alice, bob, now.minusSeconds(90)),
        trade(alice, bob, now.minusSeconds(30)),
        trade(bob, alice, now.minusSeconds(10))), List.of());

    assertThat(result.alerts()).singleElement().satisfies(alert -> {
      assertThat(alert.type()).isEqualTo("HIGH_FREQUENCY_RECIPROCAL_TRADING");
      assertThat(alert.severity()).isEqualTo("MEDIUM");
      assertThat(Set.of(alert.accountId(), alert.counterpartyId())).containsExactlyInAnyOrder(alice, bob);
    });
  }

  @Test
  void doesNotFlagOldOrDistinctTrades() {
    SuspiciousTradingDetector detector = new SuspiciousTradingDetector(clock);
    UUID carol = UUID.randomUUID();

    var result = detector.scan(List.of(
        trade(alice, bob, now.minusSeconds(600)),
        trade(alice, carol, now.minusSeconds(5))), List.of());

    assertThat(result.alerts()).isEmpty();
  }

  @Test
  void includesActivityAtTheExactWindowBoundaryButNotOlder() {
    Instant boundary = now.minus(SuspiciousTradingDetector.ACTIVITY_WINDOW);

    var atBoundary = new SuspiciousTradingDetector(clock).scan(
        List.of(), cancelPlaceActivities(boundary, 0));

    assertThat(atBoundary.alerts()).hasSize(1);

    var older = new SuspiciousTradingDetector(clock).scan(
        List.of(), cancelPlaceActivities(boundary, -1));

    assertThat(older.alerts()).isEmpty();
  }

  @Test
  void deduplicatesRepeatedScansWithinTheWindow() {
    SuspiciousTradingDetector detector = new SuspiciousTradingDetector(clock);
    List<TradeActivity> trades = List.of(
        trade(alice, bob, now.minusSeconds(90)),
        trade(alice, bob, now.minusSeconds(30)),
        trade(bob, alice, now.minusSeconds(10)));

    var first = detector.scan(trades, List.of());
    var second = detector.scan(trades, List.of());

    assertThat(first.alerts()).hasSize(1);
    assertThat(second.alerts()).isEmpty();
  }

  @Test
  void flagsHighCancelPlaceRatioAfterEnoughSamples() {
    SuspiciousTradingDetector detector = new SuspiciousTradingDetector(clock);
    List<OrderActivity> activities = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      activities.add(activity(market, OrderActivity.Kind.PLACE, now.minusSeconds(60 + i)));
    }
    for (int i = 0; i < 14; i++) {
      activities.add(activity(market, OrderActivity.Kind.CANCEL, now.minusSeconds(30 - i)));
    }

    var result = detector.scan(List.of(), activities);

    assertThat(result.alerts()).singleElement().satisfies(alert -> {
      assertThat(alert.type()).isEqualTo("HIGH_CANCEL_PLACE_RATIO");
      assertThat(alert.marketId()).isEqualTo(market);
      assertThat(alert.accountId()).isNull();
    });
  }

  @Test
  void ignoresCancelPlaceRatioWithTooFewSamples() {
    SuspiciousTradingDetector detector = new SuspiciousTradingDetector(clock);

    var result = detector.scan(List.of(), List.of(
        activity(market, OrderActivity.Kind.PLACE, now.minusSeconds(60)),
        activity(market, OrderActivity.Kind.CANCEL, now.minusSeconds(30)),
        activity(market, OrderActivity.Kind.CANCEL, now.minusSeconds(10))));

    assertThat(result.alerts()).isEmpty();
  }

  private TradeActivity trade(UUID buyer, UUID seller, Instant at) {
    return new TradeActivity(buyer, seller, market, at);
  }

  /** 12 places plus 9 cancels: ratio 0.75 at the boundary, 8/12 inside when one cancel ages out. */
  private List<OrderActivity> cancelPlaceActivities(Instant boundary, int firstCancelOffsetSeconds) {
    List<OrderActivity> activities = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      activities.add(activity(market, OrderActivity.Kind.PLACE, boundary.plusSeconds(i)));
    }
    activities.add(activity(market, OrderActivity.Kind.CANCEL,
        boundary.plusSeconds(firstCancelOffsetSeconds)));
    for (int i = 0; i < 8; i++) {
      activities.add(activity(market, OrderActivity.Kind.CANCEL, boundary.plusSeconds(12 + i)));
    }
    return activities;
  }

  private static OrderActivity activity(String marketId, OrderActivity.Kind kind, Instant at) {
    return new OrderActivity(marketId, UUID.randomUUID(), kind, at);
  }
}
