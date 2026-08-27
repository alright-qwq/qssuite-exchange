package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferLoginListenerTest {
  @Test
  void delegatesLoginRecoveryToTheWriterFencedSubmitter() {
    AtomicReference<UUID> recovered = new AtomicReference<>();
    TransferLoginListener listener = new TransferLoginListener(accountId -> {
      recovered.set(accountId);
      return CompletableFuture.completedFuture(null);
    });
    UUID accountId = UUID.randomUUID();

    listener.recover(accountId);

    assertThat(recovered).hasValue(accountId);
  }

  @Test
  void observesRecoveryFailureWithoutPropagatingToTheCaller() {
    TransferLoginListener listener = new TransferLoginListener(accountId ->
        CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));

    // Must not throw: the failed future is observed by an internal completion handler that
    // logs it, so a recovery failure cannot crash the join event pipeline.
    listener.recover(UUID.randomUUID());
  }
}
