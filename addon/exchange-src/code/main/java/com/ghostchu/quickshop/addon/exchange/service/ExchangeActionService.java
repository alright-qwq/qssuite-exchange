package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Backend action facade used by command and GUI adapters. */
public final class ExchangeActionService {
  private final Map<String, PersistentOrderService> markets;
  private final TransferActions transfers;
  private final Predicate<String> virtualSecurityMarket;

  public ExchangeActionService(Map<String, PersistentOrderService> markets,
                               MoneyTransferService money, ItemTransferService items) {
    this(markets, money, items, marketId -> false);
  }

  public ExchangeActionService(Map<String, PersistentOrderService> markets,
                               MoneyTransferService money, ItemTransferService items,
                               Predicate<String> virtualSecurityMarket) {
    this(markets, new TransferActions() {
      @Override
      public CompletableFuture<TransferRecord> moneyDeposit(
          ExchangeMenuRequest.TransferDraft draft) {
        return money.deposit(draft.requestId(), draft.accountId(), draft.assetId(), draft.amount());
      }

      @Override
      public CompletableFuture<TransferRecord> moneyWithdrawal(
          ExchangeMenuRequest.TransferDraft draft) {
        return money.withdraw(draft.requestId(), draft.accountId(), draft.assetId(), draft.amount());
      }

      @Override
      public CompletableFuture<TransferRecord> itemDeposit(ExchangeMenuRequest.TransferDraft draft) {
        return items.deposit(draft.requestId(), draft.accountId(), draft.marketId(), draft.quantity());
      }

      @Override
      public CompletableFuture<TransferRecord> itemWithdrawal(
          ExchangeMenuRequest.TransferDraft draft) {
        return items.withdraw(draft.requestId(), draft.accountId(), draft.marketId(), draft.quantity());
      }
    }, Objects.requireNonNull(virtualSecurityMarket, "virtualSecurityMarket"));
  }

  ExchangeActionService(Map<String, PersistentOrderService> markets,
                        TransferActions transfers) {
    this(markets, transfers, marketId -> false);
  }

  ExchangeActionService(Map<String, PersistentOrderService> markets, TransferActions transfers,
                        Predicate<String> virtualSecurityMarket) {
    this.markets = Map.copyOf(Objects.requireNonNull(markets, "markets"));
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.virtualSecurityMarket =
        Objects.requireNonNull(virtualSecurityMarket, "virtualSecurityMarket");
  }

  public OrderReceipt submitOrder(ExchangeMenuRequest.OrderDraft draft) throws SQLException {
    Objects.requireNonNull(draft, "draft");
    PersistentOrderService market = market(draft.marketId());
    return market.place(new OrderRequest(draft.requestId(), draft.accountId(), draft.marketId(),
        draft.side(), draft.type().name(), draft.price(), draft.slippageBoundary(), draft.quantity()));
  }

  /** Cancels a player's own order while preserving request idempotency. */
  public OrderReceipt cancel(UUID accountId, UUID requestId, UUID orderId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    SQLException lastFailure = null;
    for (PersistentOrderService market : markets.values()) {
      try {
        return market.cancel(accountId, requestId, orderId);
      } catch (IllegalArgumentException failure) {
        lastFailure = null;
      } catch (SQLException failure) {
        lastFailure = failure;
      }
    }
    if (lastFailure != null) {
      throw lastFailure;
    }
    throw new IllegalArgumentException("order is not open: " + orderId);
  }

  public CompletableFuture<TransferRecord> submitTransfer(
      ExchangeMenuRequest.TransferDraft draft) {
    Objects.requireNonNull(draft, "draft");
    return switch (draft.kind()) {
      case MONEY_DEPOSIT -> transfers.moneyDeposit(draft);
      case MONEY_WITHDRAWAL -> transfers.moneyWithdrawal(draft);
      case ITEM_DEPOSIT -> {
        requirePhysicalMarket(draft.marketId());
        yield transfers.itemDeposit(draft);
      }
      case ITEM_WITHDRAWAL -> {
        requirePhysicalMarket(draft.marketId());
        yield transfers.itemWithdrawal(draft);
      }
    };
  }

  private void requirePhysicalMarket(String marketId) {
    market(marketId);
    if (virtualSecurityMarket.test(marketId)) {
      throw new IllegalArgumentException(
          "virtual security markets do not support item transfers: " + marketId);
    }
  }

  public PersistentOrderService market(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    PersistentOrderService service = markets.get(marketId);
    if (service == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return service;
  }

  interface TransferActions {
    CompletableFuture<TransferRecord> moneyDeposit(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> moneyWithdrawal(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> itemDeposit(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> itemWithdrawal(ExchangeMenuRequest.TransferDraft draft);
  }
}
