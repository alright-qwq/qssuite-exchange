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
    if (transfer.status() == TransferStatus.PREPARED) {
      return recoverPreparedMoney(transfer);
    }
    if (transfer.status() != TransferStatus.PROCESSING) {
      return transfer;
    }
    return transfers.transition(transfer.transferId(), transfer.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
        "interrupted external money transfer");
  }

  private TransferRecord recoverPreparedMoney(TransferRecord transfer) throws SQLException {
    if (transfer.type() == TransferType.MONEY_WITHDRAWAL) {
      // The freeze committed before the external deposit, so releasing it is safe:
      // the economy call only happens after the PREPARED->PROCESSING transition.
      return repository.inTransaction(transaction -> {
        transaction.releaseCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
        transaction.appendJournal(
            TransferJournals.releaseMoneyWithdrawal(transfer, transfer.updatedAt()));
        return transaction.failPreparedTransfer(transfer.transferId(), transfer.version(),
            "interrupted before processing");
      });
    }
    // A prepared money deposit froze nothing and never touched the external
    // economy, so it can be failed safely and leaves the unfinished set.
    return repository.inTransaction(transaction ->
        transaction.failPreparedTransfer(transfer.transferId(), transfer.version(),
            "interrupted before processing"));
  }

  private TransferRecord recoverItemDeposit(TransferRecord transfer) throws SQLException {
    if (transfer.status() == TransferStatus.COMPLETED) {
      clearMarker(transfer);
      return transfer;
    }
    if (transfer.status() == TransferStatus.PREPARED) {
      // The deposit never reached PROCESSING, so no custody balance was credited and the
      // inventory marker is only a claim on the player's own stacks. Leaving the record
      // unfinished would retry forever; fail it and clean any marker so the player can
      // deposit again.
      if (markedQuantity(transfer) > 0) {
        clearMarker(transfer);
      }
      return transfers.transition(transfer.transferId(), transfer.version(),
          TransferStatus.PREPARED, TransferStatus.FAILED,
          "interrupted before processing");
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
    if (transfer.status() == TransferStatus.PREPARED) {
      long marked = markedQuantity(transfer);
      if (marked > 0) {
        // The server crashed after freezing custody but before delivering items; the marker
        // is ambiguous and must be reviewed rather than guessed at.
        return transfers.transition(transfer.transferId(), transfer.version(),
            TransferStatus.PREPARED, TransferStatus.REVIEW_REQUIRED,
            "interrupted item withdrawal marker is uncertain");
      }
      // No items were delivered, so unfreeze the custody balance and fail the transfer.
      // Otherwise the frozen quantity would stay stranded forever because the transfer
      // never advanced to PROCESSING.
      return repository.inTransaction(transaction -> {
        transaction.releaseItems(transfer.accountId(), transfer.assetId(),
            transfer.amount().longValueExact());
        transaction.appendJournal(
            TransferJournals.releaseItemWithdrawal(transfer, transfer.updatedAt()));
        return transaction.failPreparedTransfer(transfer.transferId(), transfer.version(),
            "interrupted before processing");
      });
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
