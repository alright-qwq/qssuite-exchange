package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchResult;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.matching.Reservation;
import com.ghostchu.quickshop.addon.exchange.core.matching.ReservationCalculator;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountRiskSnapshot;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.OrderRateLimiter;
import com.ghostchu.quickshop.addon.exchange.core.risk.OrderRiskService;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.TradePermission;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.repository.MarketFeeSchedule;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class PersistentOrderService {
  public static final UUID FEE_ACCOUNT_ID =
      UUID.nameUUIDFromBytes("quickshop-exchange-fees".getBytes(StandardCharsets.UTF_8));

  private static final String PLACE_OPERATION = "PLACE";
  private static final String FORCE_CANCEL_OPERATION = "FORCE_CANCEL";
  private static final long REFERENCE_DISCOVERY_QUANTITY = 100;
  private static final Duration REFERENCE_WINDOW = Duration.ofMinutes(5);
  private static final Map<MarketCoordinationKey, MarketRuntimeState> MARKET_RUNTIMES =
      new ConcurrentHashMap<>();
  private final ExchangeRepository repository;
  private final MarketRules rules;
  private final java.util.concurrent.atomic.AtomicReference<RiskLimits> riskLimits;
  private final java.util.concurrent.atomic.AtomicReference<AccountOrderLimits> accountLimits;
  private final java.util.concurrent.atomic.AtomicReference<OrderRiskService> orderRisks;
  private final java.util.concurrent.atomic.AtomicReference<FeeCalculator> fees;
  private final java.util.concurrent.atomic.AtomicReference<ReservationCalculator> reservations;
  private final AssetCustody custody;
  private final TimeOrderedIdGenerator ids;
  private final Supplier<Instant> now;
  private final RecoveryHandler recovery;
  private final SettlementObserver observer;
  private final MarketDataService marketData;
  private volatile OrderBookRecoveryService marketRecovery;
  private final MarketCoordinationKey coordinationKey;
  private final MarketRuntimeState runtimeState;

  public MarketRules marketRules() {
    return rules;
  }

  public AccountOrderLimits accountOrderLimits() {
    return accountLimits.get();
  }

  /** Atomically refreshes market-risk parameters without disturbing the live order book. */
  public void updateRiskLimits(RiskLimits limits, AccountOrderLimits accountLimits) {
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(accountLimits, "accountLimits");
    riskLimits.set(limits);
    this.accountLimits.set(accountLimits);
    orderRisks.set(new OrderRiskService(new OrderRateLimiter(
        accountLimits.operationsPerSecond(), accountLimits.operationsPerMinute())));
    this.marketRecovery = new OrderBookRecoveryService(repository, rules, limits);
    synchronized (runtimeState) {
      runtimeState.circuitBreaker = CircuitBreaker.restored(
          limits, runtimeState.circuitBreaker.level(), runtimeState.circuitBreaker.haltedUntil());
    }
  }

  /** Production wiring should prefer the constructor that supplies a recovery handler. */
  public PersistentOrderService(ExchangeRepository repository, MarketRules rules) {
    this(repository, rules, RiskLimits.defaults(), RecoveryHandler.NO_OP,
        SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults(), null, ItemAssetCustody.INSTANCE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults(), null, ItemAssetCustody.INSTANCE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                SettlementObserver observer) {
    this(repository, rules, riskLimits, recovery, observer,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults(), null, ItemAssetCustody.INSTANCE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults(), marketData, ItemAssetCustody.INSTANCE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                AccountOrderLimits accountLimits, MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, accountLimits, marketData,
        ItemAssetCustody.INSTANCE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                AccountOrderLimits accountLimits, MarketDataService marketData,
                                AssetCustody custody) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        accountLimits, marketData, custody);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         AccountOrderLimits accountLimits) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        accountLimits);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE, ids, now,
        AccountOrderLimits.defaults());
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now) {
    this(repository, rules, riskLimits, recovery, observer, ids, now,
        AccountOrderLimits.defaults());
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits) {
    this(repository, rules, riskLimits, recovery, observer, ids, now, accountLimits, null, ItemAssetCustody.INSTANCE);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits, MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, observer, ids, now, accountLimits, marketData,
        ItemAssetCustody.INSTANCE);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits, MarketDataService marketData,
                         AssetCustody custody) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.riskLimits = new java.util.concurrent.atomic.AtomicReference<>(
        Objects.requireNonNull(riskLimits, "riskLimits"));
    this.accountLimits = new java.util.concurrent.atomic.AtomicReference<>(
        Objects.requireNonNull(accountLimits, "accountLimits"));
    this.orderRisks = new java.util.concurrent.atomic.AtomicReference<>(
        new OrderRiskService(new OrderRateLimiter(
            accountLimits.operationsPerSecond(), accountLimits.operationsPerMinute())));
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.marketData = marketData;
    this.ids = Objects.requireNonNull(ids, "ids");
    this.now = Objects.requireNonNull(now, "now");
    this.fees = new java.util.concurrent.atomic.AtomicReference<>(
        new FeeCalculator(rules.priceScale()));
    this.reservations = new java.util.concurrent.atomic.AtomicReference<>(
        new ReservationCalculator(this.fees.get()));
    this.custody = Objects.requireNonNull(custody, "custody");
    this.marketRecovery = new OrderBookRecoveryService(repository, rules, this.riskLimits.get());
    this.coordinationKey = new MarketCoordinationKey(
        Objects.requireNonNull(repository.coordinationKey(), "repository coordination key"),
        rules.marketId());
    this.runtimeState = MARKET_RUNTIMES.computeIfAbsent(this.coordinationKey, ignored ->
        new MarketRuntimeState(
            new OrderBook(),
            new ReferencePriceTracker(rules.basePrice(), REFERENCE_DISCOVERY_QUANTITY,
                REFERENCE_WINDOW, rules.priceScale()),
            new CircuitBreaker(this.riskLimits.get()),
            Long.MIN_VALUE));
  }

  /** Removes this market's shared runtime state after the owning runtime is fully closed. */
  public void closeRuntimeState() {
    MARKET_RUNTIMES.remove(coordinationKey);
  }

  public OrderReceipt place(OrderRequest request) throws SQLException {
    validate(request);
    OrderReceipt stored = preflightRisk(request);
    if (stored != null) {
      return stored;
    }
    synchronized (runtimeState) {
      AtomicReference<TransactionOutcome> attemptedOutcome = new AtomicReference<>();
      try {
        TransactionOutcome outcome = repository.inTransaction(tx -> {
          TransactionOutcome settled = settle(tx, request);
          attemptedOutcome.set(settled);
          return settled;
        });
        publish(outcome);
        return outcome.receipt();
      } catch (SettlementObservationFailure failure) {
        RuntimeException injected = failure.original();
        for (Throwable suppressed : failure.getSuppressed()) {
          injected.addSuppressed(suppressed);
        }
        enterRecovery(request.marketId(), injected);
        throw injected;
      } catch (SQLException failure) {
        OrderReceipt committed = committedReceipt(request, failure);
        if (committed != null) {
          TransactionOutcome attempted = attemptedOutcome.get();
          if (attempted != null && committed.equals(attempted.receipt())) {
            publish(attempted);
          }
          return committed;
        }
        enterRecovery(request.marketId(), failure);
        throw failure;
      }
    }
  }

  /** Cancels an active order under the same market serialization as matching. */
  public OrderReceipt forceCancel(UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    OrderReceipt replay = committedForceCancelReceipt(actorId, requestId, orderId);
    if (replay != null) {
      return replay;
    }
    String normalizedReason = normalizeAdminReason(reason);
    synchronized (runtimeState) {
      AtomicReference<ForceCancelOutcome> attempted = new AtomicReference<>();
      try {
        ForceCancelOutcome outcome = repository.inTransaction(tx -> {
          ForceCancelOutcome cancelled = cancelOpenOrder(
              tx, actorId, requestId, orderId, normalizedReason);
          attempted.set(cancelled);
          return cancelled;
        });
        if (!outcome.duplicate()) {
          runtimeState.committedBook = outcome.book();
        }
        return outcome.receipt();
      } catch (SQLException failure) {
        OrderReceipt committed = committedForceCancelReceipt(actorId, requestId, orderId, failure);
        if (committed != null) {
          ForceCancelOutcome attemptedOutcome = attempted.get();
          if (attemptedOutcome != null && committed.equals(attemptedOutcome.receipt())) {
            runtimeState.committedBook = attemptedOutcome.book();
          } else {
            recoverFromDatabase();
          }
          return committed;
        }
        throw failure;
      }
    }
  }

  /** Cancels an order only when it belongs to the requesting account. */
  public OrderReceipt cancel(UUID accountId, UUID requestId, UUID orderId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    OrderReceipt replay = committedForceCancelReceipt(accountId, requestId, orderId);
    if (replay != null) {
      return replay;
    }
    synchronized (runtimeState) {
      List<PersistedOrder> open = repository.inTransaction(tx -> tx.openOrders(rules.marketId()));
      PersistedOrder order = open.stream().filter(candidate ->
          candidate.order().orderId().equals(orderId)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("order is not open: " + orderId));
      if (!order.order().accountId().equals(accountId)) {
        throw new IllegalArgumentException("order is not owned by account");
      }
      return forceCancel(accountId, requestId, orderId, "player cancellation");
    }
  }

  private void publish(TransactionOutcome outcome) {
    if (outcome.duplicate()) {
      return;
    }
    runtimeState.committedBook = outcome.book();
    runtimeState.referencePrices = outcome.referencePrices();
    runtimeState.circuitBreaker = outcome.circuitBreaker();
    runtimeState.committedMarketVersion = outcome.marketVersion();
    if (marketData != null) {
      for (Trade trade : outcome.plan().trades()) {
        try {
          marketData.recordTrade(trade.marketId(), trade.price(), trade.quantity(),
              trade.executedAt());
        } catch (RuntimeException ignored) {
          // Market data must never turn an already committed order into a failed request.
        }
      }
    }
  }

  private OrderReceipt committedReceipt(OrderRequest request, SQLException originalFailure) {
    try {
      StoredRequestResult stored = repository.inTransaction(tx ->
          tx.requestResult(request.accountId(), request.requestId()).orElse(null));
      if (stored == null) {
        return null;
      }
      if (!PLACE_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return decodeReceipt(stored.payload());
    } catch (SQLException lookupFailure) {
      originalFailure.addSuppressed(lookupFailure);
      return null;
    }
  }

  private OrderReceipt committedForceCancelReceipt(
      UUID actorId, UUID requestId, UUID expectedOrderId) throws SQLException {
    StoredRequestResult stored = repository.findRequestResult(actorId, requestId).orElse(null);
    if (stored == null) {
      return null;
    }
    if (!FORCE_CANCEL_OPERATION.equals(stored.operation())) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    OrderReceipt receipt = decodeReceipt(stored.payload());
    if (!expectedOrderId.equals(receipt.orderId())) {
      throw new IllegalStateException("request id belongs to another cancellation target");
    }
    return receipt;
  }

  private OrderReceipt committedForceCancelReceipt(
      UUID actorId, UUID requestId, UUID expectedOrderId, SQLException originalFailure) {
    try {
      return committedForceCancelReceipt(actorId, requestId, expectedOrderId);
    } catch (SQLException lookupFailure) {
      originalFailure.addSuppressed(lookupFailure);
      return null;
    }
  }

  private OrderReceipt storedReceipt(OrderRequest request) throws SQLException {
    StoredRequestResult stored = repository.findRequestResult(request.accountId(), request.requestId())
        .orElse(null);
    if (stored == null) {
      return null;
    }
    if (!PLACE_OPERATION.equals(stored.operation())) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    return decodeReceipt(stored.payload());
  }

  private TransactionOutcome settle(ExchangeTransaction tx, OrderRequest request)
      throws SQLException {
    MarketState lockedState = tx.marketState(request.marketId());
    StoredRequestResult stored = tx.requestResult(request.accountId(), request.requestId())
        .orElse(null);
    if (stored != null) {
      if (!PLACE_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return TransactionOutcome.duplicate(decodeReceipt(stored.payload()));
    }

    if (lockedState.status() != MarketStatus.OPEN) {
      reject(OrderRiskService.RejectReason.MARKET_NOT_OPEN);
    }
    Instant evaluatedAt = now.get();
    RuntimeRiskSnapshot runtimeRisk = runtimeRisk(tx, lockedState, evaluatedAt);
    MarketState beforeState = runtimeRisk.state();
    if (parseType(request.type()) == OrderType.MARKET) {
      OrderRiskService.RejectReason rejection = orderRisks.get().checkMarketSlippage(
          request.slippageBoundary(), beforeState.referencePrice(),
          riskLimits.get().maximumSlippage());
      if (rejection != null) {
        throw new IllegalStateException(rejection.name());
      }
    }
    List<PersistedOrder> persistedOrders = tx.openOrders(request.marketId());
    long structuralVersion = tx.marketStructuralVersion(request.marketId());
    MarketFeeSchedule feeSchedule = tx.marketFeeSchedule(request.marketId());
    if (feeSchedule.currencyScale() != rules.priceScale()) {
      throw new IllegalStateException("fee schedule currency scale does not match market rules");
    }

    Instant createdAt = now.get();
    long prioritySequence = Math.addExact(beforeState.prioritySequence(), 1);
    Order incoming = createOrder(
        request, prioritySequence, structuralVersion, feeSchedule.activeVersion(), createdAt);
    MarketRules incomingRules = rulesWithFees(feeSchedule.activeRates());
    OrderBook transactionBook = new OrderBook();
    Map<UUID, PersistedOrder> persistedById = new HashMap<>();
    for (PersistedOrder persisted : persistedOrders) {
      transactionBook.add(persisted.order());
      persistedById.put(persisted.order().orderId(), persisted);
    }

    if (incoming.type() == OrderType.LIMIT
        && !riskLimits.get().insideCage(incoming.limitPrice(), beforeState.referencePrice())) {
      reject(OrderRiskService.RejectReason.PRICE_OUTSIDE_CAGE);
    }
    if (wouldSelfTrade(request, transactionBook, beforeState.referencePrice())) {
      reject(OrderRiskService.RejectReason.SELF_TRADE);
    }

    Reservation reservation = incoming.type() == OrderType.MARKET
        ? reservations.get().reserve(incoming, incomingRules, transactionBook,
            price -> riskLimits.get().insideCage(price, beforeState.referencePrice()))
        : reservations.get().reserve(incoming, incomingRules);

    long holding = custody.holding(tx, request.accountId(), rules.marketId());
    BigDecimal frozenCurrency = tx.existingCurrency(request.accountId(), rules.currencyId())
        .map(balance -> balance.frozen()).orElse(BigDecimal.ZERO);
    int openOrders = (int) persistedOrders.stream()
        .filter(persisted -> persisted.order().accountId().equals(request.accountId()))
        .count();
    AccountRiskSnapshot accountRisk = new AccountRiskSnapshot(
        holding, frozenCurrency, openOrders);
    OrderRiskService.RejectReason exposureRejection = orderRisks.get().checkExposure(
        incoming.side() == OrderSide.BUY ? incoming.originalQuantity() : 0,
        incoming.side() == OrderSide.BUY ? reservation.frozenCurrency() : BigDecimal.ZERO,
        accountRisk, accountLimits.get(), incoming.type() == OrderType.LIMIT);
    if (exposureRejection != null) {
      throw new IllegalStateException(exposureRejection.name());
    }

    AtomicLong matchSequence = new AtomicLong(beforeState.matchSequence());
    ReferencePriceTracker transactionPrices = runtimeRisk.referencePrices();
    CircuitBreaker transactionBreaker = runtimeRisk.circuitBreaker();
    MatchingEngine engine = new MatchingEngine(transactionBook, rules, fees.get(),
        matchSequence::incrementAndGet, now, ids,
        price -> riskLimits.get().insideCage(price, beforeState.referencePrice()),
        order -> feeSchedule.rates(order.feeVersion()));
    MatchResult match = engine.submit(incoming);
    Order taker = match.selfTradeRejected()
        ? incoming.withStatus(OrderStatus.REJECTED, now.get()) : match.finalOrder();
    lockAssets(tx, incoming, match);
    freeze(tx, incoming, reservation);
    reached(SettlementStage.AFTER_RESERVATION);

    Map<UUID, BigDecimal> currencyReservations = new HashMap<>();
    Map<UUID, Long> itemReservations = new HashMap<>();
    for (PersistedOrder persisted : persistedOrders) {
      currencyReservations.put(persisted.order().orderId(), persisted.reservedCurrency());
      itemReservations.put(persisted.order().orderId(), persisted.reservedQuantity());
    }
    currencyReservations.put(incoming.orderId(), reservation.frozenCurrency());
    itemReservations.put(incoming.orderId(), reservation.frozenQuantity());

    for (Trade trade : match.trades()) {
      settleTrade(tx, incoming, trade, currencyReservations, itemReservations);
    }

    for (Order maker : match.changedMakers()) {
      releaseOpenBuyExcess(tx, maker, currencyReservations, feeSchedule);
      releaseTerminalReservation(tx, maker, currencyReservations, itemReservations);
    }
    long reservedTakerItemsBeforeRelease = itemReservations.get(taker.orderId());
    BigDecimal takerCurrencyRelease = releaseOpenBuyExcess(
        tx, taker, currencyReservations, feeSchedule).add(releaseTerminalReservation(
            tx, taker, currencyReservations, itemReservations));
    long takerItemRelease = taker.side() == OrderSide.SELL && isTerminal(taker)
        ? reservedTakerItemsBeforeRelease : 0;
    reached(SettlementStage.AFTER_BALANCE_UPDATE);

    for (Order maker : match.changedMakers()) {
      PersistedOrder persisted = persistedById.get(maker.orderId());
      tx.updateOrder(maker, currencyReservations.get(maker.orderId()),
          itemReservations.get(maker.orderId()), persisted.version());
    }
    reached(SettlementStage.AFTER_MAKER_UPDATE);
    tx.insertOrder(taker, currencyReservations.get(taker.orderId()),
        itemReservations.get(taker.orderId()));
    reached(SettlementStage.AFTER_ORDER_INSERT);

    for (Trade trade : match.trades()) {
      tx.insertTrade(trade);
    }
    reached(SettlementStage.AFTER_TRADE_INSERT);
    for (Trade trade : match.trades()) {
      appendTradeJournals(tx, incoming, trade);
    }
    reached(SettlementStage.AFTER_LEDGER_INSERT);

    MarketState afterState = updateRiskState(
        tx, beforeState, prioritySequence, matchSequence.get(), match.trades(),
        transactionPrices, transactionBreaker);
    tx.updateMarketState(afterState, beforeState.version());

    SettlementPlan plan = new SettlementPlan(taker, match.changedMakers(), match.trades(),
        takerCurrencyRelease, takerItemRelease);
    OrderReceipt receipt = new OrderReceipt(
        request.requestId(), taker.orderId(), taker.status().name(), plan.trades());
    tx.putRequestResult(new StoredRequestResult(
        request.accountId(), request.requestId(), PLACE_OPERATION, encodeReceipt(receipt)));
    reached(SettlementStage.AFTER_REQUEST_RESULT);
    return TransactionOutcome.committed(
        receipt, plan, transactionBook, transactionPrices, transactionBreaker,
        afterState.version());
  }

  private ForceCancelOutcome cancelOpenOrder(
      ExchangeTransaction tx, UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
    if (stored != null) {
      if (!FORCE_CANCEL_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      OrderReceipt receipt = decodeReceipt(stored.payload());
      if (!orderId.equals(receipt.orderId())) {
        throw new IllegalStateException("request id belongs to another cancellation target");
      }
      return ForceCancelOutcome.duplicate(receipt);
    }
    List<PersistedOrder> persistedOrders = tx.openOrders(rules.marketId());
    PersistedOrder persisted = persistedOrders.stream()
        .filter(candidate -> candidate.order().orderId().equals(orderId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("order is not open: " + orderId));
    Order before = persisted.order();
    Order cancelled = before.withStatus(OrderStatus.CANCELLED, now.get());
    if (before.side() == OrderSide.BUY && persisted.reservedCurrency().signum() > 0) {
      tx.releaseCurrency(before.accountId(), rules.currencyId(), persisted.reservedCurrency());
    } else if (before.side() == OrderSide.SELL && persisted.reservedQuantity() > 0) {
      custody.release(tx, before.accountId(), rules.marketId(), persisted.reservedQuantity());
    }
    tx.updateOrder(cancelled, BigDecimal.ZERO, 0L, persisted.version());
    tx.appendAudit(new AuditRecord(ids.get(), actorId, "FORCE_CANCEL_ORDER", orderId.toString(),
        reason, orderState(before, persisted), orderState(cancelled,
            BigDecimal.ZERO, 0L), now.get()));
    OrderReceipt receipt = new OrderReceipt(requestId, orderId, cancelled.status().name(), List.of());
    tx.putRequestResult(new StoredRequestResult(
        actorId, requestId, FORCE_CANCEL_OPERATION, encodeReceipt(receipt)));
    OrderBook book = new OrderBook();
    for (PersistedOrder active : persistedOrders) {
      if (!active.order().orderId().equals(orderId)) {
        book.add(active.order());
      }
    }
    return ForceCancelOutcome.committed(receipt, book);
  }

  private static String normalizeAdminReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException("administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }

  private static String orderState(Order order, PersistedOrder persisted) {
    return orderState(order, persisted.reservedCurrency(), persisted.reservedQuantity());
  }

  private static String orderState(Order order, BigDecimal reservedCurrency, long reservedQuantity) {
    return "status=" + order.status() + ",remainingQuantity=" + order.remainingQuantity()
        + ",reservedCurrency=" + reservedCurrency.toPlainString()
        + ",reservedQuantity=" + reservedQuantity;
  }

  public void publishRecoveredState(
      OrderBook rebuiltBook, ReferencePriceTracker rebuiltReferencePrices,
      CircuitBreaker rebuiltCircuitBreaker, long marketVersion) {
    Objects.requireNonNull(rebuiltBook, "rebuiltBook");
    Objects.requireNonNull(rebuiltReferencePrices, "rebuiltReferencePrices");
    Objects.requireNonNull(rebuiltCircuitBreaker, "rebuiltCircuitBreaker");
    synchronized (runtimeState) {
      runtimeState.committedBook = rebuiltBook;
      runtimeState.referencePrices = rebuiltReferencePrices.copy();
      runtimeState.circuitBreaker = rebuiltCircuitBreaker.copy();
      runtimeState.committedMarketVersion = marketVersion;
    }
  }

  public void recoverFromDatabase() throws SQLException {
    synchronized (runtimeState) {
      RecoveredMarket recovered = marketRecovery.recover(rules.marketId(), now.get());
      runtimeState.committedBook = recovered.book();
      runtimeState.referencePrices = recovered.referencePrices().copy();
      runtimeState.circuitBreaker = recovered.circuitBreaker().copy();
      runtimeState.committedMarketVersion = recovered.marketVersion();
    }
  }

  /** Rebuilds the order book only when the market is still in RECOVERING. */
  public boolean recoverIfRecovering() throws SQLException {
    MarketStatus status = repository.inTransaction(
        tx -> tx.marketState(rules.marketId()).status());
    if (status != MarketStatus.RECOVERING) {
      return false;
    }
    recoverFromDatabase();
    return true;
  }

  /** Builds a protected quote from the most recently committed book and reference-price state. */
  public MarketQuote marketQuote(MarketDataService data) throws SQLException {
    Objects.requireNonNull(data, "data");
    MarketBookSnapshot snapshot = marketBookSnapshot(data, 1);
    return data.quote(rules.marketId(), snapshot.referencePrice(), snapshot.bestBid(),
        snapshot.bestAsk(), snapshot.status(), snapshot.asOf());
  }

  /** Returns the best visible bid and ask levels without exposing the mutable order book. */
  public MarketDepth marketDepth(MarketDataService data, int limit) throws SQLException {
    MarketBookSnapshot snapshot = marketBookSnapshot(data, limit);
    return new MarketDepth(snapshot.bids(), snapshot.asks());
  }

  /** Captures one coherent, read-only view of the committed book for a market UI refresh. */
  public MarketBookSnapshot marketBookSnapshot(MarketDataService data, int limit) throws SQLException {
    Objects.requireNonNull(data, "data");
    if (limit < 1) {
      throw new IllegalArgumentException("depth limit must be positive");
    }
    MarketStatus status = repository.inTransaction(
        transaction -> transaction.marketState(rules.marketId()).status());
    synchronized (runtimeState) {
      Instant asOf = now.get();
      BigDecimal reference = runtimeState.referencePrices.copy().referenceAt(asOf);
      BigDecimal bestBid = runtimeState.committedBook.bestExecutable(OrderSide.BUY,
          price -> riskLimits.get().insideCage(price, reference)).map(Order::limitPrice).orElse(null);
      BigDecimal bestAsk = runtimeState.committedBook.bestExecutable(OrderSide.SELL,
          price -> riskLimits.get().insideCage(price, reference)).map(Order::limitPrice).orElse(null);
      List<MarketDataService.DepthLevel> bids = data.depth(runtimeState.committedBook,
              OrderSide.BUY, reference, riskLimits.get()).stream()
          .sorted(Comparator.comparing(MarketDataService.DepthLevel::price).reversed())
          .limit(limit).toList();
      List<MarketDataService.DepthLevel> asks = data.depth(runtimeState.committedBook,
              OrderSide.SELL, reference, riskLimits.get()).stream()
          .sorted(Comparator.comparing(MarketDataService.DepthLevel::price))
          .limit(limit).toList();
      return new MarketBookSnapshot(status, asOf, reference, bestBid, bestAsk, bids, asks);
    }
  }

  public record MarketDepth(List<MarketDataService.DepthLevel> bids,
                            List<MarketDataService.DepthLevel> asks) {
    public MarketDepth {
      bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
      asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
    }
  }

  public record MarketBookSnapshot(MarketStatus status, Instant asOf, BigDecimal referencePrice,
                                   BigDecimal bestBid, BigDecimal bestAsk,
                                   List<MarketDataService.DepthLevel> bids,
                                   List<MarketDataService.DepthLevel> asks) {
    public MarketBookSnapshot {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(asOf, "asOf");
      if (referencePrice == null || referencePrice.signum() <= 0) {
        throw new IllegalArgumentException("reference price must be positive");
      }
      bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
      asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
    }
  }

  private RuntimeRiskSnapshot runtimeRisk(
      ExchangeTransaction tx, MarketState state, Instant recoveredAt) throws SQLException {
    if (runtimeState.committedMarketVersion == state.version()) {
      return new RuntimeRiskSnapshot(
          state, runtimeState.referencePrices.copy(), runtimeState.circuitBreaker.copy());
    }
    try {
      RecoveredMarket recovered = marketRecovery.recover(tx, state, recoveredAt);
      return new RuntimeRiskSnapshot(
          recovered.state(), recovered.referencePrices(), recovered.circuitBreaker());
    } catch (RuntimeException failure) {
      throw new SQLException("market runtime recovery failed", failure);
    }
  }

  private void validate(OrderRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.requestId() == null || request.accountId() == null
        || request.marketId() == null || request.marketId().isBlank() || request.side() == null
        || request.type() == null) {
      throw new IllegalArgumentException("order request identity is required");
    }
    if (!rules.marketId().equals(request.marketId())) {
      throw new IllegalArgumentException("order market does not match service");
    }
    rules.validateQuantity(request.quantity());
    custody.validateQuantity(request.quantity());
    OrderType type = parseType(request.type());
    if (type == OrderType.LIMIT) {
      rules.validatePrice(request.price());
      if (request.slippageBoundary() != null) {
        throw new IllegalArgumentException("limit order cannot have a slippage boundary");
      }
    } else {
      rules.validatePrice(request.slippageBoundary());
      if (request.price() != null) {
        throw new IllegalArgumentException("market order cannot have a limit price");
      }
    }
  }

  private OrderReceipt preflightRisk(OrderRequest request) throws SQLException {
    OrderRiskService.RejectReason rateLimitRejection =
        orderRisks.get().checkRateLimit(request.accountId(), now.get());
    if (rateLimitRejection != null) {
      return storedOrReject(request, rateLimitRejection);
    }
    synchronized (runtimeState) {
      if (runtimeState.committedMarketVersion == Long.MIN_VALUE) {
        return null;
      }
      BigDecimal reference = runtimeState.referencePrices.copy().referenceAt(now.get());
      if (parseType(request.type()) == OrderType.LIMIT
          && !riskLimits.get().insideCage(request.price(), reference)) {
        return storedOrReject(request, OrderRiskService.RejectReason.PRICE_OUTSIDE_CAGE);
      }
      if (wouldSelfTrade(request, runtimeState.committedBook, reference)) {
        return storedOrReject(request, OrderRiskService.RejectReason.SELF_TRADE);
      }
    }
    return null;
  }

  private OrderReceipt storedOrReject(OrderRequest request, OrderRiskService.RejectReason reason)
      throws SQLException {
    OrderReceipt stored = storedReceipt(request);
    if (stored != null) {
      return stored;
    }
    reject(reason);
    throw new AssertionError("reject must throw");
  }

  private boolean wouldSelfTrade(OrderRequest request, OrderBook book, BigDecimal referencePrice) {
    OrderType type = parseType(request.type());
    BigDecimal boundary = type == OrderType.LIMIT ? request.price() : request.slippageBoundary();
    OrderSide opposite = request.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    for (Order maker : book.executableOrders(opposite,
        price -> riskLimits.get().insideCage(price, referencePrice))) {
      boolean crosses = request.side() == OrderSide.BUY
          ? maker.limitPrice().compareTo(boundary) <= 0
          : maker.limitPrice().compareTo(boundary) >= 0;
      if (!crosses) {
        break;
      }
      if (maker.accountId().equals(request.accountId())) {
        return true;
      }
    }
    return false;
  }

  private static void reject(OrderRiskService.RejectReason reason) {
    throw new IllegalStateException(reason.name());
  }

  private Order createOrder(
      OrderRequest request, long prioritySequence, long structuralVersion,
      long feeVersion, Instant createdAt) {
    OrderType type = parseType(request.type());
    return new Order(ids.get(), request.requestId(), request.marketId(), request.accountId(),
        request.side(), type, type == OrderType.LIMIT ? TimeInForce.GTC : TimeInForce.IOC,
        request.price(), request.slippageBoundary(), request.quantity(), request.quantity(),
        OrderStatus.OPEN, prioritySequence, structuralVersion, feeVersion, createdAt, createdAt);
  }

  private static OrderType parseType(String type) {
    try {
      return OrderType.valueOf(type);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("unsupported order type: " + type, failure);
    }
  }

  private void lockAssets(ExchangeTransaction tx, Order incoming, MatchResult match)
      throws SQLException {
    Set<LockKey> involved = new LinkedHashSet<>();
    involved.add(incoming.side() == OrderSide.BUY
        ? new LockKey(incoming.accountId(), rules.currencyId(), true)
        : new LockKey(incoming.accountId(), rules.marketId(), false));
    for (Trade trade : match.trades()) {
      involved.add(new LockKey(trade.buyerAccountId(), rules.currencyId(), true));
      involved.add(new LockKey(trade.buyerAccountId(), rules.marketId(), false));
      involved.add(new LockKey(trade.sellerAccountId(), rules.currencyId(), true));
      involved.add(new LockKey(trade.sellerAccountId(), rules.marketId(), false));
      if (trade.makerFee().add(trade.takerFee()).signum() > 0) {
        involved.add(new LockKey(FEE_ACCOUNT_ID, rules.currencyId(), true));
      }
    }
    ArrayList<LockKey> keys = new ArrayList<>(involved);
    keys.sort(Comparator.comparing((LockKey key) -> key.accountId().toString())
        .thenComparing(LockKey::assetId)
        .thenComparing(LockKey::currency));
    for (LockKey key : keys) {
      if (key.currency()) {
        tx.currency(key.accountId(), key.assetId());
      } else {
        custody.lock(tx, key.accountId(), key.assetId());
      }
    }
  }

  private void freeze(ExchangeTransaction tx, Order order, Reservation reservation)
      throws SQLException {
    if (order.side() == OrderSide.BUY && reservation.frozenCurrency().signum() > 0) {
      tx.freezeCurrency(order.accountId(), rules.currencyId(), reservation.frozenCurrency());
    } else if (order.side() == OrderSide.SELL && reservation.frozenQuantity() > 0) {
      custody.freeze(tx, order.accountId(), rules.marketId(), reservation.frozenQuantity());
    }
  }

  private void settleTrade(ExchangeTransaction tx, Order incoming, Trade trade,
                           Map<UUID, BigDecimal> currencyReservations,
                           Map<UUID, Long> itemReservations) throws SQLException {
    boolean takerBuys = incoming.side() == OrderSide.BUY;
    BigDecimal buyerFee = takerBuys ? trade.takerFee() : trade.makerFee();
    BigDecimal sellerFee = takerBuys ? trade.makerFee() : trade.takerFee();
    BigDecimal notional = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));
    BigDecimal buyerConsumption = notional.add(buyerFee);
    UUID buyerOrder = takerBuys ? incoming.orderId() : trade.makerOrderId();
    UUID sellerOrder = takerBuys ? trade.makerOrderId() : incoming.orderId();

    tx.consumeFrozenCurrency(trade.buyerAccountId(), rules.currencyId(), buyerConsumption);
    custody.creditAvailable(tx, trade.buyerAccountId(), rules.marketId(), trade.quantity());
    custody.consumeFrozen(tx, trade.sellerAccountId(), rules.marketId(), trade.quantity());
    BigDecimal sellerCredit = notional.subtract(sellerFee);
    if (sellerCredit.signum() > 0) {
      tx.creditAvailableCurrency(trade.sellerAccountId(), rules.currencyId(), sellerCredit);
    }
    BigDecimal feeCredit = buyerFee.add(sellerFee);
    if (feeCredit.signum() > 0) {
      tx.creditAvailableCurrency(FEE_ACCOUNT_ID, rules.currencyId(), feeCredit);
    }

    currencyReservations.compute(buyerOrder,
        (ignored, reserved) -> reserved.subtract(buyerConsumption));
    itemReservations.compute(sellerOrder,
        (ignored, reserved) -> Math.subtractExact(reserved, trade.quantity()));
  }

  private BigDecimal releaseTerminalReservation(
      ExchangeTransaction tx, Order order, Map<UUID, BigDecimal> currencyReservations,
      Map<UUID, Long> itemReservations) throws SQLException {
    if (!isTerminal(order)) {
      return BigDecimal.ZERO;
    }
    if (order.side() == OrderSide.BUY) {
      BigDecimal release = currencyReservations.get(order.orderId());
      if (release.signum() > 0) {
        tx.releaseCurrency(order.accountId(), rules.currencyId(), release);
        currencyReservations.put(order.orderId(), BigDecimal.ZERO);
      }
      return release;
    }
    long release = itemReservations.get(order.orderId());
    if (release > 0) {
      custody.release(tx, order.accountId(), rules.marketId(), release);
      itemReservations.put(order.orderId(), 0L);
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal releaseOpenBuyExcess(
      ExchangeTransaction tx, Order order, Map<UUID, BigDecimal> currencyReservations,
      MarketFeeSchedule feeSchedule)
      throws SQLException {
    if (order.side() != OrderSide.BUY || order.type() != OrderType.LIMIT || isTerminal(order)) {
      return BigDecimal.ZERO;
    }
    BigDecimal reserved = currencyReservations.get(order.orderId());
    BigDecimal required = reservations.get().reserve(order, rulesWithFees(
        feeSchedule.rates(order.feeVersion()))).frozenCurrency();
    BigDecimal release = reserved.subtract(required);
    if (release.signum() < 0) {
      throw new IllegalStateException("remaining buy reservation is underfunded");
    }
    if (release.signum() > 0) {
      tx.releaseCurrency(order.accountId(), rules.currencyId(), release);
      currencyReservations.put(order.orderId(), required);
    }
    return release;
  }

  private MarketRules rulesWithFees(FeeRates rates) {
    return new MarketRules(rules.marketId(), rules.currencyId(), rules.basePrice(),
        rules.minPrice(), rules.maxPrice(), rules.tickSize(), rules.minQuantity(),
        rules.maxQuantity(), rules.priceScale(), rates.makerRate(), rates.takerRate());
  }

  private static boolean isTerminal(Order order) {
    return order.status() == OrderStatus.FILLED || order.status() == OrderStatus.CANCELLED
        || order.status() == OrderStatus.REJECTED;
  }

  private void appendTradeJournals(ExchangeTransaction tx, Order incoming, Trade trade)
      throws SQLException {
    boolean makerBuys = incoming.side() == OrderSide.SELL;
    BigDecimal buyerFee = makerBuys ? trade.makerFee() : trade.takerFee();
    BigDecimal sellerFee = makerBuys ? trade.takerFee() : trade.makerFee();
    BigDecimal notional = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));
    BigDecimal buyerDebit = notional.add(buyerFee).negate();
    BigDecimal sellerCredit = notional.subtract(sellerFee);
    BigDecimal feeCredit = buyerFee.add(sellerFee);
    Instant at = trade.executedAt();
    tx.appendJournal(new LedgerJournal(ids.get(), "TRADE_CURRENCY", trade.tradeId(), at, null,
        List.of(
            entry("liability:currency:" + trade.buyerAccountId(), rules.currencyId(), buyerDebit, at),
            entry("liability:currency:" + trade.sellerAccountId(), rules.currencyId(), sellerCredit, at),
            entry("liability:fee:" + FEE_ACCOUNT_ID, rules.currencyId(), feeCredit, at),
            entry("custody:currency:" + rules.currencyId(), rules.currencyId(), BigDecimal.ZERO, at))));
    BigDecimal quantity = BigDecimal.valueOf(trade.quantity());
    tx.appendJournal(new LedgerJournal(ids.get(), "TRADE_ITEM", trade.tradeId(), at, null,
        List.of(
            entry("liability:item:" + trade.sellerAccountId(), rules.marketId(), quantity.negate(), at),
            entry("liability:item:" + trade.buyerAccountId(), rules.marketId(), quantity, at),
            entry("custody:item:" + rules.marketId(), rules.marketId(), BigDecimal.ZERO, at))));
    if (custody.recordsLedgerEntries()) {
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), "trade:" + trade.tradeId() + ":seller",
          rules.marketId(), trade.sellerAccountId(), "TRADE",
          -trade.quantity(), -trade.quantity(), 0, "TRADE", trade.tradeId().toString(),
          null, "matched sell order", at));
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), "trade:" + trade.tradeId() + ":buyer",
          rules.marketId(), trade.buyerAccountId(), "TRADE",
          trade.quantity(), trade.quantity(), 0, "TRADE", trade.tradeId().toString(),
          null, "matched buy order", at));
    }
  }

  private LedgerEntry entry(String account, String asset, BigDecimal amount, Instant at) {
    return new LedgerEntry(ids.get(), account, asset, amount, at);
  }

  private MarketState updateRiskState(
      ExchangeTransaction tx, MarketState before, long prioritySequence, long matchSequence,
      List<Trade> trades, ReferencePriceTracker prices, CircuitBreaker breaker)
      throws SQLException {
    MarketStatus status = before.status();
    BigDecimal reference = before.referencePrice();
    BigDecimal lastPrice = before.lastPrice();
    Instant haltedUntil = before.haltedUntil();
    for (Trade trade : trades) {
      BigDecimal preTradeReference = reference;
      TradePermission permission = breaker.onPrice(trade.price(), preTradeReference, trade.executedAt());
      prices.record(trade.price(), trade.quantity(), trade.executedAt());
      lastPrice = trade.price();
      if (permission.allowed()) {
        reference = prices.referenceAt(trade.executedAt());
      } else {
        status = MarketStatus.HALTED;
        haltedUntil = permission.haltUntil().orElseThrow();
        reference = preTradeReference;
        if (permission.level() == 2) {
          tx.insertHighAlert(ids.get(), rules.marketId(), "CIRCUIT_BREAKER_LEVEL_2",
              encodeLevelTwoAlert(reference, trade.price()),
              trade.executedAt());
        }
      }
    }
    return new MarketState(before.marketId(), status, prioritySequence, matchSequence,
        reference, lastPrice, haltedUntil, prices.discoveryQuantity(), breaker.level(),
        before.version() + 1);
  }

  private void reached(SettlementStage stage) {
    try {
      observer.reached(stage);
    } catch (RuntimeException failure) {
      throw new SettlementObservationFailure(failure);
    }
  }

  private void enterRecovery(String marketId, Throwable failure) {
    try {
      repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        tx.updateMarketState(new MarketState(state.marketId(), MarketStatus.RECOVERING,
            state.prioritySequence(), state.matchSequence(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
            state.circuitBreakerLevel(), state.version() + 1), state.version());
        return null;
      });
    } catch (SQLException | RuntimeException recoveryWriteFailure) {
      failure.addSuppressed(recoveryWriteFailure);
    }
    try {
      recovery.recover(marketId, failure);
    } catch (RuntimeException recoveryFailure) {
      failure.addSuppressed(recoveryFailure);
    }
  }

  private static String encodeReceipt(OrderReceipt receipt) {
    JsonObject json = new JsonObject();
    json.addProperty("requestId", receipt.requestId().toString());
    json.addProperty("orderId", receipt.orderId().toString());
    json.addProperty("status", receipt.status());
    JsonArray trades = new JsonArray();
    for (Trade trade : receipt.trades()) {
      JsonObject encoded = new JsonObject();
      encoded.addProperty("tradeId", trade.tradeId().toString());
      encoded.addProperty("marketId", trade.marketId());
      encoded.addProperty("makerOrderId", trade.makerOrderId().toString());
      encoded.addProperty("takerOrderId", trade.takerOrderId().toString());
      encoded.addProperty("buyerAccountId", trade.buyerAccountId().toString());
      encoded.addProperty("sellerAccountId", trade.sellerAccountId().toString());
      encoded.addProperty("price", trade.price().toPlainString());
      encoded.addProperty("quantity", trade.quantity());
      encoded.addProperty("makerFee", trade.makerFee().toPlainString());
      encoded.addProperty("takerFee", trade.takerFee().toPlainString());
      encoded.addProperty("matchSequence", trade.matchSequence());
      encoded.addProperty("executedAt", trade.executedAt().toString());
      trades.add(encoded);
    }
    json.add("trades", trades);
    return json.toString();
  }

  private static OrderReceipt decodeReceipt(String payload) throws SQLException {
    try {
      JsonObject receipt = JsonParser.parseString(payload).getAsJsonObject();
      ArrayList<Trade> trades = new ArrayList<>();
      for (JsonElement element : receipt.getAsJsonArray("trades")) {
        JsonObject trade = element.getAsJsonObject();
        trades.add(new Trade(uuid(trade, "tradeId"), string(trade, "marketId"),
            uuid(trade, "makerOrderId"), uuid(trade, "takerOrderId"),
            uuid(trade, "buyerAccountId"), uuid(trade, "sellerAccountId"),
            decimal(trade, "price"), trade.get("quantity").getAsLong(),
            decimal(trade, "makerFee"), decimal(trade, "takerFee"),
            trade.get("matchSequence").getAsLong(),
            Instant.parse(string(trade, "executedAt"))));
      }
      return new OrderReceipt(uuid(receipt, "requestId"), uuid(receipt, "orderId"),
          string(receipt, "status"), trades);
    } catch (RuntimeException failure) {
      throw new SQLException("invalid stored order receipt", failure);
    }
  }

  private static String encodeLevelTwoAlert(BigDecimal reference, BigDecimal tradePrice) {
    JsonObject alert = new JsonObject();
    alert.addProperty("level", 2);
    alert.addProperty("referencePrice", reference.toPlainString());
    alert.addProperty("tradePrice", tradePrice.toPlainString());
    return alert.toString();
  }

  private static UUID uuid(JsonObject json, String field) {
    return UUID.fromString(string(json, field));
  }

  private static BigDecimal decimal(JsonObject json, String field) {
    return new BigDecimal(string(json, field));
  }

  private static String string(JsonObject json, String field) {
    JsonElement value = json.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON field: " + field);
    }
    return value.getAsString();
  }

  private record LockKey(UUID accountId, String assetId, boolean currency) {}

  private record RuntimeRiskSnapshot(
      MarketState state, ReferencePriceTracker referencePrices,
      CircuitBreaker circuitBreaker) {}

  private record MarketCoordinationKey(Object repositoryKey, String marketId) {}

  private static final class SettlementObservationFailure extends RuntimeException {
    private final RuntimeException original;

    private SettlementObservationFailure(RuntimeException original) {
      super(original);
      this.original = original;
    }

    private RuntimeException original() {
      return original;
    }
  }

  private static final class MarketRuntimeState {
    private OrderBook committedBook;
    private ReferencePriceTracker referencePrices;
    private CircuitBreaker circuitBreaker;
    private long committedMarketVersion;

    private MarketRuntimeState(
        OrderBook committedBook, ReferencePriceTracker referencePrices,
        CircuitBreaker circuitBreaker, long committedMarketVersion) {
      this.committedBook = committedBook;
      this.referencePrices = referencePrices;
      this.circuitBreaker = circuitBreaker;
      this.committedMarketVersion = committedMarketVersion;
    }
  }

  private record TransactionOutcome(
      OrderReceipt receipt, SettlementPlan plan, OrderBook book,
      ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker,
      long marketVersion, boolean duplicate) {
    private static TransactionOutcome duplicate(OrderReceipt receipt) {
      return new TransactionOutcome(receipt, null, null, null, null, Long.MIN_VALUE, true);
    }

    private static TransactionOutcome committed(
        OrderReceipt receipt, SettlementPlan plan, OrderBook book,
        ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker,
        long marketVersion) {
      return new TransactionOutcome(
          receipt, plan, book, referencePrices, circuitBreaker, marketVersion, false);
    }
  }

  private record ForceCancelOutcome(OrderReceipt receipt, OrderBook book, boolean duplicate) {
    private static ForceCancelOutcome duplicate(OrderReceipt receipt) {
      return new ForceCancelOutcome(receipt, null, true);
    }

    private static ForceCancelOutcome committed(OrderReceipt receipt, OrderBook book) {
      return new ForceCancelOutcome(receipt, book, false);
    }
  }
}
