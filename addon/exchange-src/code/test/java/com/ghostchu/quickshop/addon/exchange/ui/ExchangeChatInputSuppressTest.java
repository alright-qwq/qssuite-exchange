package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeChatInputSuppressTest {
  @Test
  void suppressFlagIsConsumedExactlyOnce() {
    Set<UUID> flags = ConcurrentHashMap.newKeySet();
    UUID player = UUID.randomUUID();

    ExchangeChatInputManager.markSuppressClose(flags, player);
    assertThat(flags).contains(player);

    // The close that launched the prompt is suppressed.
    assertThat(ExchangeChatInputManager.shouldSuppressClose(flags, player)).isTrue();
    assertThat(flags).isEmpty();

    // A later close is not suppressed.
    assertThat(ExchangeChatInputManager.shouldSuppressClose(flags, player)).isFalse();
  }

  @Test
  void unrelatedPlayersDoNotConsumeEachOthersFlags() {
    Set<UUID> flags = ConcurrentHashMap.newKeySet();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    ExchangeChatInputManager.markSuppressClose(flags, first);

    assertThat(ExchangeChatInputManager.shouldSuppressClose(flags, second)).isFalse();
    assertThat(ExchangeChatInputManager.shouldSuppressClose(flags, first)).isTrue();
  }
}
