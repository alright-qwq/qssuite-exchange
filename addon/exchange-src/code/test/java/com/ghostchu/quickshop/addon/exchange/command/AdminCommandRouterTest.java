package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommandRouterTest {
  @Test
  void cancelsAnOpenOrderWithTheDedicatedOrdersPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    OrderReceipt order = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        fixture.rules().marketId(), OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    Actor actor = new Actor("quickshop.exchange.admin.orders");
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);

    router.execute(actor, new String[] {"order", "cancel", order.orderId().toString(),
        "suspected abuse"});

    assertThat(fixture.orderStatus(order.orderId())).isEqualTo("CANCELLED");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  @Test
  void deniesOrderCancellationWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);
    Actor actor = new Actor();

    router.execute(actor, new String[] {"order", "cancel", UUID.randomUUID().toString(),
        "suspected abuse"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void containsErrorsThrownWhileHandlingAdminCommands() {
    Actor actor = new Actor("quickshop.exchange.admin.orders");
    actor.failPermissionWith = new LinkageError("shaded admin class conflict");
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of()), UUID::randomUUID);

    router.execute(actor, new String[] {"order", "cancel", "missing", "reason"});

    assertThat(actor.message).isEqualTo("admin-command-failed");
  }

  @Test
  void pausesAndResumesMarketsWithTheDedicatedMarketPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"market", "pause", fixture.rules().marketId(),
        "scheduled maintenance"});
    String paused = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(paused).isEqualTo("PAUSED");
    assertThat(actor.message).isEqualTo("request-accepted");

    router.execute(actor, new String[] {"market", "resume", fixture.rules().marketId(),
        "maintenance completed"});
    String resumed = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(resumed).isEqualTo("OPEN");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  @Test
  void deniesMarketMutationWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.orders");

    router.execute(actor, new String[] {"market", "pause", fixture.rules().marketId(),
        "scheduled maintenance"});

    assertThat(actor.message).isEqualTo("permission-denied");
    String status = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(status).isEqualTo("OPEN");
  }

  @Test
  void reconcilesAndExportsAuditWithTheDedicatedAuditPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var directory = Files.createTempDirectory("exchange-admin-route-audit-");
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        new com.ghostchu.quickshop.addon.exchange.operations.AuditExporter(), directory);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(administration, UUID::randomUUID, work -> {
      writes.incrementAndGet();
      work.run();
      return true;
    });
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "reconcile"});
    assertThat(actor.message).isEqualTo("admin-reconciliation-balanced");
    assertThat(writes).hasValue(1);

    router.execute(actor, new String[] {"audit", "export", "0",
        Long.toString(Instant.now().plusSeconds(1).getEpochSecond())});
    assertThat(actor.message).isEqualTo("admin-audit-exported");
    assertThat(Files.list(directory)).hasSize(1);
  }

  @Test
  void deniesAuditOperationsWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"audit", "reconcile"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void reportsAuditStatusThroughTheReadsExecutor() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var metrics = new com.ghostchu.quickshop.addon.exchange.operations.ExchangeMetrics();
    metrics.recordQueueLength(fixture.rules().marketId(), 2);
    metrics.recordMatchingLatency(fixture.rules().marketId(), java.time.Duration.ofMillis(5));
    fixture.repository().insertAuditAlert(
        new com.ghostchu.quickshop.addon.exchange.operations.AuditAlert(UUID.randomUUID(),
            fixture.rules().marketId(), null, "HIGH_CANCEL_PLACE_RATIO", "MEDIUM",
            "ratio=1.0", Instant.now(), null));
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(), null, null,
        null, null, metrics), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "status"});

    assertThat(actor.message).isEqualTo("admin-audit-status");
    assertThat(actor.arguments).singleElement().asString()
        .contains(fixture.rules().marketId(), "HIGH_CANCEL_PLACE_RATIO", "pending-reviews=0");
  }

  @Test
  void highlightsOpenAlertsInAuditStatus() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.repository().insertAuditAlert(
        new com.ghostchu.quickshop.addon.exchange.operations.AuditAlert(UUID.randomUUID(),
            fixture.rules().marketId(), null, "HIGH_CANCEL_PLACE_RATIO", "MEDIUM",
            "ratio=1.0", Instant.now(), null));
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "status"});

    assertThat(actor.message).isEqualTo("admin-audit-status");
    assertThat(actor.arguments).singleElement().asString()
        .contains("open-alerts=1", "§c");
  }

  @Test
  void reportsNoOpenAlertsWithoutHighlightInAuditStatus() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "status"});

    assertThat(actor.message).isEqualTo("admin-audit-status");
    assertThat(actor.arguments).singleElement().asString()
        .contains("open-alerts=0").doesNotContain("§c");
  }

  @Test
  void deniesAuditStatusWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"audit", "status"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void acknowledgesAlertThroughTheWriterFence() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var alert = new com.ghostchu.quickshop.addon.exchange.operations.AuditAlert(
        UUID.randomUUID(), fixture.rules().marketId(), null, "HIGH_CANCEL_PLACE_RATIO",
        "MEDIUM", "ratio=1.0", Instant.now(), null);
    fixture.repository().insertAuditAlert(alert);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()),
        UUID::randomUUID, work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        });
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "ack", alert.alertId().toString()});

    assertThat(actor.message).isEqualTo("admin-audit-acknowledged");
    assertThat(writes).hasValue(1);
    assertThat(fixture.repository().openAlerts(10)).isEmpty();
  }

  @Test
  void deniesAlertAcknowledgementWithoutAuditPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"audit", "ack", UUID.randomUUID().toString()});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void listsAndShowsReviewedTransfersWithoutEnteringTheWriterFence() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()),
        UUID::randomUUID, work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        });
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "list"});
    assertThat(actor.message).isEqualTo("admin-transfer-review-list");
    assertThat(actor.arguments).singleElement().asString().contains(reviewed.transferId().toString());

    router.execute(actor, new String[] {"transfer", "review", "show",
        reviewed.transferId().toString()});
    assertThat(actor.message).isEqualTo("admin-transfer-review-detail");
    assertThat(actor.arguments).singleElement().asString()
        .contains(reviewed.transferId().toString(), "MONEY_DEPOSIT", "REVIEW_REQUIRED");
    assertThat(writes).hasValue(0);
  }

  @Test
  void resolvesReviewedTransferThroughWriterFenceWithRecoveryPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()),
        UUID::randomUUID, work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        });
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "resolve",
        reviewed.transferId().toString(), "success", "economy", "receipt", "provider-001"});

    assertThat(actor.message).isEqualTo("request-accepted");
    assertThat(writes).hasValue(1);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.COMPLETED);
  }

  @Test
  void deniesTransferReviewWithoutRecoveryPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"transfer", "review", "show",
        reviewed.transferId().toString()});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  private static TransferRecord reviewedMoneyDeposit(ExchangeServiceFixture fixture)
      throws Exception {
    TransferRecord prepared = fixture.repository().create(TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TransferType.MONEY_DEPOSIT,
        fixture.rules().currencyId(), new BigDecimal("12.00"), Instant.EPOCH));
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
        "economy withdrawal result unknown");
  }

  private static final class Actor implements CommandActor {
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private Object[] arguments = new Object[0];
    private Error failPermissionWith;

    private Actor(String... permissions) {
      this.permissions.addAll(Set.of(permissions));
    }

    @Override public UUID accountId() { return accountId; }
    @Override public boolean hasPermission(String permission) {
      if (failPermissionWith != null) {
        throw failPermissionWith;
      }
      return permissions.contains(permission);
    }
    @Override public void message(String key, Object... arguments) {
      message = key;
      this.arguments = arguments;
    }
    @Override public void openMenu(String menuName, int page) { }
  }
}
