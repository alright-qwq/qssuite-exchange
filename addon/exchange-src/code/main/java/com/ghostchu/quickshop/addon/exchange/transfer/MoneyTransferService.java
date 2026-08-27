package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class MoneyTransferService {
  private final TransferRepository transfers;
  private final ExchangeRepository repository;
  private final EconomyGateway economy;
  private final PlayerOperationSerialiser serialiser;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public MoneyTransferService(
      TransferRepository transfers, ExchangeRepository repository, EconomyGateway economy,
      PlayerOperationSerialiser serialiser, Clock clock, Supplier<UUID> ids) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.economy = Objects.requireNonNull(economy, "economy");
    this.serialiser = Objects.requireNonNull(serialiser, "serialiser");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public CompletableFuture<TransferRecord> deposit(
      UUID requestId, UUID accountId, String currencyId, BigDecimal amount) {
    requireTransferArguments(requestId, accountId, currencyId, amount);
    return serialiser.submit(accountId, () -> {
      try {
        return depositSynchronously(requestId, accountId, currencyId, amount);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    });
  }

  public CompletableFuture<TransferRecord> withdraw(
      UUID requestId, UUID accountId, String currencyId, BigDecimal amount) {
    requireTransferArguments(requestId, accountId, currencyId, amount);
    return serialiser.submit(accountId, () -> {
      try {
        return withdrawSynchronously(requestId, accountId, currencyId, amount);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    });
  }

  private TransferRecord depositSynchronously(
      UUID requestId, UUID accountId, String currencyId, BigDecimal amount) throws SQLException {
    TransferRecord prepared = transfers.create(TransferRecord.prepared(
        ids.get(), requestId, accountId, TransferType.MONEY_DEPOSIT,
        currencyId, amount, clock.instant()));
    if (prepared.status() != TransferStatus.PREPARED) {
      return prepared;
    }
    TransferRecord processing = transfers.transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    ExternalResult external;
    try {
      external = economy.withdraw(accountId, currencyId, amount);
    } catch (RuntimeException failure) {
      external = ExternalResult.UNKNOWN;
    }
    if (external == ExternalResult.FAILURE) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.FAILED,
          "economy withdrawal rejected");
    }
    if (external != ExternalResult.SUCCESS) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "economy withdrawal result unknown");
    }
    return repository.inTransaction(transaction -> {
      transaction.creditAvailableCurrency(accountId, currencyId, amount);
      transaction.appendJournal(TransferJournals.moneyDeposit(processing, clock.instant()));
      return transaction.completeTransfer(processing.transferId(), processing.version());
    });
  }

  private TransferRecord withdrawSynchronously(
      UUID requestId, UUID accountId, String currencyId, BigDecimal amount) throws SQLException {
    TransferRecord candidate = TransferRecord.prepared(
        ids.get(), requestId, accountId, TransferType.MONEY_WITHDRAWAL,
        currencyId, amount, clock.instant());
    TransferRecord prepared = repository.inTransaction(transaction -> {
      TransferRecord persisted = transaction.createTransfer(candidate);
      if (!persisted.transferId().equals(candidate.transferId())) {
        return persisted;
      }
      transaction.freezeCurrency(accountId, currencyId, amount);
      transaction.appendJournal(TransferJournals.freezeMoneyWithdrawal(candidate, clock.instant()));
      return candidate;
    });
    if (prepared.status() != TransferStatus.PREPARED) {
      return prepared;
    }
    TransferRecord processing = transfers.transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    ExternalResult external;
    try {
      external = economy.deposit(accountId, currencyId, amount);
    } catch (RuntimeException failure) {
      external = ExternalResult.UNKNOWN;
    }
    if (external == ExternalResult.FAILURE) {
      return repository.inTransaction(transaction -> {
        transaction.releaseCurrency(accountId, currencyId, amount);
        transaction.appendJournal(
            TransferJournals.releaseMoneyWithdrawal(processing, clock.instant()));
        return transaction.failTransfer(processing.transferId(), processing.version(),
            "economy deposit rejected");
      });
    }
    if (external != ExternalResult.SUCCESS) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "economy deposit result unknown");
    }
    return repository.inTransaction(transaction -> {
      transaction.consumeFrozenCurrency(accountId, currencyId, amount);
      transaction.appendJournal(TransferJournals.moneyWithdrawal(processing, clock.instant()));
      return transaction.completeTransfer(processing.transferId(), processing.version());
    });
  }

  private static void requireTransferArguments(
      UUID requestId, UUID accountId, String assetId, BigDecimal amount) {
    if (requestId == null || accountId == null || assetId == null || assetId.isBlank()
        || amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("invalid transfer");
    }
  }
}
