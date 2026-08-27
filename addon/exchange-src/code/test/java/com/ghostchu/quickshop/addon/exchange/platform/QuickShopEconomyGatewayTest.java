package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.obj.QUser;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickShopEconomyGatewayTest {
  @Test
  void mapsProviderBooleanAndExceptionConservatively() {
    ProviderBehavior behavior = new ProviderBehavior();
    QuickShopEconomyGateway gateway = gateway(behavior);
    UUID player = UUID.randomUUID();

    behavior.result = true;
    assertThat(gateway.withdraw(player, "USD", new BigDecimal("1.25")))
        .isEqualTo(ExternalResult.SUCCESS);
    behavior.result = false;
    assertThat(gateway.deposit(player, "USD", new BigDecimal("2.50")))
        .isEqualTo(ExternalResult.FAILURE);
    behavior.failure = new IllegalStateException("provider failed");
    assertThat(gateway.withdraw(player, "USD", BigDecimal.ONE))
        .isEqualTo(ExternalResult.UNKNOWN);
  }

  @Test
  void passesWorldCurrencyAndExactDecimalWithoutDoubleConversion() {
    ProviderBehavior behavior = new ProviderBehavior();
    QuickShopEconomyGateway gateway = gateway(behavior);
    BigDecimal amount = new BigDecimal("9007199254740993.01");

    assertThat(gateway.withdraw(UUID.randomUUID(), "default", amount))
        .isEqualTo(ExternalResult.SUCCESS);

    assertThat(behavior.lastMethod.get()).isEqualTo("withdraw");
    assertThat(behavior.lastWorld.get()).isEqualTo("world_nether");
    assertThat(behavior.lastCurrency.get()).isNull();
    assertThat(behavior.lastAmount.get()).isSameAs(amount);
  }

  private static QuickShopEconomyGateway gateway(ProviderBehavior behavior) {
    QUser user = proxy(QUser.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
    EconomyProvider provider = proxy(EconomyProvider.class, (proxy, method, args) -> {
      if (method.getName().equals("withdraw") || method.getName().equals("deposit")) {
        if (behavior.failure != null) {
          throw behavior.failure;
        }
        behavior.lastMethod.set(method.getName());
        behavior.lastWorld.set((String) args[1]);
        behavior.lastCurrency.set((String) args[2]);
        behavior.lastAmount.set((BigDecimal) args[3]);
        return behavior.result;
      }
      return defaultValue(method.getReturnType());
    });
    return new QuickShopEconomyGateway(() -> provider, ignored -> user, "world_nether");
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == double.class) return 0D;
    if (type == float.class) return 0F;
    if (type == short.class) return (short) 0;
    if (type == byte.class) return (byte) 0;
    if (type == char.class) return (char) 0;
    return null;
  }

  private static final class ProviderBehavior {
    private boolean result = true;
    private RuntimeException failure;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastWorld = new AtomicReference<>();
    private final AtomicReference<String> lastCurrency = new AtomicReference<>();
    private final AtomicReference<BigDecimal> lastAmount = new AtomicReference<>();
  }
}
