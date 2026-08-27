package com.ghostchu.quickshop.addon.exchange.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSingleWriterGuardTest {
  @Test
  void rejectsSecondAcquireUntilReleased() throws Exception {
    LocalSingleWriterGuard guard = new LocalSingleWriterGuard();

    guard.acquire();

    assertThat(guard.held()).isTrue();
    assertThatThrownBy(guard::acquire).isInstanceOf(IllegalStateException.class);
    guard.close();
    guard.acquire();
    assertThat(guard.held()).isTrue();
  }

  @Test
  void usesAnOperatingSystemLockForTheConfiguredLocalDatabase() throws Exception {
    Path database = Files.createTempFile("quickshop-exchange-writer-", ".sqlite");
    LocalSingleWriterGuard first = new LocalSingleWriterGuard(database);
    LocalSingleWriterGuard second = new LocalSingleWriterGuard(database);

    first.acquire();

    assertThatThrownBy(second::acquire).isInstanceOf(IllegalStateException.class);
    first.close();
    second.acquire();
    assertThat(second.held()).isTrue();
    second.close();
  }

  @Test
  void closeCannotReleaseTheLockDuringGuardedWork() throws Exception {
    Path database = Files.createTempFile("quickshop-exchange-fence-", ".sqlite");
    LocalSingleWriterGuard first = new LocalSingleWriterGuard(database);
    LocalSingleWriterGuard second = new LocalSingleWriterGuard(database);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    first.acquire();

    Thread work = Thread.ofPlatform().start(() -> {
      try {
        first.runWhileHeld(() -> {
          started.countDown();
          assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
        });
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Thread closer = Thread.ofPlatform().start(first::close);
    Thread.sleep(50L);

    assertThatThrownBy(second::acquire).isInstanceOf(IllegalStateException.class);
    assertThat(closer.isAlive()).isTrue();
    release.countDown();
    work.join(2_000L);
    closer.join(2_000L);
    second.acquire();
    assertThat(second.held()).isTrue();
    second.close();
  }
}
