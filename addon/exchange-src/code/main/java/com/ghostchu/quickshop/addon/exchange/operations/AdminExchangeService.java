package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.ghostchu.quickshop.addon.exchange.security.SecurityMutationResult;
import com.ghostchu.quickshop.addon.exchange.security.SecurityService;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferJournals;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates audited administration through the market services that own live order books. */
public final class AdminExchangeService {
  private static final String PAUSE_MARKET_OPERATION = "PAUSE_MARKET";
  private static final String RESUME_MARKET_OPERATION = "RESUME_MARKET";
  private static final String RESOLVE_TRANSFER_REVIEW_OPERATION = "RESOLVE_TRANSFER_REVIEW";
  private static final String CLEANUP_TRANSFER_MARKERS_OPERATION = "CLEANUP_TRANSFER_MARKERS";
  private static final String RECONCILE_OPERATION = "RECONCILE";
  private static final String RECONCILIATION_AUTO_PAUSE = "RECONCILIATION_AUTO_PAUSE";
  private static final String RECONCILIATION_DIFFERENCE = "RECONCILIATION_DIFFERENCE";
  private static final Duration DEFAULT_AUDIT_RETENTION = Duration.ofDays(90);
  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("QuickShop-Exchange.Admin");

  private final Map<String, PersistentOrderService> markets = new ConcurrentHashMap<>();
  private final ExchangeRepository repository;
  private final AuditExporter auditExporter;
  private final java.util.concurrent.atomic.AtomicReference<Path> auditDirectory;
  private final SecurityService securities;
  private final InventoryGateway inventory;
  private final ExchangeMetrics metrics;
  private final BiConsumer<String, Boolean> securityCreated;
  private final java.util.concurrent.atomic.AtomicReference<Duration> auditRetention =
      new java.util.concurrent.atomic.AtomicReference<>(DEFAULT_AUDIT_RETENTION);

  public AdminExchangeService(Map<String, PersistentOrderService> markets) {
    this(markets, null, null, null, null, null, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository) {
    this(markets, repository, null, null, null, null, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory) {
    this(markets, repository, auditExporter, auditDirectory, null, null, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory, SecurityService securities) {
    this(markets, repository, auditExporter, auditDirectory, securities, null, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory, SecurityService securities,
      InventoryGateway inventory) {
    this(markets, repository, auditExporter, auditDirectory, securities, inventory, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory, SecurityService securities,
      InventoryGateway inventory, ExchangeMetrics metrics) {
    this(markets, repository, auditExporter, auditDirectory, securities, inventory, metrics, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory, SecurityService securities,
      InventoryGateway inventory, ExchangeMetrics metrics,
      BiConsumer<String, Boolean> securityCreated) {
    this.markets.putAll(Objects.requireNonNull(markets, "markets"));
    this.repository = repository;
    this.auditExporter = auditExporter;
    this.auditDirectory = new java.util.concurrent.atomic.AtomicReference<>(auditDirectory);
    this.securities = securities;
    this.inventory = inventory;
    this.metrics = metrics;
    this.securityCreated = securityCreated;
  }

  /** Combined operational health view: metrics, recent alerts and pending transfer reviews. */
  public record AuditStatus(MetricSnapshot metrics, List<AuditAlert> recentAlerts,
                            List<TransferRecord> pendingTransferReviews) {
    public AuditStatus {
      Objects.requireNonNull(metrics, "metrics");
      recentAlerts = List.copyOf(recentAlerts);
      pendingTransferReviews = List.copyOf(pendingTransferReviews);
    }
  }

  public AuditStatus auditStatus() throws SQLException {
    ExchangeRepository store = requireRepository();
    ExchangeMetrics snapshot = this.metrics;
    return new AuditStatus(
        snapshot == null ? new MetricSnapshot(java.util.Map.of()) : snapshot.snapshot(),
        store.recentAlerts(20),
        pendingTransferReviews());
  }

  /**
   * Acknowledges one alert so operational dashboards can distinguish reviewed findings.
   * Only the first acknowledgement writes an audit record; repeats are idempotent.
   */
  public void acknowledgeAlert(UUID actorId, UUID alertId) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(alertId, "alertId");
    requireRepository().inTransaction(tx -> {
      Instant at = Instant.now();
      if (tx.acknowledgeAlert(alertId, at) == 1) {
        tx.appendAudit(new AuditRecord(UUID.randomUUID(), actorId,
            "ACKNOWLEDGE_ALERT", alertId.toString(), "operator acknowledged alert",
            "OPEN", "ACKNOWLEDGED", at));
      }
      return null;
    });
  }

  public OrderReceipt forceCancel(UUID actorId, UUID requestId, String marketId, UUID orderId,
                                  String reason) throws SQLException {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    PersistentOrderService market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return market.forceCancel(actorId, requestId, orderId, reason);
  }

  public SecurityMutationResult securityCreate(
      UUID actorId, UUID requestId, String marketId, String symbol, String name,
      String description, String currencyId, java.math.BigDecimal basePrice,
      long totalSupply, long minimumUnit) throws SQLException {
    SecurityMutationResult result = requireSecurities().create(
        actorId, requestId, marketId, symbol, name, description, currencyId,
        basePrice, totalSupply, minimumUnit);
    BiConsumer<String, Boolean> hook = securityCreated;
    if (hook != null) {
      try {
        hook.accept(marketId, result.replayed());
      } catch (RuntimeException failure) {
        throw new IllegalStateException(
            "created-but-not-attached:" + marketId
                + "; run /qse reload to restore the runtime", failure);
      }
    }
    return result;
  }

  /** Registers a hot-added market so administration commands can act on it immediately. */
  public void registerMarket(String marketId, PersistentOrderService service) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(service, "service");
    if (markets.putIfAbsent(marketId, service) != null) {
      throw new IllegalArgumentException("market already exists in administration: " + marketId);
    }
  }

  public SecurityMutationResult securityIssue(
      UUID actorId, UUID requestId, String marketId, UUID targetAccount, long quantity,
      String reason) throws SQLException {
    return requireSecurities().issue(
        actorId, requestId, marketId, targetAccount, quantity, reason);
  }

  public SecurityMutationResult securityPause(
      UUID actorId, UUID requestId, String marketId, String reason) throws SQLException {
    return requireSecurities().pause(actorId, requestId, marketId, reason);
  }

  public SecurityMutationResult securityResume(
      UUID actorId, UUID requestId, String marketId, String reason) throws SQLException {
    return requireSecurities().resume(actorId, requestId, marketId, reason);
  }

  public SecurityMutationResult securityTransfer(
      UUID actorId, UUID requestId, String marketId, UUID fromAccount, UUID toAccount,
      long quantity, String reason) throws SQLException {
    return requireSecurities().transfer(
        actorId, requestId, marketId, fromAccount, toAccount, quantity, reason);
  }

  public SecurityMutationResult securityClose(
      UUID actorId, UUID requestId, String marketId, UUID recoveryAccount, String reason)
      throws SQLException {
    return requireSecurities().close(
        actorId, requestId, marketId, recoveryAccount, reason);
  }

  public ReconciliationReport reconcile() throws SQLException {
    return requireRepository().reconcile();
  }

  public ReconciliationReport reconcile(UUID actorId, UUID requestId) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    return requireRepository().inTransaction(tx -> {
      StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
      if (stored != null) {
        if (!RECONCILE_OPERATION.equals(stored.operation())) {
          throw new IllegalStateException("request id belongs to another operation");
        }
        return tx.reconcile();
      }
      ReconciliationReport report = tx.reconcile();
      protectAffectedMarkets(tx, actorId, report);
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, RECONCILE_OPERATION, reconciliationPayload(report)));
      return report;
    });
  }

  public Path exportAudit(Instant fromInclusive, Instant toExclusive)
      throws SQLException, IOException {
    ExchangeRepository store = requireRepository();
    AuditExporter exporter = Objects.requireNonNull(
        auditExporter, "audit exporter is required for audit export");
    Path directory = Objects.requireNonNull(
        auditDirectory.get(), "audit directory is required for audit export");
    Path exported = exporter.export(
        directory, store.auditRecords(fromInclusive, toExclusive), fromInclusive, toExclusive);
    int pruned = exporter.retain(directory, auditRetention.get());
    if (pruned > 0) {
      LOGGER.log(Level.INFO,
          "Exchange audit export pruned " + pruned + " expired file(s) in " + directory);
    }
    return exported;
  }

  /**
   * Hot-swaps the audit export directory. The path must already be validated against the addon
   * data folder; the running service never recreates directories on its own.
   */
  public void updateAuditDirectory(Path directory) {
    this.auditDirectory.set(Objects.requireNonNull(directory, "directory"));
  }

  /** Hot-updates how long exported audit CSVs are kept; zero disables pruning. */
  public void updateAuditRetention(Duration retention) {
    Objects.requireNonNull(retention, "retention");
    if (retention.isNegative()) {
      throw new IllegalArgumentException("audit export retention must not be negative");
    }
    auditRetention.set(retention);
  }

  public List<TransferRecord> pendingTransferReviews() throws SQLException {
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository transfers)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }
    return transfers.findAllUnfinished().stream()
        .filter(transfer -> transfer.status() == TransferStatus.REVIEW_REQUIRED)
        .toList();
  }

  public TransferRecord transferReview(UUID transferId) throws SQLException {
    Objects.requireNonNull(transferId, "transferId");
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository transfers)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }
    TransferRecord transfer = transfers.find(transferId)
        .orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
    if (transfer.status() != TransferStatus.REVIEW_REQUIRED) {
      throw new IllegalStateException("transfer is not awaiting review: " + transfer.status());
    }
    return transfer;
  }

  /**
   * Clears the inventory markers of a review-required item transfer so the player's
   * items become usable again. This is the audited prerequisite for resolving an item
   * deposit as failed or an item withdrawal as successful.
   */
  public TransferRecord cleanupItemMarkers(
      UUID actorId, UUID requestId, UUID transferId) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(transferId, "transferId");
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository transfers)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }
    if (inventory == null) {
      throw new IllegalStateException("inventory gateway is unavailable for marker cleanup");
    }
    TransferRecord transfer = transfers.find(transferId)
        .orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
    if (transfer.status() != TransferStatus.REVIEW_REQUIRED) {
      throw new IllegalStateException("transfer is not awaiting review: " + transfer.status());
    }
    if (transfer.type() != TransferType.ITEM_DEPOSIT
        && transfer.type() != TransferType.ITEM_WITHDRAWAL) {
      throw new IllegalArgumentException("marker cleanup applies only to item transfers");
    }
    InventoryResult result = inventory.clearMarker(
        transfer.accountId(), transfer.transferId()).join();
    if (result != InventoryResult.SUCCESS) {
      throw new IllegalStateException(
          "marker cleanup requires the player to be online; gateway returned " + result);
    }
    String payload = transferId.toString();
    Instant cleanedAt = Instant.now();
    return store.inTransaction(tx -> {
      StoredRequestResult duplicate = tx.requestResult(actorId, requestId).orElse(null);
      if (duplicate != null) {
        if (!CLEANUP_TRANSFER_MARKERS_OPERATION.equals(duplicate.operation())
            || !payload.equals(duplicate.payload())) {
          throw new IllegalStateException("request id belongs to another operation");
        }
        return transfer;
      }
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, CLEANUP_TRANSFER_MARKERS_OPERATION, payload));
      tx.appendAudit(new AuditRecord(
          UUID.randomUUID(), actorId, CLEANUP_TRANSFER_MARKERS_OPERATION,
          transferId.toString(), "type=" + transfer.type() + ";asset=" + transfer.assetId(),
          transferState(transfer), "markers=cleaned;status=" + transfer.status(), cleanedAt));
      return transfer;
    });
  }

  /**
   * Applies only the internal settlement implied by an administrator's external evidence.
   * This method never invokes the economy or inventory gateways.
   */
  public TransferRecord resolveReview(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision, String evidence)
      throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(decision, "decision");
    String normalizedEvidence = normalizeReviewEvidence(evidence);
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }

    Instant resolvedAt = Instant.now();
    String operationPayload = reviewPayload(transferId, decision);
    return store.inTransaction(tx -> {
      StoredRequestResult duplicate = tx.requestResult(actorId, requestId).orElse(null);
      if (duplicate != null) {
        return resolvedReviewResult(tx, duplicate, transferId, decision);
      }
      TransferRecord review = tx.transfer(transferId)
          .orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
      requireReviewDecision(review, decision);
      applyReviewSettlement(tx, review, decision, resolvedAt);
      TransferStatus target = decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
          ? TransferStatus.COMPLETED : TransferStatus.FAILED;
      TransferRecord resolved = tx.resolveReviewedTransfer(
          review.transferId(), review.version(), target, normalizedEvidence);
      tx.appendAudit(new AuditRecord(
          UUID.randomUUID(), actorId, RESOLVE_TRANSFER_REVIEW_OPERATION,
          review.transferId().toString(), normalizedEvidence,
          transferState(review), transferState(resolved), resolvedAt));
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, RESOLVE_TRANSFER_REVIEW_OPERATION, operationPayload));
      return resolved;
    });
  }

  public void pauseMarket(UUID actorId, UUID requestId, String marketId, String reason)
      throws SQLException {
    changeMarketStatus(actorId, requestId, marketId, reason,
        PAUSE_MARKET_OPERATION, MarketStatus.PAUSED);
  }

  public void resumeMarket(UUID actorId, UUID requestId, String marketId, String reason)
      throws SQLException {
    changeMarketStatus(actorId, requestId, marketId, reason,
        RESUME_MARKET_OPERATION, MarketStatus.OPEN);
  }

  private void changeMarketStatus(
      UUID actorId, UUID requestId, String marketId, String reason,
      String operation, MarketStatus target) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    requireMarket(marketId);
    String normalizedReason = normalizeReason(reason);
    ExchangeRepository store = requireRepository();
    store.inTransaction(tx -> {
      StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
      if (stored != null) {
        if (!operation.equals(stored.operation())) {
          throw new IllegalStateException("request id belongs to another operation");
        }
        return null;
      }
      MarketState before = tx.marketState(marketId);
      requireTransition(before.status(), target);
      MarketState after = new MarketState(
          before.marketId(), target, before.prioritySequence(), before.matchSequence(),
          before.referencePrice(), before.lastPrice(),
          target == MarketStatus.OPEN ? null : before.haltedUntil(),
          before.discoveryQuantity(), before.circuitBreakerLevel(), before.version() + 1);
      tx.updateMarketState(after, before.version());
      Instant changedAt = Instant.now();
      tx.appendAudit(new AuditRecord(
          UUID.randomUUID(), actorId, operation, marketId, normalizedReason,
          "status=" + before.status(), "status=" + after.status(), changedAt));
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, operation, "status=" + after.status()));
      return null;
    });
  }

  private void protectAffectedMarkets(
      ExchangeTransaction tx, UUID actorId, ReconciliationReport report) throws SQLException {
    if (report.balanced()) {
      return;
    }
    Instant detectedAt = Instant.now();
    String difference = reconciliationPayload(report);
    for (String marketId : affectedMarkets(report)) {
      MarketState before = tx.marketState(marketId);
      if (before.status() == MarketStatus.OPEN || before.status() == MarketStatus.HALTED) {
        MarketState after = new MarketState(
            before.marketId(), MarketStatus.PAUSED, before.prioritySequence(),
            before.matchSequence(), before.referencePrice(), before.lastPrice(),
            before.haltedUntil(), before.discoveryQuantity(), before.circuitBreakerLevel(),
            before.version() + 1);
        tx.updateMarketState(after, before.version());
        tx.appendAudit(new AuditRecord(
            UUID.randomUUID(), actorId, RECONCILIATION_AUTO_PAUSE, marketId, difference,
            "status=" + before.status(), "status=" + after.status(), detectedAt));
      }
      tx.insertHighAlert(
          UUID.randomUUID(), marketId, RECONCILIATION_DIFFERENCE, difference, detectedAt);
    }
  }

  private List<String> affectedMarkets(ReconciliationReport report) {
    if (report.underReservedOrders() > 0) {
      return markets.keySet().stream().sorted().toList();
    }
    java.util.Set<String> assets = new java.util.HashSet<>(report.ledgerDifferences().keySet());
    assets.addAll(report.custodyDifferences().keySet());
    List<String> affected = markets.entrySet().stream()
        .filter(entry -> assets.contains(entry.getKey())
            || assets.contains(entry.getValue().marketRules().currencyId()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
    return affected.isEmpty() ? markets.keySet().stream().sorted().toList() : affected;
  }

  private static String reconciliationPayload(ReconciliationReport report) {
    return "ledger=" + report.ledgerDifferences()
        + ";custody=" + report.custodyDifferences()
        + ";underReservedOrders=" + report.underReservedOrders();
  }

  private static void applyReviewSettlement(
      ExchangeTransaction tx, TransferRecord transfer, ReviewDecision decision, Instant at)
      throws SQLException {
    boolean success = decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS;
    switch (transfer.type()) {
      case MONEY_DEPOSIT -> {
        if (success) {
          tx.creditAvailableCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.moneyDeposit(transfer, at));
        }
      }
      case MONEY_WITHDRAWAL -> {
        if (success) {
          tx.consumeFrozenCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.moneyWithdrawal(transfer, at));
        } else {
          tx.releaseCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.releaseMoneyWithdrawal(transfer, at));
        }
      }
      case ITEM_DEPOSIT -> {
        if (success) {
          tx.creditAvailableItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.itemDeposit(transfer, at));
        }
      }
      case ITEM_WITHDRAWAL -> {
        if (success) {
          tx.consumeFrozenItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.itemWithdrawal(transfer, at));
        } else {
          tx.releaseItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.releaseItemWithdrawal(transfer, at));
        }
      }
    }
  }

  private long markedItemQuantity(TransferRecord transfer) {
    if (inventory == null) {
      return 0;
    }
    try {
      return inventory.markedQuantity(transfer.accountId(), transfer.transferId()).join();
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "unable to verify item withdrawal markers: " + failure.getMessage(), failure);
    }
  }

  private void requireReviewDecision(TransferRecord transfer, ReviewDecision decision) {
    if (transfer.status() != TransferStatus.REVIEW_REQUIRED) {
      throw new IllegalStateException("transfer is not awaiting review: " + transfer.status());
    }
    if (transfer.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        && "inventory deposit marking result unknown".equals(transfer.failureReason())) {
      throw new IllegalStateException(
          "item deposit success requires evidence that the marked items were removed");
    }
    if (transfer.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE
        && (inventory == null || markedItemQuantity(transfer) > 0)) {
      throw new IllegalStateException(
          "item deposit failure requires marker cleanup before terminal resolution");
    }
    if (transfer.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        && (inventory == null || markedItemQuantity(transfer) > 0)) {
      throw new IllegalStateException(
          "item withdrawal success requires marker cleanup before terminal resolution");
    }
    if (transfer.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE) {
      long marked = markedItemQuantity(transfer);
      if (marked > 0) {
        throw new IllegalStateException(
            "item withdrawal failure requires marker-free evidence; marked quantity="
                + marked + " may still be delivered");
      }
    }
  }

  private static TransferRecord resolvedReviewResult(
      ExchangeTransaction tx, StoredRequestResult stored, UUID transferId,
      ReviewDecision decision) throws SQLException {
    if (!RESOLVE_TRANSFER_REVIEW_OPERATION.equals(stored.operation())
        || !reviewPayload(transferId, decision).equals(stored.payload())) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    return tx.transfer(transferId)
        .orElseThrow(() -> new IllegalStateException("resolved transfer does not exist"));
  }

  private static String reviewPayload(UUID transferId, ReviewDecision decision) {
    return "transfer=" + transferId + ";decision=" + decision;
  }

  private static String transferState(TransferRecord transfer) {
    return "type=" + transfer.type() + ";status=" + transfer.status()
        + ";asset=" + transfer.assetId() + ";amount=" + transfer.amount().toPlainString()
        + ";version=" + transfer.version();
  }

  private static String normalizeReviewEvidence(String evidence) {
    if (evidence == null || evidence.trim().length() < 16) {
      throw new IllegalArgumentException(
          "review evidence must contain at least 16 characters");
    }
    return evidence.trim();
  }

  private ExchangeRepository requireRepository() {
    return Objects.requireNonNull(
        repository, "repository is required for exchange administration");
  }

  private SecurityService requireSecurities() {
    return Objects.requireNonNull(
        securities, "security service is required for stock administration");
  }

  private void requireMarket(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    if (!markets.containsKey(marketId)) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
  }

  private static void requireTransition(MarketStatus before, MarketStatus target) {
    if (target == MarketStatus.PAUSED) {
      if (before != MarketStatus.OPEN && before != MarketStatus.HALTED) {
        throw new IllegalStateException("cannot pause market from " + before);
      }
      return;
    }
    if (target == MarketStatus.OPEN && before != MarketStatus.PAUSED) {
      throw new IllegalStateException("cannot resume market from " + before);
    }
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException(
          "administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }

  /** Locates an active order across configured markets without exposing market selection to staff. */
  public OrderReceipt forceCancel(UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    Objects.requireNonNull(orderId, "orderId");
    IllegalArgumentException missing = null;
    for (PersistentOrderService market : markets.values()) {
      try {
        return market.forceCancel(actorId, requestId, orderId, reason);
      } catch (IllegalArgumentException failure) {
        if (!failure.getMessage().startsWith("order is not open:")) {
          throw failure;
        }
        missing = failure;
      }
    }
    throw missing == null ? new IllegalArgumentException("order is not open: " + orderId) : missing;
  }
}
