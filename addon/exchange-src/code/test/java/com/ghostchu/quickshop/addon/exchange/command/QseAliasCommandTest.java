package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QseAliasCommandTest {
  @Test
  void forwardsAliasArgumentsToTheSharedRouter() {
    AtomicReference<String> opened = new AtomicReference<>();
    QseAliasCommand alias = new QseAliasCommand(new ExchangeCommandRouter(UUID::randomUUID),
        player -> new CommandActor() {
          @Override public UUID accountId() { return UUID.randomUUID(); }
          @Override public boolean hasPermission(String permission) { return true; }
          @Override public void message(String key, Object... arguments) { }
          @Override public void openMenu(String menuName, int page) { opened.set(menuName); }
        });

    assertThat(alias.onCommand(player(), null, "qse", new String[] {"open"})).isTrue();
    assertThat(opened).hasValue("markets");
  }

  @Test
  void rejectsNonPlayerAliasSenders() {
    QseAliasCommand alias = new QseAliasCommand(new ExchangeCommandRouter(UUID::randomUUID),
        player -> { throw new AssertionError("actor must not be created"); });

    CommandSender console = (CommandSender) java.lang.reflect.Proxy.newProxyInstance(
        QseAliasCommandTest.class.getClassLoader(), new Class<?>[] {CommandSender.class},
        (proxy, method, arguments) -> null);
    assertThat(alias.onCommand(console, null, "qse", new String[0])).isFalse();
  }

  private static Player player() {
    return (Player) java.lang.reflect.Proxy.newProxyInstance(
        QseAliasCommandTest.class.getClassLoader(), new Class<?>[] {Player.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "getUniqueId" -> UUID.randomUUID();
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
