package com.ghostchu.quickshop.addon.exchange.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuiRefreshCoordinatorTest {
  @Test
  void coalescesUpdatesToOnePerSecondAndStopsAfterClose() {
    MutableClock clock = new MutableClock(Instant.EPOCH);
    GuiRefreshCoordinator refresh = new GuiRefreshCoordinator(clock, Duration.ofSeconds(1));
    UUID player = UUID.randomUUID();
    AtomicInteger renders = new AtomicInteger();
    refresh.subscribe(player, renders::incrementAndGet);

    refresh.marketChanged();
    refresh.marketChanged();
    refresh.tick();
    assertThat(renders).hasValue(1);

    refresh.marketChanged();
    refresh.tick();
    assertThat(renders).hasValue(1);

    refresh.unsubscribe(player);
    clock.advance(Duration.ofSeconds(1));
    refresh.tick();
    assertThat(renders).hasValue(1);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }
  }
}
