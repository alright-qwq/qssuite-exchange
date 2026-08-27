package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentOrderServiceTest {
  @Test
  void locksOnlyAssetsInvolvedInTheOrderOrItsTrades() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(2);

    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(fixture.hasCurrencyBalance(seller)).isFalse();
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("90.00"), null, 1));

    assertThat(fixture.hasInventoryBalance(buyer)).isFalse();
    assertThat(fixture.hasCurrencyBalance(seller)).isFalse();
  }

  @Test
  void rejectsEveryNonOpenMarketWithoutReservationOrStateMutation() throws Exception {
    for (String status : Set.of("HALTED", "PAUSED", "RECOVERING", "CLOSED")) {
      ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
      UUID seller = fixture.accountWithItems(2);
      fixture.setMarketStatus(status);

      assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
          UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
          new BigDecimal("100.00"), null, 1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("MARKET_NOT_OPEN");

      assertThat(fixture.orderCount()).isZero();
      assertThat(fixture.availableItems(seller)).isEqualTo(2);
      assertThat(fixture.frozenItems(seller)).isZero();
      assertThat(fixture.marketPrioritySequence()).isZero();
      assertThat(fixture.marketStatus()).isEqualTo(status);
    }
  }

  @Test
  void rejectsLimitOrderOutsideCageBeforeItEntersTransaction() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    fixture.service().recoverFromDatabase();
    AtomicInteger transactionEntries = new AtomicInteger();
    PersistentOrderService guarded = fixture.serviceWithTransactionEntry(
        transactionEntries::incrementAndGet);

    assertThatThrownBy(() -> guarded.place(new OrderRequest(
        UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("120.01"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PRICE_OUTSIDE_CAGE");

    assertThat(fixture.orderCount()).isZero();
    assertThat(transactionEntries).hasValue(0);
  }

  @Test
  void returnsStoredReceiptWhenDuplicateRetryWouldOtherwiseExceedRateLimit() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    PersistentOrderService limited = fixture.serviceWithAccountLimits(
        new AccountOrderLimits(100_000, new BigDecimal("10000000.00"), 100, 1, 60));
    UUID seller = fixture.accountWithItems(1);
    OrderRequest request = new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1);

    OrderReceipt first = limited.place(request);
    OrderReceipt retry = limited.place(request);

    assertThat(retry).isEqualTo(first);
    assertThat(fixture.orderCount()).isEqualTo(1);
  }

  @Test
  void returnsStoredReceiptWhenRetryIsOutsideTheCurrentPriceCage() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    OrderRequest request = new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1);

    OrderReceipt first = fixture.service().place(request);
    fixture.setMarketReferencePrice("200.00");
    fixture.service().recoverFromDatabase();

    assertThat(fixture.service().place(request)).isEqualTo(first);
    assertThat(fixture.orderCount()).isEqualTo(1);
  }

  @Test
  void rechecksLimitPriceAgainstLatestTransactionReference() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    PersistentOrderService service = fixture.serviceWithTransactionEntry(
        () -> {
          try {
            fixture.setMarketReferencePrice("200.00");
          } catch (SQLException failure) {
            throw new IllegalStateException(failure);
          }
        });

    assertThatThrownBy(() -> service.place(new OrderRequest(
        UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("120.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PRICE_OUTSIDE_CAGE");

    assertThat(fixture.orderCount()).isZero();
  }

  @Test
  void rejectsSelfTradeWithExplicitReasonBeforeReservations() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(1);
    fixture.creditCurrency(account, "1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), account, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), account, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELF_TRADE");

    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.frozenCurrency(account)).isZero();
  }

  @Test
  void playerCancellationCannotCancelAnotherAccountOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID owner = fixture.accountWithItems(1);
    UUID other = fixture.accountWithItems(1);
    OrderReceipt placed = fixture.service().place(new OrderRequest(UUID.randomUUID(), owner,
        "diamond-usd", OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThatThrownBy(() -> fixture.service().cancel(other, UUID.randomUUID(), placed.orderId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not owned");
    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.frozenItems(owner)).isEqualTo(1);
  }

  @Test
  void replaysPlayerCancellationAfterTheOrderIsAlreadyCancelled() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID requestId = UUID.randomUUID();
    OrderReceipt order = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        "diamond-usd", OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    OrderReceipt first = fixture.service().cancel(seller, requestId, order.orderId());
    OrderReceipt replay = fixture.service().cancel(seller, requestId, order.orderId());

    assertThat(replay).isEqualTo(first);
  }

  @Test
  void rejectsReusingCancellationRequestIdForAnotherOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(2);
    UUID requestId = UUID.randomUUID();
    OrderReceipt firstOrder = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        "diamond-usd", OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    OrderReceipt secondOrder = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        "diamond-usd", OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    fixture.service().cancel(seller, requestId, firstOrder.orderId());

    assertThatThrownBy(() -> fixture.service().cancel(seller, requestId, secondOrder.orderId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request id belongs to another cancellation target");
  }

  @Test
  void rejectsMarketOrderWhoseProtectionExceedsConfiguredMaximumSlippage() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "MARKET", null,
        new BigDecimal("125.00"), 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SLIPPAGE_TOO_HIGH");

    assertThat(fixture.tradeCount()).isZero();
    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.frozenCurrency(buyer)).isZero();
  }

  @Test
  void rejectsSixthOrderFromAccountWithinOneSecond() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(6);
    for (int index = 0; index < 5; index++) {
      fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
          OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    }

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RATE_LIMITED");

    assertThat(fixture.orderCount()).isEqualTo(5);
    assertThat(fixture.frozenItems(seller)).isEqualTo(5);
  }

  @Test
  void rejectsOrderWhenAccountAlreadyHasMaximumOpenOrders() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    PersistentOrderService limited = fixture.serviceWithAccountLimits(
        new AccountOrderLimits(100_000, new BigDecimal("10000000.00"), 1, 5, 60));
    UUID seller = fixture.accountWithItems(2);
    limited.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThatThrownBy(() -> limited.place(new OrderRequest(
        UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPEN_ORDER_LIMIT");

    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.frozenItems(seller)).isEqualTo(1);
  }

  @Test
  void rejectsBuyOrderWhoseReservationExceedsFrozenCurrencyLimit() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    PersistentOrderService limited = fixture.serviceWithAccountLimits(
        new AccountOrderLimits(100_000, BigDecimal.ONE, 100, 5, 60));
    UUID buyer = fixture.accountWithCurrency("1000.00");

    assertThatThrownBy(() -> limited.place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FROZEN_LIMIT");

    assertThat(fixture.orderCount()).isZero();
    assertThat(fixture.frozenCurrency(buyer)).isZero();
  }

  @Test
  void rejectsBuyOrderWhoseMaximumPotentialHoldingExceedsLimit() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    PersistentOrderService limited = fixture.serviceWithAccountLimits(
        new AccountOrderLimits(1, new BigDecimal("10000000.00"), 100, 5, 60));
    UUID buyer = fixture.accountWithItems(1);
    fixture.creditCurrency(buyer, "1000.00");

    assertThatThrownBy(() -> limited.place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HOLDING_LIMIT");

    assertThat(fixture.orderCount()).isZero();
    assertThat(fixture.frozenCurrency(buyer)).isZero();
  }

  @Test
  void commitsTradeAndReturnsSameReceiptForDuplicateRequest() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(10);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    UUID request = UUID.randomUUID();
    OrderRequest buy = new OrderRequest(request, buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 2);

    OrderReceipt first = fixture.service().place(buy);
    OrderReceipt duplicate = fixture.service().place(buy);

    assertThat(duplicate).isEqualTo(first);
    assertThat(first.trades()).hasSize(1);
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.ledgerIsBalanced()).isTrue();
    assertThat(fixture.feeAccountBalance()).isPositive();
  }

  @Test
  void publishesCommittedTradesToMarketDataWithoutDuplicatingRequests() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    PersistentOrderService service = fixture.serviceWithMarketData(marketData);
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    service.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    UUID requestId = UUID.randomUUID();
    OrderRequest buy = new OrderRequest(requestId, buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1);

    service.place(buy);
    service.place(buy);

    assertThat(marketData.quote("diamond-usd", new BigDecimal("100.00"),
        (BigDecimal) null, (BigDecimal) null, MarketStatus.OPEN,
        java.time.Instant.now()).volume24h()).isEqualTo(1L);
  }

  @Test
  void exposesOnlyCageExecutableBestQuoteFromTheCommittedOrderBook() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    PersistentOrderService service = fixture.serviceWithMarketData(marketData);
    UUID seller = fixture.accountWithItems(1);
    service.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(service.marketQuote(marketData).bestAsk()).isEqualByComparingTo("100.00");
  }

  @Test
  void exposesBestFiveBidAndAskLevelsFromTheCommittedOrderBook() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    PersistentOrderService service = fixture.serviceWithMarketData(marketData);
    UUID firstBuyer = fixture.accountWithCurrency("1000.00");
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    UUID seller = fixture.accountWithItems(3);
    service.place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("99.00"), null, 2));
    service.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("98.00"), null, 1));
    service.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("101.00"), null, 3));

    var depth = service.marketDepth(marketData, 5);

    assertThat(depth.bids()).extracting(MarketDataService.DepthLevel::price)
        .containsExactly(new BigDecimal("99.00"), new BigDecimal("98.00"));
    assertThat(depth.asks()).extracting(MarketDataService.DepthLevel::price)
        .containsExactly(new BigDecimal("101.00"));
  }

  @Test
  void capturesQuoteAndDepthFromOneCommittedMarketBookSnapshot() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID buyer = fixture.accountWithCurrency("1000.00");
    UUID seller = fixture.accountWithItems(2);
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("99.00"), null, 2));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("101.00"), null, 2));

    PersistentOrderService.MarketBookSnapshot snapshot = fixture.service().marketBookSnapshot(
        new MarketDataService(new CandleAggregator()), 5);

    assertThat(snapshot.status()).isEqualTo(MarketStatus.OPEN);
    assertThat(snapshot.bestBid()).isEqualByComparingTo("99.00");
    assertThat(snapshot.bestAsk()).isEqualByComparingTo("101.00");
    assertThat(snapshot.bids()).extracting(MarketDataService.DepthLevel::price)
        .containsExactly(snapshot.bestBid());
    assertThat(snapshot.asks()).extracting(MarketDataService.DepthLevel::price)
        .containsExactly(snapshot.bestAsk());
  }

  @Test
  void doesNotHoldTheMarketWriteLockWhileLoadingSnapshotStatus() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    CountDownLatch statusReadStarted = new CountDownLatch(1);
    CountDownLatch releaseStatusRead = new CountDownLatch(1);
    PersistentOrderService blockingSnapshot = fixture.serviceWithTransactionEntry(() -> {
      statusReadStarted.countDown();
      try {
        if (!releaseStatusRead.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("status read was not released");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for status read", failure);
      }
    });
    UUID seller = fixture.accountWithItems(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> snapshot = workers.submit(() -> {
        try {
          blockingSnapshot.marketBookSnapshot(new MarketDataService(new CandleAggregator()), 5);
        } catch (SQLException failure) {
          throw new IllegalStateException(failure);
        }
      });
      assertThat(statusReadStarted.await(1, TimeUnit.SECONDS)).isTrue();

      Future<OrderReceipt> placed = workers.submit(() -> fixture.service().place(new OrderRequest(
          UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
          new BigDecimal("100.00"), null, 1)));

      assertThat(placed.get(1, TimeUnit.SECONDS).status()).isEqualTo("OPEN");
      releaseStatusRead.countDown();
      snapshot.get(1, TimeUnit.SECONDS);
    } finally {
      releaseStatusRead.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void rollsBackSettlementMarksRecoveringAndInvokesRecoveryAfterSqlFailure() throws Exception {
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    AtomicReference<Throwable> recoveryFailure = new AtomicReference<>();
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite((market, failure) -> {
      recoveredMarket.set(market);
      recoveryFailure.set(failure);
    });
    UUID seller = fixture.accountWithItems(10);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    fixture.failTradeInserts();

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2))).isInstanceOf(SQLException.class);

    assertThat(fixture.tradeCount()).isZero();
    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("1000.00");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("0");
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
    assertThat(recoveredMarket).hasValue("diamond-usd");
    assertThat(recoveryFailure.get()).isInstanceOf(SQLException.class);
  }

  @Test
  void releasesFilledLimitBuyPriceImprovementAndWritesRequiredJournalAccounts()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));

    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("899.80");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("0");
    assertThat(fixture.journalAccountKinds()).containsExactlyInAnyOrderElementsOf(Set.of(
        "buyer-currency", "seller-currency", "fee-currency", "currency-custody",
        "seller-item", "buyer-item", "item-custody"));
  }

  @Test
  void partiallyFilledLimitBuyRetainsOnlyRemainingWorstCaseReservation() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 2));

    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("789.58");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("110.22");
  }

  @Test
  void persistsCircuitBreakerStateAndPreHaltReferenceWithTrade() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("HALTED");
    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("100.00");
    assertThat(fixture.marketLastPrice()).isEqualByComparingTo("120.00");
    assertThat(fixture.marketHaltedUntil()).isNotNull();
    assertThat(fixture.marketDiscoveryQuantity()).isEqualTo("1");
    assertThat(fixture.marketCircuitBreakerLevel()).isEqualTo("1");
  }

  @Test
  void settlesValidZeroFeeMarketWithoutZeroAmountBalanceMutations() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqliteWithFees("0", "0");
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    OrderReceipt receipt = fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer,
        "diamond-usd", OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(receipt.trades()).hasSize(1);
    assertThat(fixture.feeAccountBalance()).isEqualByComparingTo("0");
    assertThat(fixture.ledgerIsBalanced()).isTrue();
  }

  @Test
  void reloadPersistsVersionsUsedByRestartedService() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketRegistry registry = fixture.marketRegistry();
    fixture.setMarketStatus("PAUSED");
    registry.reload(Map.of("diamond-usd",
        fixture.marketDefinition("0.02", "0.001", "0.002")),
        market -> new com.ghostchu.quickshop.addon.exchange.config.MarketStateReader.State(
            MarketStatus.PAUSED, 0));
    fixture.resumeMarket();

    UUID seller = fixture.accountWithItems(1);
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    registry.reload(Map.of("diamond-usd",
        fixture.marketDefinition("0.02", "0.010", "0.020")),
        market -> new com.ghostchu.quickshop.addon.exchange.config.MarketStateReader.State(
            MarketStatus.OPEN, 1));
    MarketRegistry restartedRegistry = new MarketRegistry(Map.of("diamond-usd",
        fixture.marketDefinition("0.02", "0.010", "0.020")), fixture.repository());

    PersistentOrderService restarted = fixture.restartedService();
    UUID buyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(fixture.lastTradeMakerFee()).isEqualByComparingTo("0.10");
    assertThat(fixture.lastTradeTakerFee()).isEqualByComparingTo("2.00");
    assertThat(fixture.latestOrderFeeVersion()).isEqualTo(2);
    assertThat(fixture.latestOrderConfigVersion()).isEqualTo(2);
    assertThat(restartedRegistry.versions("diamond-usd"))
        .isEqualTo(new MarketRegistry.Versions(2, 2, 2));
  }

  @Test
  void reloadPersistsCurrencyScaleWithAnEmptyPausedBook() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketRegistry registry = fixture.marketRegistry();

    registry.reload(Map.of("diamond-usd",
        fixture.marketDefinition("0.01", "0.001", "0.002", 3)),
        market -> new com.ghostchu.quickshop.addon.exchange.config.MarketStateReader.State(
            MarketStatus.PAUSED, 0));

    MarketRegistry restarted = new MarketRegistry(Map.of("diamond-usd",
        fixture.marketDefinition("0.01", "0.001", "0.002", 3)), fixture.repository());
    assertThat(restarted.require("diamond-usd").structural().currencyScale()).isEqualTo(3);
    assertThat(restarted.versions("diamond-usd"))
        .isEqualTo(new MarketRegistry.Versions(2, 1, 1));
  }

  @Test
  void refusesToArchiveFeeVersionReferencedByOpenOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketRegistry registry = fixture.marketRegistry();
    UUID seller = fixture.accountWithItems(1);
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    registry.reload(Map.of("diamond-usd",
        fixture.marketDefinition("0.01", "0.010", "0.020")),
        market -> new com.ghostchu.quickshop.addon.exchange.config.MarketStateReader.State(
            MarketStatus.OPEN, 1));

    assertThatThrownBy(() -> fixture.archiveFeeVersion(1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("open order");
  }

  @Test
  void preservesSqlFailureWhenRecoveryCallbackAlsoFails() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite((market, failure) -> {
      throw new IllegalStateException("forced recovery failure");
    });
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    fixture.failTradeInserts();

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(SQLException.class)
        .satisfies(failure -> assertThat(failure.getSuppressed())
            .anyMatch(suppressed -> suppressed instanceof IllegalStateException
                && suppressed.getMessage().equals("forced recovery failure")));
  }

  @Test
  void idempotencyConflictDoesNotPutMarketIntoRecovery() throws Exception {
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite(
        (market, failure) -> recoveredMarket.set(market));
    UUID seller = fixture.accountWithItems(1);
    UUID requestId = UUID.randomUUID();
    fixture.storeRequestResult(seller, requestId, "CANCEL", "{}");

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        requestId, seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another operation");

    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(recoveredMarket).hasNullValue();
    assertThat(fixture.availableItems(seller)).isEqualTo(1);
    assertThat(fixture.frozenItems(seller)).isZero();
  }

  @Test
  void restartedServiceContinuesFromPersistedReferencePrice() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID firstBuyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 1));

    PersistentOrderService restarted = fixture.restartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("110.00"), null, 1));
    restarted.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));

    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("100.15");
  }

  @Test
  void restartedServiceEscalatesLevelTwoAndWritesHighAlert() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID firstBuyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("110.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));
    fixture.resumeMarket();

    PersistentOrderService restarted = fixture.restartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));
    restarted.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.marketStatus()).isEqualTo("HALTED");
    assertThat(fixture.highAlertCount()).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateAcrossServiceInstancesReturnsCommittedReceipt() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    OrderRequest request = new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1);
    PersistentOrderService secondService = fixture.restartedService();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<OrderReceipt> first = executor.submit(() -> {
        start.await();
        return fixture.service().place(request);
      });
      Future<OrderReceipt> second = executor.submit(() -> {
        start.await();
        return secondService.place(request);
      });
      start.countDown();

      assertThat(second.get()).isEqualTo(first.get());
    }
    assertThat(fixture.orderCount()).isEqualTo(2);
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
  }

  @Test
  void serializesSameMarketAcrossServiceInstances() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    PersistentOrderService firstService = fixture.serviceWithTransactionEntry(() -> {
      firstEntered.countDown();
      await(releaseFirst);
    });
    PersistentOrderService secondService = fixture.serviceWithTransactionEntry(
        secondEntered::countDown);

    boolean overlapped;
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<OrderReceipt> first = executor.submit(() -> firstService.place(new OrderRequest(
          UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
          new BigDecimal("100.00"), null, 1)));
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<OrderReceipt> second = executor.submit(() -> secondService.place(new OrderRequest(
          UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
          new BigDecimal("90.00"), null, 1)));

      overlapped = secondEntered.await(1, TimeUnit.SECONDS);
      releaseFirst.countDown();
      first.get();
      second.get();
    } finally {
      releaseFirst.countDown();
    }

    assertThat(overlapped).isFalse();
  }

  @Test
  void reportedCommitFailureReturnsDurableReceiptWithoutRecovery() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    PersistentOrderService uncertain = fixture.serviceWithReportedCommitFailure(
        (market, failure) -> recoveredMarket.set(market));
    OrderRequest request = new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1);

    OrderReceipt receipt = uncertain.place(request);

    assertThat(receipt).isEqualTo(uncertain.place(request));
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(recoveredMarket).hasNullValue();
  }

  @Test
  void reportedCommitFailurePublishesExactRiskHistory() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(50);
    UUID firstBuyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    PersistentOrderService uncertain = fixture.serviceWithReportedCommitFailure(
        RecoveryHandler.NO_OP);

    uncertain.place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    uncertain.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 1));
    uncertain.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 1));

    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("102.55");
  }

  @Test
  void serviceInstancesShareExactRiskHistory() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(50);
    UUID firstBuyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));

    PersistentOrderService secondService = fixture.restartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    secondService.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 1));
    secondService.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 1));

    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("102.55");
  }

  @Test
  void independentRuntimeRecoversExactReferenceHistory() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(50);
    UUID firstBuyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));

    PersistentOrderService restarted = fixture.isolatedRestartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 1));
    restarted.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 1));

    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("102.55");
  }

  @Test
  void independentRuntimeRetainsBreakerLevelAfterBenignTrade() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    trade(fixture, fixture.service(), "110.00", 1);
    fixture.resumeMarket();
    trade(fixture, fixture.service(), "100.00", 1);

    PersistentOrderService restarted = fixture.isolatedRestartedService();
    trade(fixture, restarted, "120.12", 1);

    assertThat(fixture.marketStatus()).isEqualTo("HALTED");
    assertThat(fixture.marketCircuitBreakerLevel()).isEqualTo("2");
    assertThat(fixture.highAlertCount()).isEqualTo(1);
  }

  private static void trade(
      ExchangeServiceFixture fixture, PersistentOrderService service,
      String price, long quantity) throws Exception {
    UUID seller = fixture.accountWithItems(quantity);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    service.place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal(price), null, quantity));
    service.place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal(price), null, quantity));
  }

  @Test
  void recoveryCanPublishRebuiltRuntimeRiskState() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    RiskLimits limits = RiskLimits.defaults();
    ReferencePriceTracker rebuiltPrices = ReferencePriceTracker.restored(
        new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);
    CircuitBreaker rebuiltBreaker = CircuitBreaker.restored(
        limits, MarketStatus.OPEN, new BigDecimal("100.00"),
        new BigDecimal("110.00"), null);
    fixture.service().publishRecoveredState(
        new OrderBook(), rebuiltPrices, rebuiltBreaker, fixture.marketVersion());
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");

    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.highAlertCount()).isEqualTo(1);
  }

  @Test
  void hotRiskUpdateAppliesNewCageAndLimitsWithoutDisturbingTheBook() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    RiskLimits tightCage = new RiskLimits(new BigDecimal("0.01"), new BigDecimal("0.05"),
        new BigDecimal("0.20"), new BigDecimal("0.10"), Duration.ofMinutes(2),
        new BigDecimal("0.20"), Duration.ofMinutes(10));
    fixture.service().updateRiskLimits(tightCage, new AccountOrderLimits(
        1, new BigDecimal("10.00"), 1, 5, 60));

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("120.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PRICE_OUTSIDE_CAGE");
    assertThat(fixture.orderCount()).isEqualTo(2);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for transaction gate");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
