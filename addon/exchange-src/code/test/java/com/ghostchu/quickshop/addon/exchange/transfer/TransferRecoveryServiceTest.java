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

  private static final class Fixture implements AutoCloseable {
    private final JdbcExchangeRepository repository;
    private final TransferRecord transfer;
    private final FakeInventoryGateway gateway = new FakeInventoryGateway();
    private final TransferRecoveryService recovery;

    private Fixture(JdbcExchangeRepository repository, TransferRecord transfer) {
      this.repository = repository;
      this.transfer = transfer;
      this.recovery = new TransferRecoveryService(repository, repository, gateway, Runnable::run);
    }

    static Fixture interrupted(TransferType type) throws Exception {
      Path file = Files.createTempFile("quickshop-exchange-recovery-", ".sqlite");
      file.toFile().deleteOnExit();
      ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
      TableNames tables = new TableNames("recovery_");
      new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
      JdbcExchangeRepository repository =
          new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
      UUID account = UUID.randomUUID();
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
      return new Fixture(repository, processing);
    }

    JdbcExchangeRepository repository() {
      return repository;
    }

    TransferRecord transfer() {
      return transfer;
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
