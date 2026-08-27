package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Resolves interrupted custody operations only when durable inventory evidence is sufficient. */
public final class TransferRecoveryService {
  private final TransferRepository transfers;
  private final ExchangeRepository repository;
  private final InventoryGateway inventory;
  private final Executor executor;

  public TransferRecoveryService(
      TransferRepository transfers, ExchangeRepository repository,
      InventoryGateway inventory, Executor executor) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.inventory = Objects.requireNonNull(inventory, "inventory");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public CompletableFuture<List<TransferRecord>> recoverPlayer(UUID accountId) {
    Objects.requireNonNull(accountId, "accountId");
    return CompletableFuture.supplyAsync(() -> {
      try {
        List<TransferRecord> recovered = new ArrayList<>();
        for (TransferRecord transfer : transfers.findUnfinished(accountId)) {
          recovered.add(recoverSynchronously(transfer));
        }
        return List.copyOf(recovered);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    }, executor);
  }

  public CompletableFuture<TransferRecord> recover(TransferRecord transfer) {
    Objects.requireNonNull(transfer, "transfer");
    return CompletableFuture.supplyAsync(() -> {
      try {
        return recoverSynchronously(transfer);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    }, executor);
  }

  public List<TransferRecord> recoverAllMoneyTransfers() throws SQLException {
    List<TransferRecord> recovered = new ArrayList<>();
    for (TransferRecord transfer : transfers.findAllUnfinished()) {
      if (transfer.type() == TransferType.MONEY_DEPOSIT
          || transfer.type() == TransferType.MONEY_WITHDRAWAL) {
        recovered.add(recoverMoney(transfer));
      }
    }
    return List.copyOf(recovered);
  }

  private TransferRecord recoverSynchronously(TransferRecord transfer) throws SQLException {
    return switch (transfer.type()) {
      case MONEY_DEPOSIT, MONEY_WITHDRAWAL -> recoverMoney(transfer);
      case ITEM_DEPOSIT -> recoverItemDeposit(transfer);
      case ITEM_WITHDRAWAL -> recoverItemWithdrawal(transfer);
    };
  }

  private TransferRecord recoverMoney(TransferRecord transfer) throws SQLException {
    if (transfer.status() != TransferStatus.PROCESSING) {
      return transfer;
    }
    return transfers.transition(transfer.transferId(), transfer.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
        "interrupted external money transfer");
  }

  private TransferRecord recoverItemDeposit(TransferRecord transfer) throws SQLException {
    if (transfer.status() == TransferStatus.COMPLETED) {
      clearMarker(transfer);
      return transfer;
    }
    if (transfer.status() == TransferStatus.PREPARED) {
      if (markedQuantity(transfer) > 0) {
        clearMarker(transfer);
      }
      return transfer;
    }
    if (transfer.status() != TransferStatus.PROCESSING) {
      return transfer;
    }
    if (markedQuantity(transfer) >= transfer.amount().longValueExact()) {
      clearMarker(transfer);
      return transfers.transition(transfer.transferId(), transfer.version(),
          TransferStatus.PROCESSING, TransferStatus.FAILED,
          "interrupted item deposit was not removed");
    }
    return transfers.transition(transfer.transferId(), transfer.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
        "interrupted item deposit marker is uncertain");
  }

  private TransferRecord recoverItemWithdrawal(TransferRecord transfer) throws SQLException {
    if (transfer.status() == TransferStatus.COMPLETED) {
      clearMarker(transfer);
      return transfer;
    }
    if (transfer.status() != TransferStatus.PROCESSING) {
      return transfer;
    }
    if (markedQuantity(transfer) < transfer.amount().longValueExact()) {
      return transfers.transition(transfer.transferId(), transfer.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "interrupted item withdrawal marker is uncertain");
    }
    TransferRecord completed = repository.inTransaction(transaction -> {
      transaction.consumeFrozenItems(transfer.accountId(), transfer.assetId(),
          transfer.amount().longValueExact());
      transaction.appendJournal(TransferJournals.itemWithdrawal(transfer, transfer.updatedAt()));
      return transaction.completeTransfer(transfer.transferId(), transfer.version());
    });
    clearMarker(completed);
    return completed;
  }

  private long markedQuantity(TransferRecord transfer) {
    try {
      return inventory.markedQuantity(transfer.accountId(), transfer.transferId()).join();
    } catch (RuntimeException failure) {
      return -1;
    }
  }

  private void clearMarker(TransferRecord transfer) {
    try {
      inventory.clearMarker(transfer.accountId(), transfer.transferId()).join();
    } catch (RuntimeException ignored) {
      // Cleanup is retried at the next player login without changing custody state.
    }
  }
}
