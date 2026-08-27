package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Background-only read facade used by exchange UI pages. */
public final class ExchangeViewService {
  private volatile Map<String, MarketView> markets;
  private final MarketDataService marketData;
  private final Executor executor;
  private final ExchangeRepository repository;
  private final List<TransferTarget> transferTargets;
  private final MarketListPresenter presenter = new MarketListPresenter();
  private volatile Duration marketUpdateMinInterval;
  private final java.util.Map<UUID, Long> lastMarketRefresh =
      new java.util.concurrent.ConcurrentHashMap<>();

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor) {
    this(markets, marketData, executor, null, List.of(), Duration.ofSeconds(1));
  }

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor, ExchangeRepository repository) {
    this(markets, marketData, executor, repository, List.of(), Duration.ofSeconds(1));
  }

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor, ExchangeRepository repository,
                             List<TransferTarget> transferTargets) {
    this(markets, marketData, executor, repository, transferTargets, Duration.ofSeconds(1));
  }

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor, ExchangeRepository repository,
                             List<TransferTarget> transferTargets,
                             Duration marketUpdateMinInterval) {
    this.markets = Map.copyOf(new LinkedHashMap<>(markets));
    this.marketData = Objects.requireNonNull(marketData, "marketData");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.repository = repository;
    this.transferTargets = List.copyOf(Objects.requireNonNull(transferTargets, "transferTargets"));
    this.marketUpdateMinInterval = Objects.requireNonNull(marketUpdateMinInterval,
        "marketUpdateMinInterval");
    if (marketUpdateMinInterval.isZero() || marketUpdateMinInterval.isNegative()) {
      throw new IllegalArgumentException("market update interval must be positive");
    }
  }

  /** Hot-adds a market view so it is immediately visible to list/detail/resolution consumers. */
  public synchronized void addMarket(MarketView view) {
    Objects.requireNonNull(view, "view");
    if (markets.containsKey(view.marketId())) {
      throw new IllegalArgumentException("market view already exists: " + view.marketId());
    }
    Map<String, MarketView> extended = new LinkedHashMap<>(markets);
    extended.put(view.marketId(), view);
    this.markets = Map.copyOf(extended);
  }

  /**
   * Hot-updates a market's display metadata (name, asset type, symbol, supply) so configuration
   * changes appear in already-open views without a restart. The order book service and live
   * security suppliers are preserved from the previous view.
   */
  public synchronized void updateMarketMetadata(String marketId, String displayName,
      String assetType, String symbol, Long totalSupply) {
    MarketView current = markets.get(marketId);
    if (current == null) {
      throw new IllegalArgumentException("unknown market view: " + marketId);
    }
    MarketView updated = new MarketView(current.marketId(), displayName, current.service(),
        assetType, symbol, totalSupply, current.securityStatus(), current.issuedSupply());
    Map<String, MarketView> updatedMarkets = new LinkedHashMap<>(markets);
    updatedMarkets.put(marketId, updated);
    this.markets = Map.copyOf(updatedMarkets);
  }

  /**
   * Hot-applies the GUI market refresh minimum interval. Existing player subscriptions pick up the
   * new pacing on their next published update without re-opening the view.
   */
  public void updateRefreshInterval(Duration marketUpdateMinInterval) {
    Objects.requireNonNull(marketUpdateMinInterval, "marketUpdateMinInterval");
    if (marketUpdateMinInterval.isZero() || marketUpdateMinInterval.isNegative()) {
      throw new IllegalArgumentException("market update interval must be positive");
    }
    this.marketUpdateMinInterval = marketUpdateMinInterval;
  }

  /** Returns a snapshot of the current transfer targets, including hot-added markets. */
  public synchronized List<TransferTarget> transferTargets() {
    return transferTargets;
  }

  public void subscribeMarketUpdates(UUID playerId, Consumer<MarketDataService.PlayerUpdate> consumer) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(consumer, "consumer");
    lastMarketRefresh.remove(playerId);
    marketData.subscribePlayer(playerId, update -> {
      long now = System.currentTimeMillis();
      Long previous = lastMarketRefresh.get(playerId);
      boolean forward = previous == null
          ? lastMarketRefresh.putIfAbsent(playerId, now) == null
          : now - previous >= marketUpdateMinInterval.toMillis()
              && lastMarketRefresh.replace(playerId, previous, now);
      if (forward) {
        consumer.accept(update);
      }
    });
  }

  public void unsubscribeMarketUpdates(UUID playerId) {
    lastMarketRefresh.remove(Objects.requireNonNull(playerId, "playerId"));
    marketData.unsubscribePlayer(playerId);
  }

  public CompletableFuture<List<MarketRow>> marketRows() {
    return marketList().thenApply(MarketListSnapshot::markets);
  }

  public CompletableFuture<MarketListSnapshot> marketList() {
    return CompletableFuture.supplyAsync(() -> {
      List<MarketListPresenter.Entry> entries = loadMarketEntries();
      return new MarketListSnapshot(presenter.rows(entries), presenter.overview(entries));
    }, executor);
  }

  public CompletableFuture<MarketOverviewSnapshot> marketOverview() {
    return marketList().thenApply(MarketListSnapshot::overview);
  }

  public CompletableFuture<MarketRow> marketRow(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    MarketView market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities =
            loadSecurityDefinitions();
        return presenter.rows(List.of(new MarketListPresenter.Entry(market.marketId(),
            market.displayName(), market.service().marketQuote(marketData),
            market.assetType(), market.symbol(), market.totalSupply(),
            securityStatus(securities, market.marketId()),
            issuedSupply(securities, market), recentTrades(market.marketId()))))
            .getFirst();
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market quote: " + marketId, failure);
      }
    }, executor);
  }

  public String resolveMarketIdBySymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    for (MarketView market : markets.values()) {
      if (market.symbol() != null && market.symbol().equalsIgnoreCase(symbol)) {
        return market.marketId();
      }
    }
    return null;
  }

  /** All configured security symbols, sorted, for tab completion. */
  public List<String> securitySymbols() {
    return markets.values().stream()
        .map(MarketView::symbol)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  /** Returns the latest market quote for a market id, or null when the market is unknown. */
  public MarketQuote marketQuote(String marketId) {
    MarketView market = markets.get(marketId);
    if (market == null) {
      return null;
    }
    try {
      return market.service().marketQuote(marketData);
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load market quote: " + marketId, failure);
    }
  }

  /** Loads the latest market quote on the background read executor. */
  public CompletableFuture<MarketQuote> marketQuoteAsync(String marketId) {
    return CompletableFuture.supplyAsync(() -> marketQuote(marketId), executor);
  }

  /** Loads quotes for the requested market ids on the background read executor. */
  public CompletableFuture<Map<String, MarketQuote>> marketQuotes(Collection<String> marketIds) {
    List<String> ids = marketIds == null ? List.of() : List.copyOf(marketIds);
    return CompletableFuture.supplyAsync(() -> {
      Map<String, MarketQuote> quotes = new java.util.HashMap<>();
      for (String marketId : ids) {
        MarketQuote quote = marketQuote(marketId);
        if (quote != null) {
          quotes.put(marketId, quote);
        }
      }
      return Map.copyOf(quotes);
    }, executor);
  }

  /** Returns the configured market view, or null when the market id is unknown. */
  public MarketView market(String marketId) {
    return marketId == null ? null : markets.get(marketId);
  }

  /** Returns the configured market display name, or the market id when unknown. */
  public String marketDisplayName(String marketId) {
    MarketView market = markets.get(marketId);
    return market == null ? marketId : market.displayName();
  }

  public CompletableFuture<MarketDashboardSnapshot> marketDashboard(String marketId) {
    return marketDashboard(marketId, Duration.ofMinutes(9));
  }

  public CompletableFuture<MarketDashboardSnapshot> marketDashboard(
      String marketId, Duration candleWindow) {
    MarketView market = requiredMarket(marketId);
    Duration window = candleWindow == null || candleWindow.isZero() || candleWindow.isNegative()
        ? Duration.ofMinutes(9) : candleWindow;
    return CompletableFuture.supplyAsync(() -> {
      try {
        PersistentOrderService.MarketBookSnapshot book = market.service()
            .marketBookSnapshot(marketData, 5);
        MarketQuote quote = marketData.quote(marketId, book.referencePrice(), book.bestBid(),
            book.bestAsk(), book.status(), book.asOf());
        Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities =
            loadSecurityDefinitions();
        MarketListPresenter.Entry entry = new MarketListPresenter.Entry(market.marketId(),
            market.displayName(), quote, market.assetType(), market.symbol(),
            market.totalSupply(), securityStatus(securities, market.marketId()),
            issuedSupply(securities, market),
            recentTrades(market.marketId()));
        int scale = market.service().marketRules().priceScale();
        MarketRow row = presenter.rows(List.of(entry)).getFirst();
        if (row.notional24h() != null) {
          row = new MarketRow(row.marketId(), row.displayName(), row.lastPrice(),
              row.bestBid(), row.bestAsk(), row.change24h(), row.volume24h(), row.status(),
              row.assetType(), row.symbol(), row.totalSupply(), row.securityStatus(),
              row.volatility24h(), row.high24h(), row.low24h(), row.issuedSupply(),
              row.notional24h().setScale(scale, RoundingMode.HALF_UP), row.recentTrades());
        }
        Instant asOf = book.asOf();
        List<com.ghostchu.quickshop.addon.exchange.marketdata.Candle> candles =
            marketData.recentCandles(marketId, asOf.minus(window),
                asOf.plusSeconds(60));
        BigDecimal spread = spread(quote.bestBid(), quote.bestAsk());
        List<ExchangeRepository.MarketTradeRow> recentTrades = repository == null ? List.of()
            : repository.marketTrades(marketId, 6);
        ExchangeRepository.MarketTradeSummary tradeSummary = repository == null ? null
            : repository.marketTradeSummary(marketId, asOf.minus(Duration.ofHours(24)));
        BigDecimal notional = quote.notional24h() == null ? null
            : quote.notional24h().setScale(scale, RoundingMode.HALF_UP);
        return new MarketDashboardSnapshot(row, candles, book.bids(), book.asks(), spread,
            spreadPercent(spread, quote.bestBid(), quote.bestAsk()), notional,
            recentTrades, tradeSummary);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market dashboard: " + marketId, failure);
      }
    }, executor);
  }

  public CompletableFuture<List<ExchangeTransaction.PersistedOrder>> accountOrders(
      UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 100 || offset < 0) {
      throw new IllegalArgumentException("invalid account order page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountOpenOrders(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account orders", failure);
      }
    }, executor);
  }

  /** Loads one currently cancellable order for the cancellation confirmation page. */
  public CompletableFuture<java.util.Optional<ExchangeTransaction.PersistedOrder>> accountOpenOrder(
      UUID accountId, UUID orderId) {
    if (accountId == null || orderId == null) {
      throw new IllegalArgumentException("account and order ids are required");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.openOrder(accountId, orderId);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load open order", failure);
      }
    }, executor);
  }

  /** Loads a bounded page of a market's recent trades on the background executor. */
  public CompletableFuture<List<ExchangeRepository.MarketTradeRow>> marketTradePage(
      String marketId, int limit, int offset) {
    if (marketId == null || marketId.isBlank() || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid market trade page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("market trade views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.marketTradesPage(marketId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market trades: " + marketId, failure);
      }
    }, executor);
  }

  public CompletableFuture<List<AccountAssetBalance>> accountAssets(UUID accountId) {
    Objects.requireNonNull(accountId, "accountId");
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountAssets(accountId);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account assets", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow>>
      accountTrades(UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account trade page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountTrades(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account trades", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<TransferRecord>> accountTransfers(
      UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account transfer page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountTransfers(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account transfers", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<AccountLedgerEntry>> accountLedger(
      UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account ledger page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountLedgerEntries(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account ledger", failure);
      }
    }, executor);
  }

  private List<MarketListPresenter.Entry> loadMarketEntries() {
    List<MarketListPresenter.Entry> entries = new ArrayList<>();
    Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities =
        loadSecurityDefinitions();
    for (MarketView market : markets.values()) {
      try {
        String securityStatus = market.assetType() != null
            && "VIRTUAL_SECURITY".equals(market.assetType())
            ? securityStatus(securities, market.marketId()) : null;
        entries.add(new MarketListPresenter.Entry(market.marketId(), market.displayName(),
            market.service().marketQuote(marketData), market.assetType(), market.symbol(),
            market.totalSupply(), securityStatus,
            issuedSupply(securities, market),
            recentTrades(market.marketId())));
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market quote: " + market.marketId(), failure);
      }
    }
    return List.copyOf(entries);
  }

  private Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState>
      loadSecurityDefinitions() {
    if (repository == null) {
      return Map.of();
    }
    try {
      return repository.securityDefinitionStates();
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load security definitions", failure);
    }
  }

  private static com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState
      securityState(
          Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities,
          String marketId) {
    return securities.get(marketId);
  }

  private static String securityStatus(
      Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities,
      String marketId) {
    com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState state =
        securityState(securities, marketId);
    return state == null ? null : state.status();
  }

  private static Long issuedSupply(
      Map<String, com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState> securities,
      MarketView market) {
    if (market.assetType() == null || !"VIRTUAL_SECURITY".equals(market.assetType())) {
      return null;
    }
    com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState state =
        securityState(securities, market.marketId());
    return state == null ? null : state.issuedSupply();
  }

  private MarketView requiredMarket(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    MarketView market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return market;
  }

  private List<MarketRow.TradeLore> recentTrades(String marketId) {
    if (repository == null) {
      return List.of();
    }
    try {
      return repository.marketTrades(marketId, 1).stream()
          .map(trade -> new MarketRow.TradeLore(trade.price(), trade.quantity(),
              trade.takerSide().name(),
              trade.takerSide() == com.ghostchu.quickshop.addon.exchange.core.model.OrderSide.BUY))
          .toList();
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load recent trades: " + marketId, failure);
    }
  }

  private static BigDecimal spread(BigDecimal bid, BigDecimal ask) {
    return bid == null || ask == null ? null : ask.subtract(bid);
  }

  private static BigDecimal spreadPercent(BigDecimal spread, BigDecimal bid, BigDecimal ask) {
    if (spread == null) {
      return null;
    }
    BigDecimal midpoint = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    return midpoint.signum() == 0 ? null : spread.divide(midpoint, 8, RoundingMode.HALF_UP);
  }

  public record MarketView(String marketId, String displayName, PersistentOrderService service,
                           String assetType, String symbol, Long totalSupply,
                           Supplier<String> securityStatus, Supplier<Long> issuedSupply) {
    public MarketView(String marketId, String displayName, PersistentOrderService service) {
      this(marketId, displayName, service, null, null, null, () -> null, () -> null);
    }

    public MarketView {
      if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display data is required");
      }
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(securityStatus, "securityStatus");
      Objects.requireNonNull(issuedSupply, "issuedSupply");
    }
  }
}
