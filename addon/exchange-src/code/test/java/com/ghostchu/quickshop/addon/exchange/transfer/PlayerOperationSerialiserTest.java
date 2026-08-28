package com.ghostchu.quickshop.addon.exchange.transfer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerOperationSerialiserTest {
  @Test
  void closeWaitsForAcceptedOperationsAndRejectsNewOnes() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(Duration.ofSeconds(2));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var accepted = serialiser.submit(UUID.randomUUID(), () -> {
      started.countDown();
      try {
        assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      }
      return "completed";
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    Thread closer = Thread.ofPlatform().start(serialiser::close);
    Thread.sleep(50L);
    assertThat(closer.isAlive()).isTrue();
    release.countDown();
    closer.join(2_000L);

    assertThat(closer.isAlive()).isFalse();
    assertThat(accepted.join()).isEqualTo("completed");
    assertThatThrownBy(() -> serialiser.submit(UUID.randomUUID(), () -> "late"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void closeStillShutsDownEveryStripeWhenOneStripeTimesOut() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        java.time.Duration.ofMillis(200));
    UUID stuckPlayer = UUID.randomUUID();
    int stuckStripe = Math.floorMod(stuckPlayer.hashCode(), 32);
    UUID otherPlayer = stuckPlayer;
    for (int attempt = 0; attempt < 32; attempt++) {
      otherPlayer = UUID.randomUUID();
      if (Math.floorMod(otherPlayer.hashCode(), 32) != stuckStripe) {
        break;
      }
    }
    UUID latePlayer = otherPlayer;
    CountDownLatch started = new CountDownLatch(1);
    var accepted = serialiser.submit(stuckPlayer, () -> {
      started.countDown();
      try {
        // Never releases until interrupted by shutdownNow after the drain timeout.
        Thread.sleep(30_000L);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      return "stuck";
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(serialiser::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");

    // Every stripe (including those after the timed-out one) must reject new work now.
    java.util.function.Supplier<String> lateWork = () -> "late";
    assertThatThrownBy(() -> {
      serialiser.submit(latePlayer, lateWork);
    })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    accepted.get(2, TimeUnit.SECONDS);
  }
}
