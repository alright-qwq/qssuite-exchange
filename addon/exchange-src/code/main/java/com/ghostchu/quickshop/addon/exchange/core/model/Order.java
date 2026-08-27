package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID orderId, UUID requestId, String marketId, UUID accountId,
    OrderSide side, OrderType type, TimeInForce timeInForce,
    BigDecimal limitPrice, BigDecimal slippageBoundary,
    long originalQuantity, long remainingQuantity, OrderStatus status,
    long prioritySequence, long configVersion, long feeVersion,
    Instant createdAt, Instant updatedAt) {

  public Order {
    if (orderId == null || requestId == null || accountId == null || marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("order identity is required");
    }
    if (side == null || type == null || timeInForce == null || status == null
        || createdAt == null || updatedAt == null) {
      throw new IllegalArgumentException("order metadata is required");
    }
    if (prioritySequence <= 0 || configVersion <= 0 || feeVersion <= 0) {
      throw new IllegalArgumentException("order versions must be positive");
    }
    if (originalQuantity <= 0 || remainingQuantity < 0 || remainingQuantity > originalQuantity) {
      throw new IllegalArgumentException("invalid remaining quantity");
    }
    if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0
        || slippageBoundary != null || timeInForce != TimeInForce.GTC)) {
      throw new IllegalArgumentException("limit order requires price and GTC");
    }
    if (type == OrderType.MARKET && (slippageBoundary == null || slippageBoundary.signum() <= 0
        || limitPrice != null || timeInForce != TimeInForce.IOC)) {
      throw new IllegalArgumentException("market order requires IOC");
    }
    validateStatusQuantity(status, originalQuantity, remainingQuantity);
  }

  public Order withRemaining(long remaining, Instant now) {
    if (status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED) {
      throw new IllegalArgumentException("only open orders can be filled");
    }
    if (remaining < 0 || remaining >= remainingQuantity) {
      throw new IllegalArgumentException("remaining quantity must decrease");
    }
    OrderStatus next = remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remaining, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }

  public Order withStatus(OrderStatus next, Instant now) {
    if (next == null) {
      throw new IllegalArgumentException("order status is required");
    }
    if (next != status) {
      if (status == OrderStatus.FILLED || status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED) {
        throw new IllegalArgumentException("terminal order status cannot change");
      }
      boolean allowed = next == OrderStatus.CANCELLED && remainingQuantity > 0
          || next == OrderStatus.REJECTED && remainingQuantity == originalQuantity;
      if (!allowed) {
        throw new IllegalArgumentException("invalid order status transition");
      }
    }
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remainingQuantity, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }

  private static void validateStatusQuantity(OrderStatus status, long original, long remaining) {
    boolean valid = switch (status) {
      case OPEN, REJECTED -> remaining == original;
      case PARTIALLY_FILLED -> remaining > 0 && remaining < original;
      case FILLED -> remaining == 0;
      case CANCELLED -> remaining > 0;
    };
    if (!valid) {
      throw new IllegalArgumentException("order status conflicts with remaining quantity");
    }
  }
}
