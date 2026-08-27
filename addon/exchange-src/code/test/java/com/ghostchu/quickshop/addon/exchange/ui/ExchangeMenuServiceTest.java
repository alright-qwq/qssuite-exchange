package com.ghostchu.quickshop.addon.exchange.ui;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMenuServiceTest {
  @Test
  void schedulesInventoryCloseAtThePlayerEntityOwner() {
    AtomicBoolean scheduled = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    Player player = (Player) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
          if ("closeInventory".equals(method.getName())) {
            closed.set(true);
            return null;
          }
          if (method.getReturnType() == boolean.class) return false;
          if (method.getReturnType() == int.class) return 0;
          if (method.getReturnType() == long.class) return 0L;
          if (method.getReturnType() == float.class) return 0F;
          if (method.getReturnType() == double.class) return 0D;
          return null;
        });

    ExchangeMenuService.closeInventoryAtOwner(player, (owner, action) -> {
      scheduled.set(true);
      assertThat(closed).isFalse();
      action.run();
    });

    assertThat(scheduled).isTrue();
    assertThat(closed).isTrue();
  }
}
