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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTransferRepositoryTest {
  @Test
  void preparedFactoryRejectsMissingIdentityConsistently() {
    assertThatThrownBy(() -> TransferRecord.prepared(
        null, UUID.randomUUID(), UUID.randomUUID(), TransferType.MONEY_DEPOSIT,
        "USD", BigDecimal.ONE, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid transfer");
  }

  @Test
  void createsOnceAndUsesCompareAndSetTransitions() throws Exception {
    TransferRepository repository = repository();
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    TransferRecord prepared = prepared(request, account, TransferType.MONEY_DEPOSIT, "USD", "50.00");

    TransferRecord first = repository.create(prepared);
    TransferRecord duplicate = repository.create(
        prepared(request, account, TransferType.MONEY_DEPOSIT, "USD", "50.0"));
    TransferRecord processing = repository.transition(first.transferId(), 0,
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);

    assertThat(duplicate.transferId()).isEqualTo(first.transferId());
    assertThat(processing.status()).isEqualTo(TransferStatus.PROCESSING);
    assertThat(processing.version()).isEqualTo(1);
    assertThat(repository.find(first.transferId())).contains(processing);
    assertThat(repository.findByRequest(account, request)).contains(processing);
    assertThatThrownBy(() -> repository.transition(first.transferId(), 0,
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null))
        .isInstanceOf(java.util.ConcurrentModificationException.class);
  }

  @Test
  void rejectsIdempotencyConflictsAndIllegalTransitions() throws Exception {
    TransferRepository repository = repository();
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    TransferRecord first = repository.create(
        prepared(request, account, TransferType.MONEY_DEPOSIT, "USD", "50.00"));

    assertThatThrownBy(() -> repository.create(
        prepared(request, account, TransferType.MONEY_WITHDRAWAL, "USD", "50.00")))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThatThrownBy(() -> repository.create(
        prepared(request, account, TransferType.MONEY_DEPOSIT, "USD", "51.00")))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThatThrownBy(() -> repository.transition(first.transferId(), first.version(),
        TransferStatus.PREPARED, TransferStatus.COMPLETED, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("illegal transfer transition");
  }

  @Test
  void onlyEvidenceGuardedRecoveryCanReturnProcessingTransferToPrepared() throws Exception {
    TransferRepository repository = repository();
    TransferRecord prepared = repository.create(prepared(
        UUID.randomUUID(), UUID.randomUUID(), TransferType.ITEM_WITHDRAWAL, "diamond-usd", "2"));
    TransferRecord processing = repository.transition(prepared.transferId(), prepared.version(),
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);

    assertThatThrownBy(() -> repository.transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.PREPARED, "inventory-capacity-race"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("illegal transfer transition");

    TransferRecord recovered = repository.transitionGuarded(
        processing.transferId(), processing.version(), TransferStatus.PROCESSING,
        TransferStatus.PREPARED, RecoveryEvidence.NO_MARKED_ITEMS, "inventory-capacity-race");

    assertThat(recovered.status()).isEqualTo(TransferStatus.PREPARED);
    assertThat(recovered.failureReason()).isEqualTo("inventory-capacity-race");
  }

  @Test
  void listsOnlyUnfinishedTransfers() throws Exception {
    TransferRepository repository = repository();
    UUID firstAccount = UUID.randomUUID();
    UUID secondAccount = UUID.randomUUID();
    TransferRecord unfinished = repository.create(prepared(
        UUID.randomUUID(), firstAccount, TransferType.ITEM_WITHDRAWAL, "diamond-usd", "2"));
    TransferRecord terminal = repository.create(prepared(
        UUID.randomUUID(), secondAccount, TransferType.MONEY_DEPOSIT, "USD", "5.00"));
    TransferRecord processing = repository.transition(terminal.transferId(), terminal.version(),
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
    repository.transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.FAILED, "rejected");

    assertThat(repository.findUnfinished(firstAccount)).containsExactly(unfinished);
    assertThat(repository.findUnfinished(secondAccount)).isEmpty();
    assertThat(repository.findAllUnfinished()).containsExactly(unfinished);
  }

  private static TransferRecord prepared(
      UUID request, UUID account, TransferType type, String asset, String amount) {
    return TransferRecord.prepared(
        UUID.randomUUID(), request, account, type, asset, new BigDecimal(amount), Instant.EPOCH);
  }

  private static TransferRepository repository() throws Exception {
    Path file = Files.createTempFile("quickshop-exchange-transfer-", ".sqlite");
    file.toFile().deleteOnExit();
    ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
    TableNames tables = new TableNames("transfer_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    return new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
  }
}
