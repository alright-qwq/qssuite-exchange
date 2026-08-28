package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Detects suspicious trading patterns on immutable read snapshots and only emits alerts.
 * Never mutates accounts, orders or balances; callers decide how to persist alerts.
 */
public final class SuspiciousTradingDetector {
  /** Two accounts trading both directions at least this often inside the window is suspicious. */
  private static final int RECIPROCAL_MIN_TRADES = 2;
  /** Cancel/place ratio above this threshold is suspicious once enough samples exist. */
  private static final double CANCEL_PLACE_RATIO_THRESHOLD = 0.75;
  private static final int CANCEL_PLACE_MIN_SAMPLES = 20;
  private static final Duration RECIPROCAL_WINDOW = Duration.ofMinutes(5);
  /** Lookback window the caller must cover when loading order activity for a scan. */
  public static final Duration ACTIVITY_WINDOW = Duration.ofMinutes(10);
  private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(10);

  private final Clock clock;
  private final Map<String, Instant> dedupeAt = new HashMap<>();

  public SuspiciousTradingDetector(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ScanResult scan(List<TradeActivity> trades, List<OrderActivity> orders) {
    Instant now = clock.instant();
    prune(now);
    List<Alert> alerts = new ArrayList<>();
    alerts.addAll(reciprocalTrades(trades, now));
    alerts.addAll(cancelPlaceRatio(orders, now));
    alerts.removeIf(alert -> !dedupe(alert));
    return new ScanResult(List.copyOf(alerts));
  }

  private List<Alert> reciprocalTrades(List<TradeActivity> trades, Instant now) {
    if (trades == null || trades.isEmpty()) {
      return List.of();
    }
    Instant cutoff = now.minus(RECIPROCAL_WINDOW);
    Map<PairKey, List<Instant>> pairs = new HashMap<>();
    for (TradeActivity trade : trades) {
      if (trade.executedAt().isBefore(cutoff) || trade.executedAt().isAfter(now)) {
        continue;
      }
      PairKey key = PairKey.of(trade.buyerAccountId(), trade.sellerAccountId());
      pairs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(trade.executedAt());
    }
    List<Alert> alerts = new ArrayList<>();
    for (Map.Entry<PairKey, List<Instant>> pair : pairs.entrySet()) {
      List<Instant> times = pair.getValue();
      times.sort(Comparator.naturalOrder());
      for (int i = 0; i + RECIPROCAL_MIN_TRADES - 1 < times.size(); i++) {
        Instant first = times.get(i);
        Instant last = times.get(i + RECIPROCAL_MIN_TRADES - 1);
        if (Duration.between(first, last).compareTo(RECIPROCAL_WINDOW) <= 0) {
          alerts.add(new Alert("HIGH_FREQUENCY_RECIPROCAL_TRADING", "MEDIUM", now,
              pair.getKey().first() + ":" + pair.getKey().second(),
              "trades=" + times.size() + " within=" + RECIPROCAL_WINDOW,
              pair.getKey().first(), pair.getKey().second()));
          break;
        }
      }
    }
    return alerts;
  }

  private List<Alert> cancelPlaceRatio(List<OrderActivity> orders, Instant now) {
    if (orders == null || orders.isEmpty()) {
      return List.of();
    }
    Instant cutoff = now.minus(ACTIVITY_WINDOW);
    Map<String, int[]> byMarket = new HashMap<>();
    for (OrderActivity activity : orders) {
      if (activity.at().isBefore(cutoff) || activity.at().isAfter(now)) {
        continue;
      }
      int[] counts = byMarket.computeIfAbsent(activity.marketId(), ignored -> new int[2]);
      if (activity.kind() == OrderActivity.Kind.PLACE) {
        counts[0]++;
      } else if (activity.kind() == OrderActivity.Kind.CANCEL) {
        counts[1]++;
      }
    }
    List<Alert> alerts = new ArrayList<>();
    for (Map.Entry<String, int[]> market : byMarket.entrySet()) {
      int placed = market.getValue()[0];
      int cancelled = market.getValue()[1];
      if (placed + cancelled < CANCEL_PLACE_MIN_SAMPLES) {
        continue;
      }
      double ratio = placed == 0 ? Double.POSITIVE_INFINITY : (double) cancelled / placed;
      if (ratio >= CANCEL_PLACE_RATIO_THRESHOLD) {
        alerts.add(new Alert("HIGH_CANCEL_PLACE_RATIO", "MEDIUM", now, market.getKey(),
            "placed=" + placed + ",cancelled=" + cancelled + ",ratio=" + ratio,
            null, null));
      }
    }
    return alerts;
  }

  private boolean dedupe(Alert alert) {
    String key = alert.type() + ":" + alert.marketId() + ":" + Objects.toString(alert.accountId(), "-");
    Instant existing = dedupeAt.get(key);
    if (existing != null && !alert.at().isBefore(existing)) {
      return false;
    }
    dedupeAt.put(key, alert.at());
    return true;
  }

  private void prune(Instant now) {
    dedupeAt.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now)
        .compareTo(DEDUPE_WINDOW) > 0);
  }

  public record TradeActivity(UUID buyerAccountId, UUID sellerAccountId, String marketId,
                              Instant executedAt) {
    public TradeActivity {
      Objects.requireNonNull(buyerAccountId, "buyerAccountId");
      Objects.requireNonNull(sellerAccountId, "sellerAccountId");
      if (marketId == null || marketId.isBlank()) {
        throw new IllegalArgumentException("marketId is required");
      }
      Objects.requireNonNull(executedAt, "executedAt");
      if (buyerAccountId.equals(sellerAccountId)) {
        throw new IllegalArgumentException("trade parties must be distinct");
      }
    }
  }

  public record OrderActivity(String marketId, UUID accountId, Kind kind, Instant at) {
    public OrderActivity {
      if (marketId == null || marketId.isBlank()) {
        throw new IllegalArgumentException("marketId is required");
      }
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(at, "at");
    }

    public enum Kind {
      PLACE, CANCEL
    }
  }

  public record Alert(String type, String severity, Instant at, String marketId,
                      String evidence, UUID accountId, UUID counterpartyId) {
    public Alert {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("type is required");
      }
      if (severity == null || severity.isBlank()) {
        throw new IllegalArgumentException("severity is required");
      }
      Objects.requireNonNull(at, "at");
    }
  }

  public record ScanResult(List<Alert> alerts) {
    public ScanResult {
      alerts = List.copyOf(alerts);
    }
  }

  private record PairKey(UUID first, UUID second) {
    static PairKey of(UUID a, UUID b) {
      return a.compareTo(b) <= 0 ? new PairKey(a, b) : new PairKey(b, a);
    }
  }
}
