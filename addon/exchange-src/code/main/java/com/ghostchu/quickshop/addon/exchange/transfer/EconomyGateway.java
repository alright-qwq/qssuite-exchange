package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import java.math.BigDecimal;
import java.util.UUID;

public interface EconomyGateway {
  ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount);

  ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount);
}
