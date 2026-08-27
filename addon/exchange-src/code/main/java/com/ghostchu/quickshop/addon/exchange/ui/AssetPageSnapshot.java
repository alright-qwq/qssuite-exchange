package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Combined asynchronous data required to render the asset page. */
record AssetPageSnapshot(List<AccountAssetBalance> assets, List<TransferRecord> transfers,
                         Map<String, MarketQuote> quotes, Throwable failure) {
  AssetPageSnapshot {
    assets = List.copyOf(assets);
    transfers = List.copyOf(transfers);
    quotes = Map.copyOf(quotes);
  }

  static CompletableFuture<AssetPageSnapshot> combine(
      CompletableFuture<List<AccountAssetBalance>> assets,
      CompletableFuture<List<TransferRecord>> transfers,
      CompletableFuture<Map<String, MarketQuote>> quotes) {
    CompletableFuture<Result<List<AccountAssetBalance>>> assetResult = result(assets);
    CompletableFuture<Result<List<TransferRecord>>> transferResult = result(transfers);
    CompletableFuture<Result<Map<String, MarketQuote>>> quoteResult = resultMap(quotes);
    return assetResult.thenCombine(transferResult, Pair::new)
        .thenCombine(quoteResult, (pair, quote) -> new AssetPageSnapshot(
            pair.asset().value(), pair.transfer().value(), quote.value(),
            firstFailure(pair.asset(), pair.transfer(), quote)));
  }

  private static Throwable firstFailure(Result<?>... results) {
    for (Result<?> result : results) {
      if (result.failure() != null) return result.failure();
    }
    return null;
  }

  private record Pair(Result<List<AccountAssetBalance>> asset,
                      Result<List<TransferRecord>> transfer) {
    static Pair of(Result<List<AccountAssetBalance>> asset,
                   Result<List<TransferRecord>> transfer) {
      return new Pair(asset, transfer);
    }
  }

  private static <T> CompletableFuture<Result<List<T>>> result(CompletableFuture<List<T>> future) {
    return future.handle((value, failure) -> new Result<>(
        value == null ? List.of() : List.copyOf(value), unwrap(failure)));
  }

  private static <K, V> CompletableFuture<Result<Map<K, V>>> resultMap(
      CompletableFuture<Map<K, V>> future) {
    return future.handle((value, failure) -> new Result<>(
        value == null ? Map.of() : Map.copyOf(value), unwrap(failure)));
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return failure;
  }

  private record Result<T>(T value, Throwable failure) {}
}
