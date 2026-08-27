package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualSecurityAssetsPageTest {
  @Test
  void acceptsSecurityKindAndCarriesSymbolName() {
    AccountAssetBalance balance = new AccountAssetBalance(AccountAssetBalance.Kind.SECURITY,
        "concept_alpha", new BigDecimal("25"), new BigDecimal("5"),
        "Alpha Holdings (ALPHA)", "ALPHA");

    assertThat(balance.kind()).isEqualTo(AccountAssetBalance.Kind.SECURITY);
    assertThat(balance.kindName()).isEqualTo("security");
    assertThat(balance.displayName()).isEqualTo("Alpha Holdings (ALPHA)");
  }

  @Test
  void securityRowsAreSeparatedFromTransferRowsAndCarryQuantities() {
    AssetPageRows.Merged merged = AssetPageRows.merge(
        List.of(TransferTarget.currency("default")),
        List.of(new AccountAssetBalance(AccountAssetBalance.Kind.CURRENCY, "default",
                new BigDecimal("12.50"), BigDecimal.ONE, null),
            new AccountAssetBalance(AccountAssetBalance.Kind.SECURITY, "concept_alpha",
                new BigDecimal("25"), new BigDecimal("5"),
                "Alpha Holdings (ALPHA)", "ALPHA")));

    assertThat(merged.rows()).containsExactly(new AssetPageRows.Row(
        TransferTarget.currency("default"), new BigDecimal("12.50"), BigDecimal.ONE));
    assertThat(merged.securities()).containsExactly(new AssetPageRows.SecurityRow(
        "ALPHA", "Alpha Holdings (ALPHA)", new BigDecimal("25"),
        new BigDecimal("5"), "concept_alpha"));
  }

  @Test
  void securityRowRejectsBlankDisplayName() {
    assertThatThrownBy(() -> new AssetPageRows.SecurityRow("concept_alpha", " ",
        BigDecimal.ZERO, BigDecimal.ZERO, "concept_alpha"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void securityRowsExposeAnAssetTypeSymbolAndSupplyOnMarketRows() {
    MarketListPresenter presenter = new MarketListPresenter();
    MarketQuote quote = new MarketQuote("concept_alpha", new BigDecimal("10.00"),
        new BigDecimal("10.00"), new BigDecimal("9.90"), new BigDecimal("10.10"),
        BigDecimal.ZERO, 100, new BigDecimal("1000.00"), MarketStatus.OPEN, Instant.EPOCH);

    MarketRow row = presenter.rows(List.of(new MarketListPresenter.Entry("concept_alpha",
        "Alpha", quote, "VIRTUAL_SECURITY", "ALPHA", 1000L, "OPEN", 400L))).getFirst();

    assertThat(row.assetType()).isEqualTo("VIRTUAL_SECURITY");
    assertThat(row.symbol()).isEqualTo("ALPHA");
    assertThat(row.totalSupply()).isEqualTo(1000L);
    assertThat(row.issuedSupply()).isEqualTo(400L);
    assertThat(row.securityStatus()).isEqualTo("OPEN");
  }

  @Test
  void pausedOrClosedSecurityBlocksOrderEntry() {
    assertThat(new OrderEntryAccess(
            com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy.DISABLED)
        .denial(java.util.UUID.randomUUID(), MarketStatus.PAUSED,
            com.ghostchu.quickshop.addon.exchange.core.model.OrderType.LIMIT,
            permission -> true)).contains("market-not-open");
    assertThat(new OrderEntryAccess(
            com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy.DISABLED)
        .denial(java.util.UUID.randomUUID(), MarketStatus.CLOSED,
            com.ghostchu.quickshop.addon.exchange.core.model.OrderType.MARKET,
            permission -> true)).contains("market-not-open");
  }
}
