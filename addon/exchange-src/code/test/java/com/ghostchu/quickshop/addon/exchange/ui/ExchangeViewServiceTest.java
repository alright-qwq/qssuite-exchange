package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeViewServiceTest {
  @Test
  void exposesConfiguredTransferTargetsEvenBeforeAnAccountHasBalances() {
    List<TransferTarget> targets = List.of(
        TransferTarget.currency("default"),
        TransferTarget.item("diamond/default", "Diamond / Default"));
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run,
        new RecordingRepository(), targets);

    assertThat(views.transferTargets()).containsExactlyElementsOf(targets);
    assertThatThrownBy(() -> views.transferTargets().add(TransferTarget.currency("other")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void loadsOnlyTheRequestedAccountOrderPageOffThePlayerThread() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    List<PersistedOrder> orders = views.accountOrders(accountId, 36, 72).join();

    assertThat(repository.accountId).isEqualTo(accountId);
    assertThat(repository.limit).isEqualTo(36);
    assertThat(repository.offset).isEqualTo(72);
    assertThat(orders).extracting(persisted -> persisted.order().orderId())
        .containsExactly(repository.order.orderId());
    assertThat(repository.calls).hasValue(1);
  }

  @Test
  void loadsOneOpenOrderForTheCancellationConfirmation() {
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    var persisted = views.accountOpenOrder(accountId, orderId).join();

    assertThat(persisted).isPresent();
    assertThat(persisted.get().order().orderId()).isEqualTo(repository.order.orderId());
    assertThat(repository.openAccountId).isEqualTo(accountId);
    assertThat(repository.openOrderId).isEqualTo(orderId);
  }

  @Test
  void roundsMarketNotionalToTwoDecimalsInDashboardAndOverview() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    PersistentOrderService marketService = fixture.serviceWithMarketData(marketData);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    UUID seller = fixture.accountWithItems(2);
    marketService.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("99.00"), null, 2));
    marketService.place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("101.00"), null, 2));
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of("diamond-usd",
        new ExchangeViewService.MarketView("diamond-usd", "Diamond", marketService)),
        marketData, Runnable::run);

    MarketDashboardSnapshot dashboard = views.marketDashboard("diamond-usd").join();
    MarketOverviewSnapshot overview = views.marketOverview().join();

    assertThat(dashboard.notional24h()).isEqualByComparingTo("198.00");
    assertThat(overview.totalNotional24h()).isEqualByComparingTo("198.00");
    assertThat(overview.mostActive().notional24h()).isEqualByComparingTo("198.00");
  }

  @Test
  void loadsOnlyTheRequestedAccountAssetsOffThePlayerThread() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    List<AccountAssetBalance> assets = views.accountAssets(accountId).join();

    assertThat(repository.assetAccountId).isEqualTo(accountId);
    assertThat(assets).containsExactly(new AccountAssetBalance("currency", "default",
        new BigDecimal("12.50"), new BigDecimal("1.00")));
  }

  @Test
  void attributesMakerAndTakerFeesToTheCorrectAccount() {
    UUID taker = UUID.randomUUID();
    UUID maker = UUID.randomUUID();
    Trade trade = new Trade(UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        UUID.randomUUID(), taker, maker, new BigDecimal("100.00"), 2,
        new BigDecimal("0.50"), new BigDecimal("1.50"), 1, Instant.EPOCH);
    var row = new com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow(
        trade, taker);

    assertThat(row.feeFor(taker)).isEqualByComparingTo("1.50");
    assertThat(row.feeFor(maker)).isEqualByComparingTo("0.50");
    assertThat(row.feeFor(UUID.randomUUID())).isNull();
  }

  @Test
  void exposesConfiguredMarketViewByNameAndRejectsUnknownIds() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var market = new ExchangeViewService.MarketView("diamond-usd", "Diamond",
        fixture.service());
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of("diamond-usd", market),
        new MarketDataService(new CandleAggregator()), Runnable::run);

    assertThat(views.market("diamond-usd")).isSameAs(market);
    assertThat(views.market("unknown")).isNull();
    assertThat(views.market(null)).isNull();
    assertThat(views.marketDisplayName("unknown")).isEqualTo("unknown");
    assertThat(views.marketDisplayName("diamond-usd")).isEqualTo("Diamond");
  }

  @Test
  void loadsOnlyTheRequestedAccountTradePage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountTrades(accountId, 36, 36).join())
        .containsExactly(new com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow(
            repository.trade, repository.tradeTakerAccountId));
    assertThat(repository.tradeAccountId).isEqualTo(accountId);
    assertThat(repository.tradeLimit).isEqualTo(36);
    assertThat(repository.tradeOffset).isEqualTo(36);
  }

  @Test
  void loadsTheRequestedAccountTransferPage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountTransfers(accountId, 36, 0).join()).containsExactly(repository.transfer);
    assertThat(repository.transferAccountId).isEqualTo(accountId);
  }

  @Test
  void loadsOnlyTheRequestedAccountLedgerPage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountLedger(accountId, 18, 36).join())
        .containsExactly(repository.ledgerEntry);
    assertThat(repository.ledgerAccountId).isEqualTo(accountId);
    assertThat(repository.ledgerLimit).isEqualTo(18);
    assertThat(repository.ledgerOffset).isEqualTo(36);
  }

  @Test
  void composesOneMarketDashboardAndMarketOverviewOnTheReadExecutor() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    UUID buyer = fixture.accountWithCurrency("1000.00");
    UUID seller = fixture.accountWithItems(2);
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("99.00"), null, 2));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("101.00"), null, 2));
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of("diamond-usd",
        new ExchangeViewService.MarketView("diamond-usd", "Diamond", fixture.service())),
        marketData, Runnable::run);

    MarketDashboardSnapshot dashboard = views.marketDashboard("diamond-usd").join();
    MarketOverviewSnapshot overview = views.marketOverview().join();

    assertThat(dashboard.market().displayName()).isEqualTo("Diamond");
    assertThat(dashboard.bids()).singleElement().extracting(
        com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService.DepthLevel::price)
        .isEqualTo(new BigDecimal("99.00"));
    assertThat(dashboard.asks()).singleElement().extracting(
        com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService.DepthLevel::price)
        .isEqualTo(new BigDecimal("101.00"));
    assertThat(overview.marketCount()).isEqualTo(1);
    assertThat(overview.mostActive().marketId()).isEqualTo("diamond-usd");
  }

  @Test
  void loadsMarketQuoteAsyncThroughTheBackgroundExecutor() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of("diamond-usd",
        new ExchangeViewService.MarketView("diamond-usd", "Diamond", fixture.service())),
        marketData, Runnable::run);

    com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote =
        views.marketQuoteAsync("diamond-usd").join();

    assertThat(quote).isNotNull();
    assertThat(quote.marketId()).isEqualTo("diamond-usd");
  }

  @Test
  void forwardsCoalescedMarketUpdatesUntilThePlayerUnsubscribes() {
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of(), marketData,
        Runnable::run);
    UUID playerId = UUID.randomUUID();
    AtomicInteger updates = new AtomicInteger();
    views.subscribeMarketUpdates(playerId, update -> updates.incrementAndGet());

    marketData.recordTrade("diamond-usd", new BigDecimal("100"), 1, Instant.EPOCH);
    marketData.publishPlayerUpdates();
    views.unsubscribeMarketUpdates(playerId);
    marketData.recordTrade("diamond-usd", new BigDecimal("101"), 1, Instant.EPOCH.plusSeconds(1));
    marketData.publishPlayerUpdates();

    assertThat(updates).hasValue(1);
  }

  @Test
  void throttlesMarketUpdateRefreshesToOnePerSecond() throws Exception {
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of(), marketData,
        Runnable::run);
    UUID playerId = UUID.randomUUID();
    AtomicInteger updates = new AtomicInteger();
    views.subscribeMarketUpdates(playerId, update -> updates.incrementAndGet());

    marketData.recordTrade("diamond-usd", new BigDecimal("100"), 1, Instant.EPOCH);
    marketData.publishPlayerUpdates();
    marketData.recordTrade("diamond-usd", new BigDecimal("101"), 1, Instant.EPOCH.plusSeconds(1));
    marketData.publishPlayerUpdates();
    assertThat(updates).hasValue(1);

    Thread.sleep(1100);
    marketData.recordTrade("diamond-usd", new BigDecimal("102"), 1, Instant.EPOCH.plusSeconds(2));
    marketData.publishPlayerUpdates();
    assertThat(updates).hasValue(2);
  }

  @Test
  void resolvesSecuritySymbolToItsMarketIdCaseInsensitively() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    ExchangeViewService views = new ExchangeViewService(java.util.Map.of("concept_alpha",
        new ExchangeViewService.MarketView("concept_alpha", "Alpha", fixture.service(),
            "VIRTUAL_SECURITY", "ALPHA", 1000L, () -> "OPEN", () -> 400L)),
        marketData, Runnable::run);

    assertThat(views.resolveMarketIdBySymbol("alpha")).isEqualTo("concept_alpha");
    assertThat(views.resolveMarketIdBySymbol("ALPHA")).isEqualTo("concept_alpha");
    assertThat(views.resolveMarketIdBySymbol("BETA")).isNull();
  }

  private static final class RecordingRepository implements ExchangeRepository {
    private final Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd",
        UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
        new BigDecimal("100.00"), null, 10, 10, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH);
    private final AtomicInteger calls = new AtomicInteger();
    private UUID accountId;
    private int limit;
    private int offset;
    private UUID assetAccountId;
    private final Trade trade = new Trade(UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"),
        2, new BigDecimal("0.10"), new BigDecimal("0.20"), 1, Instant.EPOCH);
    private UUID tradeAccountId;
    private int tradeLimit;
    private int tradeOffset;
    private final UUID tradeTakerAccountId = UUID.randomUUID();
    private final TransferRecord transfer = new TransferRecord(UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), TransferType.MONEY_DEPOSIT, "default", new BigDecimal("5.00"),
        TransferStatus.REVIEW_REQUIRED, null, "review", Instant.EPOCH, Instant.EPOCH, 1);
    private UUID transferAccountId;
    private final AccountLedgerEntry ledgerEntry = new AccountLedgerEntry(
        UUID.randomUUID(), "MONEY_DEPOSIT", UUID.randomUUID(), "default",
        new BigDecimal("5.00"), Instant.EPOCH);
    private UUID ledgerAccountId;
    private int ledgerLimit;
    private int ledgerOffset;
    private UUID openAccountId;
    private UUID openOrderId;

    @Override
    public List<AccountLedgerEntry> accountLedgerEntries(UUID accountId, int limit, int offset) {
      ledgerAccountId = accountId;
      ledgerLimit = limit;
      ledgerOffset = offset;
      return List.of(ledgerEntry);
    }

    @Override
    public List<TransferRecord> accountTransfers(UUID accountId, int limit, int offset) {
      transferAccountId = accountId;
      return List.of(transfer);
    }

    @Override
    public List<com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow>
        accountTrades(UUID accountId, int limit, int offset) {
      tradeAccountId = accountId;
      tradeLimit = limit;
      tradeOffset = offset;
      return List.of(new com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository.AccountTradeRow(
          trade, tradeTakerAccountId));
    }

    @Override
    public List<AccountAssetBalance> accountAssets(UUID accountId) {
      assetAccountId = accountId;
      return List.of(new AccountAssetBalance("currency", "default",
          new BigDecimal("12.50"), new BigDecimal("1.00")));
    }

    @Override
    public List<PersistedOrder> accountOpenOrders(UUID accountId, int limit, int offset) {
      this.accountId = accountId;
      this.limit = limit;
      this.offset = offset;
      calls.incrementAndGet();
      return List.of(new PersistedOrder(order, BigDecimal.ZERO, 0, 1));
    }

    @Override
    public java.util.Optional<PersistedOrder> openOrder(UUID accountId, UUID orderId) {
      openAccountId = accountId;
      openOrderId = orderId;
      return java.util.Optional.of(new PersistedOrder(order, BigDecimal.ZERO, 0, 1));
    }

    @Override
    public <T> T inTransaction(TransactionWork<T> work) {
      throw new AssertionError("account page must use the paged read method");
    }
  }
}
