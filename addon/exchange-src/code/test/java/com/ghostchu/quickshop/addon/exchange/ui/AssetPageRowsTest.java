package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPageRowsTest {
  @Test
  void includesConfiguredTargetsWithZeroBalanceAndPreservesKnownBalances() {
    AssetPageRows.Merged merged = AssetPageRows.merge(
        List.of(TransferTarget.currency("default"),
            TransferTarget.item("diamond/default", "Diamond / Default")),
        List.of(new AccountAssetBalance("currency", "default",
            new BigDecimal("12.50"), new BigDecimal("1.00"))));

    assertThat(merged.rows()).containsExactly(
        new AssetPageRows.Row(TransferTarget.currency("default"),
            new BigDecimal("12.50"), new BigDecimal("1.00")),
        new AssetPageRows.Row(TransferTarget.item("diamond/default", "Diamond / Default"),
            BigDecimal.ZERO, BigDecimal.ZERO));
    assertThat(merged.securities()).isEmpty();
  }

  @Test
  void securityRowsCarryTheirMarketIdForValuation() {
    AssetPageRows.Merged merged = AssetPageRows.merge(
        List.of(),
        List.of(new AccountAssetBalance(AccountAssetBalance.Kind.SECURITY, "concept_alpha",
            new BigDecimal("25"), new BigDecimal("5"), "Alpha (ALPHA)", "ALPHA")));

    assertThat(merged.securities()).singleElement().satisfies(row -> {
      assertThat(row.symbol()).isEqualTo("ALPHA");
      assertThat(row.marketId()).isEqualTo("concept_alpha");
    });
  }
}
