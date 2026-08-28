package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferJournals;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminExchangeServiceTest {
  @Test
  void securityCreateNotifiesLiveMarketAttachmentHook() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    List<String> attached = new ArrayList<>();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(), null, null,
        new com.ghostchu.quickshop.addon.exchange.security.SecurityService(fixture.repository()),
        null, null, (marketId, replayed) -> attached.add(marketId + ":" + replayed));
    UUID actor = UUID.randomUUID();

    var result = admin.securityCreate(actor, UUID.randomUUID(), "new_alpha", "NALPHA",
        "New Alpha", "New concept stock", "default", new BigDecimal("10.00"), 1000, 1);

    assertThat(result.replayed()).isFalse();
    assertThat(attached).containsExactly("new_alpha:false");
  }

  @Test
  void wrapsLiveAttachmentFailureSoTheOperatorCanRecoverWithReload() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(), null, null,
        new com.ghostchu.quickshop.addon.exchange.security.SecurityService(fixture.repository()),
        null, null, (marketId, replayed) -> {
          throw new IllegalStateException("markets.yml is read-only");
        });
    UUID actor = UUID.randomUUID();

    assertThatThrownBy(() -> admin.securityCreate(actor, UUID.randomUUID(), "new_beta", "NBETA",
        "New Beta", "Concept stock", "default", new BigDecimal("10.00"), 1000, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("created-but-not-attached:new_beta")
        .hasMessageContaining("/qse reload");
  }

  @Test
  void auditStatusCombinesMetricsAlertsAndPendingReviews() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var metrics = new ExchangeMetrics();
    metrics.recordQueueLength(fixture.rules().marketId(), 3);
    Instant at = Instant.ofEpochMilli(Instant.now().toEpochMilli());
    AuditAlert alert = new AuditAlert(UUID.randomUUID(), fixture.rules().marketId(), null,
        "HIGH_CANCEL_PLACE_RATIO", "MEDIUM", "ratio=1.0", at, null);
    fixture.repository().insertAuditAlert(alert);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(), null, null,
        null, null, metrics);

    AdminExchangeService.AuditStatus status = admin.auditStatus();

    assertThat(status.metrics().markets().get(fixture.rules().marketId()).queueLength())
        .isEqualTo(3);
    assertThat(status.recentAlerts()).containsExactly(alert);
    assertThat(status.pendingTransferReviews()).isEmpty();
  }

  @Test
  void acknowledgeAlertWritesOneAuditRecordAndIsIdempotent() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AuditAlert alert = new AuditAlert(UUID.randomUUID(), fixture.rules().marketId(), null,
        "HIGH_CANCEL_PLACE_RATIO", "MEDIUM", "ratio=1.0", Instant.now(), null);
    fixture.repository().insertAuditAlert(alert);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    UUID actor = UUID.randomUUID();

    admin.acknowledgeAlert(actor, alert.alertId());
    admin.acknowledgeAlert(actor, alert.alertId());

    assertThat(fixture.repository().openAlerts(10)).isEmpty();
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("ACKNOWLEDGE_ALERT");
          assertThat(record.targetId()).isEqualTo(alert.alertId().toString());
        });
  }

  @Test
  void forceCancelReturnsReservedCurrencyAndAppendsAnAuditRecord() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID buyer = fixture.accountWithCurrency("500.00");
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, fixture.rules().marketId(), OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));

    admin.forceCancel(actor, UUID.randomUUID(), fixture.rules().marketId(), receipt.orderId(),
        "suspected abuse");

    assertThat(fixture.orderStatus(receipt.orderId())).isEqualTo("CANCELLED");
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("500.00");
    assertThat(fixture.frozenCurrency(buyer)).isZero();
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("FORCE_CANCEL_ORDER");
          assertThat(record.targetId()).isEqualTo(receipt.orderId().toString());
          assertThat(record.reason()).isEqualTo("suspected abuse");
          assertThat(record.beforeState()).contains("OPEN");
          assertThat(record.afterState()).contains("CANCELLED");
        });
    assertThat(fixture.tradeCount()).isZero();
  }

  @Test
  void registerMarketMakesForceCancelWorkWithoutRestart() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID buyer = fixture.accountWithCurrency("500.00");
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, fixture.rules().marketId(), OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    AdminExchangeService admin = new AdminExchangeService(Map.of());

    admin.registerMarket(fixture.rules().marketId(), fixture.service());
    admin.forceCancel(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
        receipt.orderId(), "hot-added market");

    assertThat(fixture.orderStatus(receipt.orderId())).isEqualTo("CANCELLED");
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("500.00");
  }

  @Test
  void forceCancelReturnsReservedItemsForSellOrders() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(3);
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), seller, fixture.rules().marketId(), OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));

    admin.forceCancel(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
        receipt.orderId(), "suspected abuse");

    assertThat(fixture.orderStatus(receipt.orderId())).isEqualTo("CANCELLED");
    assertThat(fixture.availableItems(seller)).isEqualTo(3);
    assertThat(fixture.frozenItems(seller)).isZero();
  }

  @Test
  void replaysForceCancelBeforeValidatingReason() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID buyer = fixture.accountWithCurrency("500.00");
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, fixture.rules().marketId(), OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1));
    UUID actor = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));

    OrderReceipt first = admin.forceCancel(actor, requestId, fixture.rules().marketId(),
        receipt.orderId(), "suspected abuse");
    OrderReceipt replay = admin.forceCancel(actor, requestId, fixture.rules().marketId(),
        receipt.orderId(), "bad");

    assertThat(replay).isEqualTo(first);
  }

  @Test
  void pausesAndResumesAMarketWithAppendOnlyAuditRecords() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    admin.pauseMarket(actor, UUID.randomUUID(), fixture.rules().marketId(),
        "scheduled maintenance");
    MarketStatus paused = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status());
    assertThat(paused).isEqualTo(MarketStatus.PAUSED);

    admin.resumeMarket(actor, UUID.randomUUID(), fixture.rules().marketId(),
        "maintenance completed");
    MarketStatus resumed = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status());
    assertThat(resumed).isEqualTo(MarketStatus.OPEN);
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .extracting(AuditRecord::action)
        .containsExactly("PAUSE_MARKET", "RESUME_MARKET");
  }

  @Test
  void refusesToResumeARecoveringMarket() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.repository().inTransaction(tx -> {
      var state = tx.marketState(fixture.rules().marketId());
      tx.updateMarketState(new com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState(
          state.marketId(), MarketStatus.RECOVERING, state.prioritySequence(), state.matchSequence(),
          state.referencePrice(), state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
          state.circuitBreakerLevel(), state.version() + 1), state.version());
      return null;
    });
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        admin.resumeMarket(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
            "operator override"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RECOVERING");
  }

  @Test
  void reconcilesAndExportsAStatusChangeAuditRange() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    Path directory = Files.createTempDirectory("exchange-admin-audit-");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        new AuditExporter(), directory);
    Instant from = Instant.now().minusSeconds(1);
    admin.pauseMarket(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
        "scheduled maintenance");
    Instant to = Instant.now().plusSeconds(1);

    assertThat(admin.reconcile().balanced()).isTrue();
    Path exported = admin.exportAudit(from, to);
    assertThat(exported).exists().isRegularFile().hasParent(directory.toAbsolutePath());
    assertThat(Files.readString(exported)).contains("PAUSE_MARKET", fixture.rules().marketId());
  }

  @Test
  void hotSwappedAuditDirectoryRoutesLaterExportsToTheNewLocation() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    Path firstDirectory = Files.createTempDirectory("exchange-admin-audit-first-");
    Path secondDirectory = Files.createTempDirectory("exchange-admin-audit-second-");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        new AuditExporter(), firstDirectory);
    Instant from = Instant.now().minusSeconds(1);
    admin.pauseMarket(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
        "scheduled maintenance");
    Instant to = Instant.now().plusSeconds(1);

    admin.updateAuditDirectory(secondDirectory);

    Path exported = admin.exportAudit(from, to);
    assertThat(exported).exists().isRegularFile().hasParent(secondDirectory.toAbsolutePath());
    assertThat(exported.getParent()).isNotEqualTo(firstDirectory.toAbsolutePath());
  }

  @Test
  void pausesAffectedMarketAndAppendsAuditWhenReconciliationFindsACurrencyDifference()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    fixture.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(account, fixture.rules().currencyId(), new BigDecimal("1.00"));
      return null;
    });
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    var report = admin.reconcile(actor, UUID.randomUUID());

    assertThat(report.balanced()).isFalse();
    MarketStatus protectedStatus = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status());
    assertThat(protectedStatus).isEqualTo(MarketStatus.PAUSED);
    assertThat(fixture.reconciliationAlertCount()).isEqualTo(1);
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("RECONCILIATION_AUTO_PAUSE");
          assertThat(record.targetId()).isEqualTo(fixture.rules().marketId());
          assertThat(record.reason()).contains(fixture.rules().currencyId(), "1.00");
          assertThat(record.beforeState()).isEqualTo("status=OPEN");
          assertThat(record.afterState()).isEqualTo("status=PAUSED");
        });
  }

  @Test
  void scheduledReconciliationProtectionPausesTheAffectedMarketWithoutARequestMarker()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(UUID.randomUUID(), fixture.rules().currencyId(),
          new BigDecimal("1.00"));
      return null;
    });
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    var report = admin.reconcileAndProtect(actor);

    assertThat(report.balanced()).isFalse();
    MarketStatus protectedStatus = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status());
    assertThat(protectedStatus).isEqualTo(MarketStatus.PAUSED);
    assertThat(fixture.reconciliationAlertCount()).isEqualTo(1);
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("RECONCILIATION_AUTO_PAUSE");
          assertThat(record.targetId()).isEqualTo(fixture.rules().marketId());
        });
  }

  @Test
  void pausesAllMarketsWhenAReconciliationDifferenceCannotBeMappedToAConfiguredAsset()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(UUID.randomUUID(), "UNKNOWN", new BigDecimal("1.00"));
      return null;
    });
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    admin.reconcile(UUID.randomUUID(), UUID.randomUUID());

    MarketStatus protectedStatus = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status());
    assertThat(protectedStatus).isEqualTo(MarketStatus.PAUSED);
    assertThat(fixture.reconciliationAlertCount()).isEqualTo(1);
  }

  @Test
  void confirmsExternalSuccessBySettlingAReviewedMoneyDepositWithoutCallingExternalSystems()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedTransfer(
        fixture, account, TransferType.MONEY_DEPOSIT, fixture.rules().currencyId(), "25.00");
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(actor, UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "economy receipt bank-2026-07-28-001");

    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableCurrency(account)).isEqualByComparingTo("25.00");
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("RESOLVE_TRANSFER_REVIEW");
          assertThat(record.targetId()).isEqualTo(reviewed.transferId().toString());
          assertThat(record.beforeState()).contains("REVIEW_REQUIRED", "MONEY_DEPOSIT");
          assertThat(record.afterState()).contains("COMPLETED");
        });
  }

  @Test
  void confirmsExternalFailureForMoneyDepositWithoutCreatingInternalLiability() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedTransfer(
        fixture, account, TransferType.MONEY_DEPOSIT, fixture.rules().currencyId(), "25.00");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "economy provider confirms withdrawal was rejected");

    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableCurrency(account)).isZero();
  }

  @Test
  void confirmsExternalSuccessByConsumingReviewedMoneyWithdrawalReservation() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithCurrency("40.00");
    TransferRecord reviewed = reviewedWithdrawal(
        fixture, account, TransferType.MONEY_WITHDRAWAL, fixture.rules().currencyId(), "15.00");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "economy provider confirms deposit receipt player-001");

    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableCurrency(account)).isEqualByComparingTo("25.00");
    assertThat(fixture.frozenCurrency(account)).isZero();
  }

  @Test
  void confirmsExternalFailureByReleasingReviewedMoneyWithdrawalReservation() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithCurrency("40.00");
    TransferRecord reviewed = reviewedWithdrawal(
        fixture, account, TransferType.MONEY_WITHDRAWAL, fixture.rules().currencyId(), "15.00");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "economy provider confirms no deposit");

    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableCurrency(account)).isEqualByComparingTo("40.00");
    assertThat(fixture.frozenCurrency(account)).isZero();
  }

  @Test
  void confirmsRemovedItemDepositByCreditingInternalCustody() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedTransfer(fixture, account, TransferType.ITEM_DEPOSIT,
        fixture.rules().marketId(), "2", "inventory deposit removal result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "inventory log confirms two marked items were removed");

    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(2);
  }

  @Test
  void releasesReviewedItemWithdrawalWhenDeliveryDidNotOccur() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account, TransferType.ITEM_WITHDRAWAL,
        fixture.rules().marketId(), "2");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "inventory snapshot confirms no marked items were delivered");

    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableItems(account)).isEqualTo(3);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void rejectsReviewedItemWithdrawalFailureWhileMarkedItemsMayStillBeDelivered() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account, TransferType.ITEM_WITHDRAWAL,
        fixture.rules().marketId(), "2");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        null, null, null, new com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway() {
          @Override
          public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> markForDeposit(
              UUID playerId, org.bukkit.inventory.ItemStack template, long quantity, UUID transferId) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.UNKNOWN);
          }

          @Override
          public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> removeMarked(
              UUID playerId, UUID transferId, long quantity) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.UNKNOWN);
          }

          @Override
          public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> deliverMarked(
              UUID playerId, org.bukkit.inventory.ItemStack template, long quantity, UUID transferId) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.UNKNOWN);
          }

          @Override
          public java.util.concurrent.CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
            return java.util.concurrent.CompletableFuture.completedFuture(2L);
          }

          @Override
          public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> clearMarker(
              UUID playerId, UUID transferId) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS);
          }
        });

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.resolveReview(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "inventory snapshot is unreliable, marked delivery may have occurred"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker-free evidence");

    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isEqualTo(2);
  }

  @Test
  void rejectsUnsafeSuccessForReviewedItemDepositThatNeverReachedProcessing() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord prepared = fixture.repository().create(TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TransferType.ITEM_DEPOSIT,
        fixture.rules().marketId(), BigDecimal.valueOf(2), Instant.EPOCH));
    TransferRecord reviewed = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.REVIEW_REQUIRED, "inventory deposit marking result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.resolveReview(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator only found an inventory marker"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("item deposit");

    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.availableItems(reviewed.accountId())).isZero();
  }

  @Test
  void rejectsItemDepositFailureWhenMarkedItemsMayStillNeedCleanup() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedTransfer(fixture, UUID.randomUUID(),
        TransferType.ITEM_DEPOSIT, fixture.rules().marketId(), "2",
        "inventory deposit removal result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.resolveReview(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "inventory log says removal did not complete"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker cleanup");
  }

  @Test
  void rejectsItemWithdrawalSuccessUntilMarkedDeliveryCanBeCleanedSafely() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account, TransferType.ITEM_WITHDRAWAL,
        fixture.rules().marketId(), "2");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.resolveReview(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "inventory log confirms marked delivery completed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker cleanup");
  }

  @Test
  void cleansItemDepositMarkersThenAllowsFailureResolution() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedTransfer(fixture, account, TransferType.ITEM_DEPOSIT,
        fixture.rules().marketId(), "2", "inventory deposit removal result unknown");
    MarkerGateway gateway = new MarkerGateway(2);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        null, null, null, gateway);

    assertThatThrownBy(() -> admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "inventory log says removal did not complete"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker cleanup");

    TransferRecord cleaned = admin.cleanupItemMarkers(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId());
    assertThat(cleaned.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(gateway.markedQuantity).isZero();

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_FAILURE,
        "inventory log says removal did not complete");
    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableItems(account)).isZero();
  }

  @Test
  void cleansItemWithdrawalMarkersThenAllowsSuccessResolution() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account, TransferType.ITEM_WITHDRAWAL,
        fixture.rules().marketId(), "2");
    MarkerGateway gateway = new MarkerGateway(2);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        null, null, null, gateway);

    assertThatThrownBy(() -> admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "inventory log confirms marked delivery completed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker cleanup");

    TransferRecord cleaned = admin.cleanupItemMarkers(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId());
    assertThat(cleaned.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(gateway.markedQuantity).isZero();

    TransferRecord resolved = admin.resolveReview(UUID.randomUUID(), UUID.randomUUID(),
        reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "inventory log confirms marked delivery completed");
    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void markerCleanupIsIdempotentForTheSameAdministratorRequest() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedTransfer(fixture, account, TransferType.ITEM_DEPOSIT,
        fixture.rules().marketId(), "2", "inventory deposit removal result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        null, null, null, new MarkerGateway(2));
    UUID actor = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    TransferRecord first = admin.cleanupItemMarkers(actor, requestId, reviewed.transferId());
    TransferRecord duplicate = admin.cleanupItemMarkers(actor, requestId, reviewed.transferId());

    assertThat(duplicate).isEqualTo(first);
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .extracting(AuditRecord::action)
        .containsOnly("CLEANUP_TRANSFER_MARKERS");
  }

  @Test
  void reviewResolutionIsIdempotentForTheSameAdministratorRequest() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedTransfer(fixture, UUID.randomUUID(),
        TransferType.MONEY_DEPOSIT, fixture.rules().currencyId(), "10.00");
    UUID actor = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());

    TransferRecord first = admin.resolveReview(actor, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "economy receipt duplicate-safe-001");
    TransferRecord duplicate = admin.resolveReview(actor, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "economy receipt duplicate-safe-001");

    assertThat(duplicate).isEqualTo(first);
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .hasSize(1);
  }

  private static TransferRecord reviewedTransfer(
      ExchangeServiceFixture fixture, UUID account, TransferType type, String asset, String amount)
      throws Exception {
    return reviewedTransfer(fixture, account, type, asset, amount, "external result unknown");
  }

  private static TransferRecord reviewedTransfer(
      ExchangeServiceFixture fixture, UUID account, TransferType type, String asset, String amount,
      String reviewReason) throws Exception {
    TransferRecord prepared = fixture.repository().create(TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), account, type, asset, new BigDecimal(amount),
        Instant.EPOCH));
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, reviewReason);
  }

  private static TransferRecord reviewedWithdrawal(
      ExchangeServiceFixture fixture, UUID account, TransferType type, String asset, String amount)
      throws Exception {
    BigDecimal quantity = new BigDecimal(amount);
    TransferRecord candidate = TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), account, type, asset, quantity, Instant.EPOCH);
    TransferRecord prepared = fixture.repository().inTransaction(tx -> {
      TransferRecord persisted = tx.createTransfer(candidate);
      if (type == TransferType.MONEY_WITHDRAWAL) {
        tx.freezeCurrency(account, asset, quantity);
        tx.appendJournal(TransferJournals.freezeMoneyWithdrawal(candidate, Instant.EPOCH));
      } else {
        tx.freezeItems(account, asset, quantity.longValueExact());
        tx.appendJournal(TransferJournals.freezeItemWithdrawal(candidate, Instant.EPOCH));
      }
      return persisted;
    });
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, "external result unknown");
  }

  private static final class MarkerGateway
      implements com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway {
    private long markedQuantity;

    private MarkerGateway(long markedQuantity) {
      this.markedQuantity = markedQuantity;
    }

    @Override
    public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> markForDeposit(
        UUID playerId, org.bukkit.inventory.ItemStack template, long quantity, UUID transferId) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS);
    }

    @Override
    public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> removeMarked(
        UUID playerId, UUID transferId, long quantity) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS);
    }

    @Override
    public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> deliverMarked(
        UUID playerId, org.bukkit.inventory.ItemStack template, long quantity, UUID transferId) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
      return java.util.concurrent.CompletableFuture.completedFuture(markedQuantity);
    }

    @Override
    public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult> clearMarker(
        UUID playerId, UUID transferId) {
      markedQuantity = 0;
      return java.util.concurrent.CompletableFuture.completedFuture(
          com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS);
    }
  }
}
