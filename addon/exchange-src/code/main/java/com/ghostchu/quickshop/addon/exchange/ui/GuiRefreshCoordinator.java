package com.ghostchu.quickshop.addon.exchange.ui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coalesces market-driven UI renders so each subscribed player refreshes at most once per period. */
public final class GuiRefreshCoordinator implements AutoCloseable {
  private final Clock clock;
  private final Duration minimumInterval;
  private final Map<UUID, Subscription> subscriptions = new HashMap<>();
  private boolean dirty;

  public GuiRefreshCoordinator(Clock clock, Duration minimumInterval) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.minimumInterval = Objects.requireNonNull(minimumInterval, "minimumInterval");
    if (minimumInterval.isZero() || minimumInterval.isNegative()) {
      throw new IllegalArgumentException("minimum interval must be positive");
    }
  }

  public synchronized void subscribe(UUID playerId, Runnable render) {
    subscriptions.put(Objects.requireNonNull(playerId, "playerId"),
        new Subscription(Objects.requireNonNull(render, "render"), clock.instant().minus(minimumInterval)));
  }

  public synchronized void unsubscribe(UUID playerId) {
    subscriptions.remove(Objects.requireNonNull(playerId, "playerId"));
  }

  public synchronized void marketChanged() {
    dirty = true;
  }

  public synchronized void tick() {
    if (!dirty) {
      return;
    }
    Instant now = clock.instant();
    boolean pending = false;
    for (Subscription subscription : subscriptions.values()) {
      if (Duration.between(subscription.lastRender, now).compareTo(minimumInterval) < 0) {
        pending = true;
        continue;
      }
      subscription.render.run();
      subscription.lastRender = now;
    }
    dirty = pending;
  }

  @Override
  public synchronized void close() {
    subscriptions.clear();
    dirty = false;
  }

  private static final class Subscription {
    private final Runnable render;
    private Instant lastRender;

    private Subscription(Runnable render, Instant lastRender) {
      this.render = render;
      this.lastRender = lastRender;
    }
  }
}
