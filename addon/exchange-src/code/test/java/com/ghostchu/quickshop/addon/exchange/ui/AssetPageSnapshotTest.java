package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPageSnapshotTest {
  @Test
  void reportsAQueryFailureWithoutCompletingTheSnapshotExceptionally() {
    var assets = CompletableFuture.completedFuture(List.of(
        new AccountAssetBalance("currency", "default", BigDecimal.ONE, BigDecimal.ZERO)));
    var transfers = CompletableFuture.<List<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>>
        failedFuture(new IllegalStateException("database offline"));
    var quotes = CompletableFuture.<Map<String, MarketQuote>>completedFuture(Map.of());

    AssetPageSnapshot snapshot = AssetPageSnapshot.combine(assets, transfers, quotes).join();

    assertThat(snapshot.failure()).isInstanceOf(IllegalStateException.class)
        .hasMessage("database offline");
    assertThat(snapshot.assets()).hasSize(1);
    assertThat(snapshot.transfers()).isEmpty();
  }
}
