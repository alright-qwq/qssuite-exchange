package com.ghostchu.quickshop.addon.exchange.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownSequenceTest {
  @Test
  void closesTheRuntimeWhenEntrypointCleanupFails() {
    AtomicBoolean runtimeClosed = new AtomicBoolean();
    List<Exception> failures = new ArrayList<>();

    ShutdownSequence.close(
        () -> { throw new IllegalStateException("admin reads timed out"); },
        () -> runtimeClosed.set(true), failures::add);

    assertThat(runtimeClosed).isTrue();
    assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);
    assertThat(failures.getFirst()).hasMessage("admin reads timed out");
  }

  @Test
  void reportsRuntimeCloseFailureAfterEntrypointCleanup() {
    List<Exception> failures = new ArrayList<>();

    ShutdownSequence.close(
        () -> {}, () -> { throw new Exception("runtime close failed"); }, failures::add);

    assertThat(failures).singleElement().isInstanceOf(Exception.class);
    assertThat(failures.getFirst()).hasMessage("runtime close failed");
  }

  @Test
  void continuesThroughEveryEntrypointCleanupStageAfterAFailure() {
    List<String> closed = new ArrayList<>();
    List<Exception> failures = new ArrayList<>();

    ShutdownSequence.closeAll(List.of(
        () -> closed.add("command"),
        () -> { throw new IllegalStateException("menu close failed"); },
        () -> closed.add("listener"),
        () -> closed.add("admin reads")), failures::add);

    assertThat(closed).containsExactly("command", "listener", "admin reads");
    assertThat(failures).extracting(Throwable::getMessage).containsExactly("menu close failed");
  }
}
