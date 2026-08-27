package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BukkitCommandActorTest {
  private static ServerMock server;

  @BeforeAll
  static void startServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopServer() {
    MockBukkit.unmock();
  }

  @Test
  void forwardsMessagesAndMenuOpeningToPlayerPorts() {
    PlayerMock player = server.addPlayer();
    AtomicReference<String> opened = new AtomicReference<>();
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of("permission-denied", "Denied")));
    BukkitCommandActor actor = new BukkitCommandActor(player, messages, Locale.US,
        (menu, page) -> opened.set(menu + ":" + page));

    assertThat(actor.accountId()).isEqualTo(player.getUniqueId());
    actor.message("permission-denied");
    actor.openMenu("markets", 2);

    assertThat(player.nextMessage()).isEqualTo("Denied");
    assertThat(opened).hasValue("markets:2");
  }
}
