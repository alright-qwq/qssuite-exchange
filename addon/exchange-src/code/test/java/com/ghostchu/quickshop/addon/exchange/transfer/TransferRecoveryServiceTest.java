package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRecoveryServiceTest {
  @ParameterizedTest
  @CsvSource({
      "MONEY_DEPOSIT,REVIEW_REQUIRED",
      "MONEY_WITHDRAWAL,REVIEW_REQUIRED",
      "ITEM_DEPOSIT,REVIEW_REQUIRED",
      "ITEM_WITHDRAWAL,COMPLETED"
  })
  void recoversOnlyWhenExternalMarkerProvesOutcome(
      TransferType type, TransferStatus expected) throws Exception {
    try (Fixture fixture = Fixture.interrupted(type)) {
      if (type == TransferType.ITEM_WITHDRAWAL) {
        fixture.gateway().markedQuantity = 2;
      }

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(expected);
      assertThat(fixture.repository().find(recovered.transferId())).contains(recovered);
    }
  }

  @org.junit.jupiter.api.Test
  void marksInterruptedDepositFailedWhenAllItemsRemainMarked() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_DEPOSIT)) {
      fixture.gateway().markedQuantity = 2;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(fixture.gateway().markedQuantity).isZero();
    }
  }

  @org.junit.jupiter.api.Test
  void releasesFrozenCurrencyWhenMoneyWithdrawalDiedBeforeProcessing() throws Exception {
    try (Fixture fixture = Fixture.prepared(TransferType.MONEY_WITHDRAWAL, true)) {
      var before = fixture.repository().inTransaction(transaction ->
          transaction.currency(fixture.account(), "diamond-usd"));
      assertThat(before.frozen()).isEqualByComparingTo("2");
      assertThat(before.available()).isEqualByComparingTo("0");

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      var after = fixture.repository().inTransaction(transaction ->
          transaction.currency(fixture.account(), "diamond-usd"));
      assertThat(after.frozen()).isEqualByComparingTo("0");
      assertThat(after.available()).isEqualByComparingTo("2");
    }
  }

  @org.junit.jupiter.api.Test
  void failsMoneyDepositThatDiedBeforeProcessing() throws Exception {
    try (Fixture fixture = Fixture.prepared(TransferType.MONEY_DEPOSIT, false)) {
      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(recovered.failureReason()).contains("interrupted before processing");
    }
  }

  @org.junit.jupiter.api.Test
  void failsPreparedItemWithdrawalAndReleasesFrozenItems() throws Exception {
    try (Fixture fixture = Fixture.prepared(TransferType.ITEM_WITHDRAWAL, true)) {
      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(recovered.failureReason()).contains("interrupted before processing");
      var after = fixture.repository().inTransaction(transaction ->
          transaction.inventory(fixture.account(), "diamond-usd"));
      assertThat(after.frozenQuantity()).isZero();
      assertThat(after.availableQuantity()).isEqualTo(2);
    }
  }

  @org.junit.jupiter.api.Test
  void marksPreparedItemWithdrawalReviewWhenItemsWereAlreadyDelivered() throws Exception {
    try (Fixture fixture = Fixture.prepared(TransferType.ITEM_WITHDRAWAL, true)) {
      fixture.gateway().markedQuantity = 2;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(recovered.failureReason()).contains("marker is uncertain");
      var after = fixture.repository().inTransaction(transaction ->
          transaction.inventory(fixture.account(), "diamond-usd"));
      assertThat(after.frozenQuantity()).isEqualTo(2);
    }
  }

  @org.junit.jupiter.api.Test
  void failsPreparedItemDepositAndClearsResidualMarker() throws Exception {
    try (Fixture fixture = Fixture.prepared(TransferType.ITEM_DEPOSIT, false)) {
      fixture.gateway().markedQuantity = 2;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(recovered.failureReason()).contains("interrupted before processing");
      assertThat(fixture.gateway().markedQuantity).isZero();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final JdbcExchangeRepository repository;
    private final TransferRecord transfer;
    private final UUID account;
    private final FakeInventoryGateway gateway = new FakeInventoryGateway();
    private final TransferRecoveryService recovery;

    private Fixture(JdbcExchangeRepository repository, TransferRecord transfer, UUID account) {
      this.repository = repository;
      this.transfer = transfer;
      this.account = account;
      this.recovery = new TransferRecoveryService(repository, repository, gateway, Runnable::run);
    }

    static Fixture interrupted(TransferType type) throws Exception {
      UUID account = UUID.randomUUID();
      JdbcExchangeRepository repository = newRepository();
      TransferRecord prepared = repository.create(TransferRecord.prepared(
          UUID.randomUUID(), UUID.randomUUID(), account, type, "diamond-usd",
          new BigDecimal("2"), Instant.EPOCH));
      if (type == TransferType.ITEM_WITHDRAWAL) {
        repository.inTransaction(transaction -> {
          transaction.creditAvailableItems(account, "diamond-usd", 2);
          transaction.freezeItems(account, "diamond-usd", 2);
          return null;
        });
      }
      TransferRecord processing = repository.transition(prepared.transferId(), prepared.version(),
          TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
      return new Fixture(repository, processing, account);
    }

    static Fixture prepared(TransferType type, boolean frozen) throws Exception {
      UUID account = UUID.randomUUID();
      JdbcExchangeRepository repository = newRepository();
      TransferRecord prepared = repository.create(TransferRecord.prepared(
          UUID.randomUUID(), UUID.randomUUID(), account, type, "diamond-usd",
          new BigDecimal("2"), Instant.EPOCH));
      if (frozen && type == TransferType.MONEY_WITHDRAWAL) {
        repository.inTransaction(transaction -> {
          transaction.creditAvailableCurrency(account, "diamond-usd", new BigDecimal("2"));
          transaction.freezeCurrency(account, "diamond-usd", new BigDecimal("2"));
          return null;
        });
      }
      if (frozen && type == TransferType.ITEM_WITHDRAWAL) {
        repository.inTransaction(transaction -> {
          transaction.creditAvailableItems(account, "diamond-usd", 2);
          transaction.freezeItems(account, "diamond-usd", 2);
          return null;
        });
      }
      return new Fixture(repository, prepared, account);
    }

    private static JdbcExchangeRepository newRepository() throws Exception {
      Path file = Files.createTempFile("quickshop-exchange-recovery-", ".sqlite");
      file.toFile().deleteOnExit();
      ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
      TableNames tables = new TableNames("recovery_");
      new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
      return new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    }

    JdbcExchangeRepository repository() {
      return repository;
    }

    TransferRecord transfer() {
      return transfer;
    }

    UUID account() {
      return account;
    }

    FakeInventoryGateway gateway() {
      return gateway;
    }

    TransferRecoveryService recovery() {
      return recovery;
    }

    @Override
    public void close() {
    }
  }

  private static final class FakeInventoryGateway implements InventoryGateway {
    private long markedQuantity;

    @Override
    public CompletableFuture<InventoryResult> markForDeposit(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<InventoryResult> removeMarked(
        UUID playerId, UUID transferId, long quantity) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<InventoryResult> deliverMarked(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
      return CompletableFuture.completedFuture(markedQuantity);
    }

    @Override
    public CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId) {
      markedQuantity = 0;
      return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
    }
  }
}
