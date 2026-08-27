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
}
