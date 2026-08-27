package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Typed state passed from a command into the exchange GUI. */
public record ExchangeMenuRequest(
    String menuName, int page, UUID requestId, UUID accountId, String marketId, UUID orderId,
    OrderDraft order, TransferDraft transfer) {
  public ExchangeMenuRequest {
    if (menuName == null || menuName.isBlank() || page < 1) {
      throw new IllegalArgumentException("menu and page are required");
    }
    if (order != null && transfer != null) {
      throw new IllegalArgumentException("a menu request cannot contain both order and transfer");
    }
    if (orderId != null && order != null) {
      throw new IllegalArgumentException("a menu request cannot contain a draft and order id");
    }
  }

  public static ExchangeMenuRequest page(String menuName) {
    return page(menuName, 1);
  }

  public static ExchangeMenuRequest page(String menuName, int page) {
    return new ExchangeMenuRequest(menuName, Math.max(1, page), null, null, null, null, null, null);
  }

  public static ExchangeMenuRequest market(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    return new ExchangeMenuRequest("market-detail", 1, null, null, marketId, null, null, null);
  }

  /** Opens a paginated market trade-history page. */
  public static ExchangeMenuRequest marketTrades(String marketId, int page) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    return new ExchangeMenuRequest("market-trades", Math.max(1, page), null, null,
        marketId, null, null, null);
  }

  public static ExchangeMenuRequest order(OrderDraft order) {
    Objects.requireNonNull(order, "order");
    return new ExchangeMenuRequest("order-confirm", 1, order.requestId(), order.accountId(),
        order.marketId(), null, order, null);
  }

  public static ExchangeMenuRequest cancel(UUID requestId, UUID accountId, UUID orderId) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(orderId, "orderId");
    return new ExchangeMenuRequest("cancel-confirm", 1, requestId, accountId, null, orderId,
        null, null);
  }

  public static ExchangeMenuRequest transfer(TransferDraft transfer) {
    Objects.requireNonNull(transfer, "transfer");
    return new ExchangeMenuRequest("transfer-confirm", 1, transfer.requestId(),
        transfer.accountId(), transfer.marketId(), null, null, transfer);
  }

  public record OrderDraft(UUID requestId, UUID accountId, String marketId, OrderSide side,
                           OrderType type, BigDecimal price, BigDecimal slippageBoundary,
                           long quantity) {
    public OrderDraft {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(accountId, "accountId");
      if (marketId == null || marketId.isBlank() || side == null || type == null || quantity <= 0) {
        throw new IllegalArgumentException("invalid order draft");
      }
      if (type == OrderType.LIMIT && (price == null || price.signum() <= 0)) {
        throw new IllegalArgumentException("limit orders require a positive price");
      }
      if (price != null && price.signum() <= 0) {
        throw new IllegalArgumentException("price must be positive");
      }
      if (slippageBoundary != null && slippageBoundary.signum() <= 0) {
        throw new IllegalArgumentException("slippage boundary must be positive");
      }
    }
  }

  public record TransferDraft(UUID requestId, UUID accountId, TransferKind kind,
                              String assetId, BigDecimal amount, long quantity, String marketId) {
    public TransferDraft {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(kind, "kind");
      if (assetId == null || assetId.isBlank()) {
        throw new IllegalArgumentException("assetId is required");
      }
      if (kind.money() && (amount == null || amount.signum() <= 0)) {
        throw new IllegalArgumentException("money transfers require a positive amount");
      }
      if (!kind.money() && quantity <= 0) {
        throw new IllegalArgumentException("item transfers require a positive quantity");
      }
      if (!kind.money() && (marketId == null || marketId.isBlank())) {
        throw new IllegalArgumentException("item transfers require a market");
      }
    }
  }

  public enum TransferKind {
    MONEY_DEPOSIT(true), MONEY_WITHDRAWAL(true), ITEM_DEPOSIT(false), ITEM_WITHDRAWAL(false);

    private final boolean money;

    TransferKind(boolean money) {
      this.money = money;
    }

    public boolean money() {
      return money;
    }
  }
}
