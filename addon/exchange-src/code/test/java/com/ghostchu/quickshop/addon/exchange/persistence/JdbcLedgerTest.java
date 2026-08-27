package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.ledger.*;
import com.ghostchu.quickshop.addon.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcLedgerTest {
  @Test
  void acceptsBalancedJournalAndAppendsReversal(@TempDir Path temp) throws Exception {
    ConnectionProvider cp = SqliteTestDatabase.at(temp.resolve("ledger.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(cp, SqlDialect.SQLITE, names).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(cp, SqlDialect.SQLITE, names);
    LedgerJournal journal = journal("10.00", null);

    repository.inTransaction(tx -> { tx.appendJournal(journal); return null; });
    LedgerJournal reversal = journal("-10.00", journal.journalId());
    repository.inTransaction(tx -> { tx.appendJournal(reversal); return null; });

    assertThat(rowCount(cp, names.journals())).isEqualTo(2);
    assertThat(rowCount(cp, names.entries())).isEqualTo(4);
    LedgerJournal duplicateReference = new LedgerJournal(
        UUID.randomUUID(), journal.journalType(), journal.referenceId(), Instant.EPOCH, null,
        journal("10.00", null).entries());
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.appendJournal(duplicateReference);
      return null;
    })).isInstanceOf(SQLException.class);
    assertThat(rowCount(cp, names.journals())).isEqualTo(2);
    assertThat(rowCount(cp, names.entries())).isEqualTo(4);
    assertThat(reversalOf(cp, names, reversal.journalId())).isEqualTo(journal.journalId());
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.appendJournal(journal);
      return null;
    })).isInstanceOf(java.sql.SQLException.class);
    assertThat(rowCount(cp, names.journals())).isEqualTo(2);
    assertThat(rowCount(cp, names.entries())).isEqualTo(4);

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.appendJournal(new LedgerJournal(UUID.randomUUID(), "BROKEN", UUID.randomUUID(),
          Instant.EPOCH, null, List.of(new LedgerEntry(UUID.randomUUID(), "player:a", "USD",
          BigDecimal.ONE, Instant.EPOCH))));
      return null;
    })).isInstanceOf(UnbalancedJournalException.class);
    assertThatThrownBy(() -> directUpdateFirstEntry(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
    assertThatThrownBy(() -> directDeleteFirstEntry(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
    assertThatThrownBy(() -> directUpdateFirstJournal(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
    assertThatThrownBy(() -> directDeleteFirstJournal(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
  }

  @Test
  void readsOnlyTheRequestedAccountsLedgerInDescendingPages(@TempDir Path temp) throws Exception {
    ConnectionProvider cp = SqliteTestDatabase.at(temp.resolve("account-ledger.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(cp, SqlDialect.SQLITE, names).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(cp, SqlDialect.SQLITE, names);
    UUID accountId = UUID.randomUUID();
    UUID otherAccountId = UUID.randomUUID();
    LedgerJournal older = accountJournal(accountId, "OLDER", "1.00", Instant.EPOCH);
    LedgerJournal newer = accountJournal(accountId, "NEWER", "2.00", Instant.EPOCH.plusSeconds(1));
    LedgerJournal unrelated = accountJournal(
        otherAccountId, "UNRELATED", "3.00", Instant.EPOCH.plusSeconds(2));
    LedgerJournal misleadingSuffix = new LedgerJournal(
        UUID.randomUUID(), "NON_PLAYER", UUID.randomUUID(), Instant.EPOCH.plusSeconds(3), null,
        List.of(
            new LedgerEntry(UUID.randomUUID(), "custody:currency:" + accountId, "USD",
                BigDecimal.ONE, Instant.EPOCH.plusSeconds(3)),
            new LedgerEntry(UUID.randomUUID(), "custody:currency:balancing", "USD",
                BigDecimal.ONE.negate(), Instant.EPOCH.plusSeconds(3))));
    repository.inTransaction(tx -> {
      tx.appendJournal(older);
      tx.appendJournal(newer);
      tx.appendJournal(unrelated);
      tx.appendJournal(misleadingSuffix);
      return null;
    });

    assertThat(repository.accountLedgerEntries(accountId, 1, 0))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.journalType()).isEqualTo("NEWER");
          assertThat(entry.referenceId()).isEqualTo(newer.referenceId());
          assertThat(entry.amount()).isEqualByComparingTo("2.00");
        });
    assertThat(repository.accountLedgerEntries(accountId, 1, 1))
        .singleElement()
        .satisfies(entry -> assertThat(entry.journalType()).isEqualTo("OLDER"));
    assertThatThrownBy(() -> repository.accountLedgerEntries(accountId, 37, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsJournalThatBalancesOneAssetButNotAnother() {
    assertThatThrownBy(() -> new LedgerJournal(
        UUID.randomUUID(), "BROKEN", UUID.randomUUID(), Instant.EPOCH, null, List.of(
        new LedgerEntry(UUID.randomUUID(), "player:a", "USD", BigDecimal.ONE, Instant.EPOCH),
        new LedgerEntry(UUID.randomUUID(), "custody:USD", "USD", BigDecimal.ONE.negate(),
            Instant.EPOCH),
        new LedgerEntry(UUID.randomUUID(), "player:a", "DIAMOND", BigDecimal.ONE,
            Instant.EPOCH))))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessage("journal is not balanced for asset DIAMOND");
  }

  @Test
  void entryBatchFailureCannotCommitPartialJournalWhenCallerContinues(@TempDir Path temp)
      throws Exception {
    ConnectionProvider cp = SqliteTestDatabase.at(temp.resolve("batch-failure.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(cp, SqlDialect.SQLITE, names).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(cp, SqlDialect.SQLITE, names);
    LedgerJournal existing = journal("10.00", null);
    repository.inTransaction(tx -> { tx.appendJournal(existing); return null; });
    LedgerJournal failing = new LedgerJournal(
        UUID.randomUUID(), "ADJUSTMENT", UUID.randomUUID(), Instant.EPOCH, null, List.of(
        new LedgerEntry(UUID.randomUUID(), "player:b", "USD", BigDecimal.ONE, Instant.EPOCH),
        new LedgerEntry(existing.entries().get(0).entryId(), "custody:USD", "USD",
            BigDecimal.ONE.negate(), Instant.EPOCH)));

    repository.inTransaction(tx -> {
      try {
        tx.appendJournal(failing);
      } catch (SQLException expected) {
        assertThat(expected.getMessage()).isNotBlank();
      }
      return null;
    });

    assertThat(rowCount(cp, names.journals())).isEqualTo(1);
    assertThat(rowCount(cp, names.entries())).isEqualTo(2);
  }

  private static LedgerJournal journal(String amount, UUID reversalOf) {
    BigDecimal value = new BigDecimal(amount);
    return new LedgerJournal(UUID.randomUUID(), "ADJUSTMENT", UUID.randomUUID(),
        Instant.EPOCH, reversalOf, List.of(
        new LedgerEntry(UUID.randomUUID(), "player:a", "USD", value, Instant.EPOCH),
        new LedgerEntry(UUID.randomUUID(), "custody:USD", "USD", value.negate(), Instant.EPOCH)));
  }

  private static LedgerJournal accountJournal(
      UUID accountId, String type, String amount, Instant at) {
    BigDecimal value = new BigDecimal(amount);
    return new LedgerJournal(UUID.randomUUID(), type, UUID.randomUUID(), at, null, List.of(
        new LedgerEntry(UUID.randomUUID(), "liability:currency:" + accountId, "USD", value, at),
        new LedgerEntry(UUID.randomUUID(), "custody:currency:USD", "USD", value.negate(), at)));
  }

  private static void directUpdateFirstEntry(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var update = connection.prepareStatement(
             "UPDATE " + names.entries() + " SET amount='999.00'")) {
      update.executeUpdate();
    }
  }

  private static void directDeleteFirstEntry(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var delete = connection.prepareStatement("DELETE FROM " + names.entries())) {
      delete.executeUpdate();
    }
  }

  private static void directUpdateFirstJournal(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var update = connection.prepareStatement(
             "UPDATE " + names.journals() + " SET journal_type='MUTATED'")) {
      update.executeUpdate();
    }
  }

  private static void directDeleteFirstJournal(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var delete = connection.prepareStatement("DELETE FROM " + names.journals())) {
      delete.executeUpdate();
    }
  }

  private static int rowCount(ConnectionProvider cp, String table) throws java.sql.SQLException {
    try (var connection = cp.open();
         var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  private static UUID reversalOf(
      ConnectionProvider cp, TableNames names, UUID journalId) throws java.sql.SQLException {
    try (var connection = cp.open();
         var query = connection.prepareStatement(
             "SELECT reversal_of FROM " + names.journals() + " WHERE journal_id=?")) {
      query.setString(1, journalId.toString());
      try (var result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        return UUID.fromString(result.getString(1));
      }
    }
  }
}
