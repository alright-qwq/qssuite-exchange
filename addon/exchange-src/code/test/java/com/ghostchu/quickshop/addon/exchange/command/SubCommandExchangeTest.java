package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.api.command.CommandParser;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubCommandExchangeTest {
  @Test
  void forwardsQuickShopArgumentsToTheSharedRouter() {
    AtomicReference<String> opened = new AtomicReference<>();
    SubCommandExchange command = new SubCommandExchange(new ExchangeCommandRouter(UUID::randomUUID),
        player -> new CommandActor() {
          @Override public UUID accountId() { return UUID.randomUUID(); }
          @Override public boolean hasPermission(String permission) { return true; }
          @Override public void message(String key, Object... arguments) { }
          @Override public void openMenu(String menuName, int page) { opened.set(menuName); }
        });

    command.onCommand(player(), "quickshop", new CommandParser("open", true));

    assertThat(opened).hasValue("markets");
  }

  private static Player player() {
    return (Player) java.lang.reflect.Proxy.newProxyInstance(
        SubCommandExchangeTest.class.getClassLoader(), new Class<?>[] {Player.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "getUniqueId" -> UUID.randomUUID();
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
