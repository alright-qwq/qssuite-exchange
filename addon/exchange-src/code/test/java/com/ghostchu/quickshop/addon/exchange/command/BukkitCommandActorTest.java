package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.Main.ReloadResult;
import com.ghostchu.quickshop.addon.exchange.Main;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void surfacesReloadFailureCauseToThePlayerInTheirLocale() {
    PlayerMock player = server.addPlayer();
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of("reload-requested", "Reloading",
            "reload-success", "OK",
            "reload-failed", "Failed: <0>")));
    BukkitCommandActor actor = new BukkitCommandActor(player, messages, Locale.US,
        (menu, page) -> {}, () -> new ReloadResult(false, "tick size changed from 0.01 to 0.05"));

    actor.reloadRequested();

    assertThat(player.nextMessage()).isEqualTo("Reloading");
    assertThat(player.nextMessage()).isEqualTo("Failed: tick size changed from 0.01 to 0.05");
  }

  @Test
  void reloadResultRejectsSuccessWithCause() {
    assertThatThrownBy(() -> new ReloadResult(true, "unexpected"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("successful reload");
    assertThat(new ReloadResult(false, "blocked").success()).isFalse();
  }
}
