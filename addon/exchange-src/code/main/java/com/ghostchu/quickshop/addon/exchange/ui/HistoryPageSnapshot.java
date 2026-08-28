package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Combined bounded history projections loaded independently on the background executor. */
record HistoryPageSnapshot(List<AccountTradeRow> trades, List<TransferRecord> transfers,
                           List<AccountLedgerEntry> ledger, Throwable failure) {
  /** Visible rows per section; one extra row is fetched per section as a next-page probe. */
  static final int SECTION_SIZE = 12;
  static final int FETCH_SIZE = SECTION_SIZE + 1;

  HistoryPageSnapshot {
    trades = List.copyOf(trades);
    transfers = List.copyOf(transfers);
    ledger = List.copyOf(ledger);
  }

  static CompletableFuture<HistoryPageSnapshot> combine(
      CompletableFuture<List<AccountTradeRow>> trades,
      CompletableFuture<List<TransferRecord>> transfers,
      CompletableFuture<List<AccountLedgerEntry>> ledger) {
    return result(trades).thenCombine(result(transfers), Pair::new)
        .thenCombine(result(ledger), (pair, ledgerResult) -> new HistoryPageSnapshot(
            pair.first().value(), pair.second().value(), ledgerResult.value(),
            firstFailure(pair.first(), pair.second(), ledgerResult)));
  }

  static int offset(int page) {
    return Math.multiplyExact(Math.min(AssetTransferPaging.MAX_PAGE, Math.max(1, page)) - 1,
        SECTION_SIZE);
  }

  static boolean hasNext(int trades, int transfers, int ledger) {
    return trades > SECTION_SIZE || transfers > SECTION_SIZE || ledger > SECTION_SIZE;
  }

  boolean hasNext() {
    return hasNext(trades.size(), transfers.size(), ledger.size());
  }

  @SafeVarargs
  private static Throwable firstFailure(Result<?>... results) {
    for (Result<?> result : results) {
      if (result.failure() != null) return result.failure();
    }
    return null;
  }

  private static <T> CompletableFuture<Result<List<T>>> result(CompletableFuture<List<T>> future) {
    return future.handle((value, failure) -> new Result<>(
        value == null ? List.of() : List.copyOf(value), unwrap(failure)));
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return failure;
  }

  private record Pair(Result<List<AccountTradeRow>> first, Result<List<TransferRecord>> second) {}

  private record Result<T>(T value, Throwable failure) {}
}
