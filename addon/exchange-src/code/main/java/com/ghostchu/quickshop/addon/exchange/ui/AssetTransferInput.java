package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Parses chat-entered asset amounts into confirmable transfer requests. */
final class AssetTransferInput {
  private AssetTransferInput() {}

  private static final int MAX_CURRENCY_SCALE = 4;
  private static final int MAX_CURRENCY_DIGITS = 18;

  static ExchangeMenuRequest currency(UUID requestId, UUID accountId,
                                      ExchangeMenuRequest.TransferKind kind,
                                      String currencyId, String rawAmount) {
    requireKind(kind, true);
    BigDecimal amount;
    try {
      amount = new BigDecimal(requireInput(rawAmount));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid money amount", invalid);
    }
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("money amount must be positive");
    }
    if (amount.scale() > MAX_CURRENCY_SCALE
        || amount.precision() - amount.scale() > MAX_CURRENCY_DIGITS) {
      throw new IllegalArgumentException("money amount is outside the supported range");
    }
    return ExchangeMenuRequest.transfer(new ExchangeMenuRequest.TransferDraft(
        requestId, accountId, kind, currencyId, amount, 0, null));
  }

  static ExchangeMenuRequest item(UUID requestId, UUID accountId,
                                  ExchangeMenuRequest.TransferKind kind,
                                  String marketId, String rawQuantity) {
    requireKind(kind, false);
    long quantity;
    try {
      quantity = Long.parseLong(requireInput(rawQuantity));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid item quantity", invalid);
    }
    return ExchangeMenuRequest.transfer(new ExchangeMenuRequest.TransferDraft(
        requestId, accountId, kind, marketId, null, quantity, marketId));
  }

  private static void requireKind(ExchangeMenuRequest.TransferKind kind, boolean money) {
    Objects.requireNonNull(kind, "kind");
    if (kind.money() != money) {
      throw new IllegalArgumentException("transfer kind does not match asset type");
    }
  }

  private static String requireInput(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("transfer amount is required");
    }
    return raw.trim();
  }
}
