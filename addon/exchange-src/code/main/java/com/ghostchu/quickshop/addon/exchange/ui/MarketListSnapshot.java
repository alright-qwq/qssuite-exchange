package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable market list and overview generated from the same quote collection. */
public record MarketListSnapshot(List<MarketRow> markets, MarketOverviewSnapshot overview) {
  public MarketListSnapshot {
    markets = List.copyOf(Objects.requireNonNull(markets, "markets"));
    overview = Objects.requireNonNull(overview, "overview");
  }

  public enum SortMode {
    NOTIONAL, CHANGE, LAST;

    public SortMode next() {
      return switch (this) {
        case NOTIONAL -> CHANGE;
        case CHANGE -> LAST;
        case LAST -> NOTIONAL;
      };
    }
  }

  public static List<MarketRow> sorted(List<MarketRow> rows, SortMode mode) {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(mode, "mode");
    Comparator<MarketRow> comparator = switch (mode) {
      case NOTIONAL -> Comparator.comparing((MarketRow row) ->
          row.notional24h() == null ? java.math.BigDecimal.ZERO : row.notional24h()).reversed();
      case CHANGE -> Comparator.comparing((MarketRow row) ->
          row.change24h() == null ? java.math.BigDecimal.ZERO : row.change24h()).reversed();
      case LAST -> Comparator.comparing((MarketRow row) ->
          row.lastPrice() == null ? java.math.BigDecimal.ZERO : row.lastPrice()).reversed();
    };
    return rows.stream().sorted(comparator).toList();
  }

  public static List<MarketRow> filtered(List<MarketRow> rows, String assetTypeFilter) {
    Objects.requireNonNull(rows, "rows");
    if (assetTypeFilter == null || assetTypeFilter.isBlank()
        || "ALL".equalsIgnoreCase(assetTypeFilter)) {
      return rows;
    }
    boolean security = "SECURITY".equalsIgnoreCase(assetTypeFilter);
    return rows.stream()
        .filter(row -> security
            ? "VIRTUAL_SECURITY".equals(row.assetType())
            : !"VIRTUAL_SECURITY".equals(row.assetType()))
        .toList();
  }
}
