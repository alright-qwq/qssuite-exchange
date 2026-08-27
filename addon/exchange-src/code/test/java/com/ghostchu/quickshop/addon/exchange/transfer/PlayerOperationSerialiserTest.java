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
}
