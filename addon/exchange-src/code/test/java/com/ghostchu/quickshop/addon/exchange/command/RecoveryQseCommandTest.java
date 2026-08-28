package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.Main.ReloadResult;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryQseCommandTest {
  private static final AddonMessageService MESSAGES = new AddonMessageService(Map.of(
      "en-US", Map.of(
          "permission-denied", "Denied",
          "reload-requested", "Reloading",
          "reload-success", "OK",
          "reload-failed", "Failed: <0>",
          "runtime-not-started", "Not started")));

  @Test
  void reloadWithoutPermissionIsRejectedAndNeverRuns() {
    AtomicBoolean reloaded = new AtomicBoolean(false);
    List<String> messages = new ArrayList<>();
    Player player = player(false, messages);
    RecoveryQseCommand command = new RecoveryQseCommand(MESSAGES, p -> Locale.US,
        () -> {
          reloaded.set(true);
          return new ReloadResult(true, null);
        });

    assertThat(command.onCommand(player, null, "qse", new String[] {"reload"})).isTrue();
    assertThat(messages).containsExactly("Denied");
    assertThat(reloaded).isFalse();
  }

  @Test
  void reloadWithPermissionSurfacesSuccessAndRunsTheAction() {
    AtomicBoolean reloaded = new AtomicBoolean(false);
    List<String> messages = new ArrayList<>();
    Player player = player(true, messages);
    RecoveryQseCommand command = new RecoveryQseCommand(MESSAGES, p -> Locale.US,
        () -> {
          reloaded.set(true);
          return new ReloadResult(true, null);
        });

    assertThat(command.onCommand(player, null, "qse", new String[] {"reload"})).isTrue();
    assertThat(messages).containsExactly("Reloading", "OK");
    assertThat(reloaded).isTrue();
  }

  @Test
  void reloadFailureSurfacesTheCause() {
    List<String> messages = new ArrayList<>();
    Player player = player(true, messages);
    RecoveryQseCommand command = new RecoveryQseCommand(MESSAGES, p -> Locale.US,
        () -> new ReloadResult(false, "market config invalid"));

    assertThat(command.onCommand(player, null, "qse", new String[] {"reload"})).isTrue();
    assertThat(messages).containsExactly("Reloading", "Failed: market config invalid");
  }

  @Test
  void anythingElseExplainsThatTheRuntimeIsNotStarted() {
    List<String> messages = new ArrayList<>();
    Player player = player(true, messages);
    RecoveryQseCommand command = new RecoveryQseCommand(MESSAGES, p -> Locale.US,
        () -> new ReloadResult(true, null));

    assertThat(command.onCommand(player, null, "qse", new String[0])).isTrue();
    assertThat(messages).containsExactly("Not started");
  }

  @Test
  void rejectsNonPlayerSendersAndCompletesReload() {
    RecoveryQseCommand command = new RecoveryQseCommand(MESSAGES, p -> Locale.US,
        () -> new ReloadResult(true, null));
    CommandSender console = (CommandSender) Proxy.newProxyInstance(
        RecoveryQseCommandTest.class.getClassLoader(), new Class<?>[] {CommandSender.class},
        (proxy, method, arguments) -> null);

    assertThat(command.onCommand(console, null, "qse", new String[0])).isFalse();
    assertThat(command.onTabComplete(null, null, "qse", new String[0]))
        .containsExactly("reload");
  }

  private static Player player(boolean hasPermission, List<String> messages) {
    return (Player) Proxy.newProxyInstance(RecoveryQseCommandTest.class.getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "hasPermission" -> hasPermission;
          case "locale" -> Locale.US;
          case "sendMessage" -> {
            messages.add(String.valueOf(arguments[0]));
            yield null;
          }
          case "getUniqueId" -> UUID.randomUUID();
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
