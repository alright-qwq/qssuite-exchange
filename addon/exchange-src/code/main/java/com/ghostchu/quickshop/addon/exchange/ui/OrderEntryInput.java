package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Parses chat-entered order fields into a typed confirmation request. */
final class OrderEntryInput {
  private OrderEntryInput() {}

  static ExchangeMenuRequest limit(UUID requestId, UUID accountId, String marketId,
                                   OrderSide side, String raw) {
    Fields fields = parse(raw);
    return request(requestId, accountId, marketId, side, OrderType.LIMIT,
        fields.priceOrBoundary(), null, fields.quantity());
  }

  static ExchangeMenuRequest market(UUID requestId, UUID accountId, String marketId,
                                    OrderSide side, String raw) {
    Fields fields = parse(raw);
    return request(requestId, accountId, marketId, side, OrderType.MARKET,
        null, fields.priceOrBoundary(), fields.quantity());
  }

  private static ExchangeMenuRequest request(UUID requestId, UUID accountId, String marketId,
                                             OrderSide side, OrderType type, BigDecimal price,
                                             BigDecimal boundary, long quantity) {
    Objects.requireNonNull(side, "side");
    return ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        requestId, accountId, marketId, side, type, price, boundary, quantity));
  }

  private static Fields parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("order fields are required");
    }
    String[] fields = raw.trim().split("\\s+");
    if (fields.length != 2) {
      throw new IllegalArgumentException("order input requires quantity and price boundary");
    }
    try {
      long quantity = Long.parseLong(fields[0]);
      BigDecimal priceOrBoundary = new BigDecimal(fields[1]);
      if (quantity <= 0 || priceOrBoundary.signum() <= 0) {
        throw new IllegalArgumentException("order fields must be positive");
      }
      return new Fields(quantity, priceOrBoundary);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid order fields", invalid);
    }
  }

  private record Fields(long quantity, BigDecimal priceOrBoundary) {}
}
