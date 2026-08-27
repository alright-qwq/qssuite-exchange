package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.transfer.EconomyGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.obj.QUserImpl;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class QuickShopEconomyGateway implements EconomyGateway {
  private final Supplier<EconomyProvider> providers;
  private final Function<UUID, QUser> users;
  private final String worldName;

  public QuickShopEconomyGateway(QuickShop quickShop, String worldName) {
    this(() -> Objects.requireNonNull(quickShop, "quickShop").getEconomyManager().provider(),
        playerId -> QUserImpl.createSync(quickShop.getPlayerFinder(), playerId), worldName);
  }

  QuickShopEconomyGateway(
      Supplier<EconomyProvider> providers, Function<UUID, QUser> users, String worldName) {
    this.providers = Objects.requireNonNull(providers, "providers");
    this.users = Objects.requireNonNull(users, "users");
    if (worldName == null || worldName.isBlank()) {
      throw new IllegalArgumentException("world name is required");
    }
    this.worldName = worldName;
  }

  @Override
  public ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount) {
    return invoke(playerId, currencyId, amount, true);
  }

  @Override
  public ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount) {
    return invoke(playerId, currencyId, amount, false);
  }

  private ExternalResult invoke(
      UUID playerId, String currencyId, BigDecimal amount, boolean withdraw) {
    try {
      EconomyProvider provider = Objects.requireNonNull(
          providers.get(), "economy provider unavailable");
      QUser user = Objects.requireNonNull(users.apply(playerId), "economy user unavailable");
      String providerCurrency = "default".equalsIgnoreCase(currencyId) ? null : currencyId;
      boolean success = withdraw
          ? provider.withdraw(user, worldName, providerCurrency, amount)
          : provider.deposit(user, worldName, providerCurrency, amount);
      return success ? ExternalResult.SUCCESS : ExternalResult.FAILURE;
    } catch (RuntimeException failure) {
      return ExternalResult.UNKNOWN;
    }
  }
}
