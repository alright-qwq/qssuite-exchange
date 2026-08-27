package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Maps quote values to page-safe rows without exposing books or repositories to the UI. */
public final class MarketListPresenter {
  public List<MarketRow> rows(List<Entry> entries) {
    return List.copyOf(entries.stream().map(entry -> {
      MarketQuote quote = entry.quote();
      return new MarketRow(entry.marketId(), entry.displayName(), quote.lastPrice(),
          quote.bestBid(), quote.bestAsk(), quote.change24h(), quote.volume24h(), quote.status(),
          entry.assetType(), entry.symbol(), entry.totalSupply(), entry.securityStatus(),
          quote.volatility24h(), quote.high24h(), quote.low24h(), entry.issuedSupply(),
          quote.notional24h(), entry.recentTrades());
    }).toList());
  }

  public MarketOverviewSnapshot overview(List<Entry> entries) {
    List<Entry> safeEntries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    long totalVolume = safeEntries.stream().mapToLong(entry -> entry.quote().volume24h())
        .reduce(0L, Math::addExact);
    BigDecimal totalNotional = safeEntries.stream().map(entry -> entry.quote().notional24h())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    int rising = (int) safeEntries.stream()
        .filter(entry -> entry.quote().change24h() != null
            && entry.quote().change24h().signum() > 0).count();
    int falling = (int) safeEntries.stream()
        .filter(entry -> entry.quote().change24h() != null
            && entry.quote().change24h().signum() < 0).count();
    return new MarketOverviewSnapshot(safeEntries.size(), rising, falling, totalVolume, totalNotional,
        row(select(safeEntries, Comparator.comparing((Entry entry) -> entry.quote().notional24h(),
            Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed().thenComparing(Entry::marketId))),
        row(select(safeEntries, Comparator.comparing((Entry entry) -> entry.quote().change24h(),
            Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed().thenComparing(Entry::marketId))),
        row(select(safeEntries, Comparator.comparing((Entry entry) -> entry.quote().change24h(),
            Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Entry::marketId))));
  }

  private static Entry select(List<Entry> entries, Comparator<Entry> comparator) {
    return entries.stream().min(comparator).orElse(null);
  }

  private static MarketRow row(Entry entry) {
    if (entry == null) {
      return null;
    }
    MarketQuote quote = entry.quote();
    return new MarketRow(entry.marketId(), entry.displayName(), quote.lastPrice(),
        quote.bestBid(), quote.bestAsk(), quote.change24h(), quote.volume24h(), quote.status(),
        entry.assetType(), entry.symbol(), entry.totalSupply(), entry.securityStatus(),
        quote.volatility24h(), quote.high24h(), quote.low24h(), entry.issuedSupply(),
        quote.notional24h(), entry.recentTrades());
  }

  public record Entry(String marketId, String displayName, MarketQuote quote,
                      String assetType, String symbol, Long totalSupply, String securityStatus,
                      Long issuedSupply, List<MarketRow.TradeLore> recentTrades) {
    public Entry {
      if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display data is required");
      }
      Objects.requireNonNull(quote, "quote");
      recentTrades = List.copyOf(recentTrades == null ? List.of() : recentTrades);
    }

    public Entry(String marketId, String displayName, MarketQuote quote) {
      this(marketId, displayName, quote, null, null, null, null, null, List.of());
    }

    /** Backwards-compatible projection without recent-trade lore. */
    public Entry(String marketId, String displayName, MarketQuote quote,
                 String assetType, String symbol, Long totalSupply, String securityStatus,
                 Long issuedSupply) {
      this(marketId, displayName, quote, assetType, symbol, totalSupply, securityStatus,
          issuedSupply, List.of());
    }
  }
}
