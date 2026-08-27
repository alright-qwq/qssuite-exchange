package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityAuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSecurityLedgerTest {
  @TempDir
  Path temp;

  private ConnectionProvider connections;
  private TableNames tables;
  private ExchangeRepository repository;
  private String marketId = "concept_alpha";

  @BeforeEach
  void createRepository() throws Exception {
    connections = SqliteTestDatabase.at(temp.resolve("security.db"));
    tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.markets()
          + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
          + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
          + " VALUES ('" + marketId + "','default','','','{}','{}','{}',1,1,0)");
    }
  }

  @Test
  void appliesEverySecurityTransformationAndIncrementsVersion() throws Exception {
    UUID account = UUID.randomUUID();

    repository.inTransaction(tx -> {
      assertSecurity(tx.securityBalance(account, marketId), 0, 0, 0);
      tx.creditAvailableSecurity(account, marketId, 100);
      assertSecurity(tx.securityBalance(account, marketId), 100, 0, 1);
      tx.freezeSecurity(account, marketId, 70);
      assertSecurity(tx.securityBalance(account, marketId), 30, 70, 2);
      tx.releaseSecurity(account, marketId, 20);
      assertSecurity(tx.securityBalance(account, marketId), 50, 50, 3);
      tx.consumeFrozenSecurity(account, marketId, 30);
      assertSecurity(tx.securityBalance(account, marketId), 50, 20, 4);
      return null;
    });

    assertSecurity(repository.inTransaction(tx -> tx.securityBalance(account, marketId)), 50, 20, 4);
  }

  @Test
  void rejectsEveryNonPositiveSecurityMutation() {
    UUID account = UUID.randomUUID();

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(account, marketId, 0);
      return null;
    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.freezeSecurity(account, marketId, -1);
      return null;
    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.releaseSecurity(account, marketId, 0);
      return null;
    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.consumeFrozenSecurity(account, marketId, -1);
      return null;
    }))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void insufficientSecuritySourceRollsBackAndPreservesState() throws Exception {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(account, marketId, 10);
      tx.freezeSecurity(account, marketId, 6);
      return null;
    });

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.creditAvailableSecurity(account, marketId, 2);
      tx.freezeSecurity(account, marketId, 7);
      return null;
    })).hasMessageContaining("security");
    assertSecurity(repository.inTransaction(tx -> tx.securityBalance(account, marketId)), 4, 6, 2);

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.releaseSecurity(account, marketId, 7);
      return null;
    })).hasMessageContaining("security");
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.consumeFrozenSecurity(account, marketId, 7);
      return null;
    })).hasMessageContaining("security");
    assertSecurity(repository.inTransaction(tx -> tx.securityBalance(account, marketId)), 4, 6, 2);
  }

  @Test
  void persistsLedgerEventsAndFindsThemByIdempotencyKey() throws Exception {
    UUID account = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    SecurityLedgerEntry entry = new SecurityLedgerEntry(UUID.randomUUID(), "issue-1", marketId,
        account, "ISSUE", 100, 100, 0, "ORDER", "order-1", actor, "grant",
        Instant.ofEpochMilli(1000));

    repository.inTransaction(tx -> {
      tx.appendSecurityLedger(entry);
      return null;
    });

    List<SecurityLedgerEntry> entries =
        repository.inTransaction(tx -> tx.securityLedger(marketId, account));
    assertThat(entries).containsExactly(entry);
    Optional<SecurityLedgerEntry> stored =
        repository.inTransaction(tx -> tx.securityLedgerEntry("issue-1"));
    assertThat(stored).contains(entry);
  }

  @Test
  void persistsDefinitionAndAuditWithOptimisticVersioning() throws Exception {
    UUID actor = UUID.randomUUID();
    SecurityDefinitionState definition = new SecurityDefinitionState(marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 0, 1, "OPEN", null,
        Instant.ofEpochMilli(1000), Instant.ofEpochMilli(1000), 0);
    SecurityAuditRecord audit = new SecurityAuditRecord(UUID.randomUUID(), "request-1", marketId,
        "CREATE", actor, "{}", "SUCCESS", Instant.ofEpochMilli(1000));

    repository.inTransaction(tx -> {
      tx.insertSecurityDefinition(definition);
      tx.appendSecurityAudit(audit);
      return null;
    });

    SecurityDefinitionState loaded =
        repository.inTransaction(tx -> tx.securityDefinition(marketId));
    assertThat(loaded.symbol()).isEqualTo("ALPHA");
    assertThat(loaded.issuedSupply()).isZero();
    Optional<SecurityAuditRecord> storedAudit =
        repository.inTransaction(tx -> tx.securityAudit("request-1"));
    assertThat(storedAudit).contains(audit);

    SecurityDefinitionState paused = new SecurityDefinitionState(marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 0, 1, "PAUSED", null,
        Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), loaded.version());
    repository.inTransaction(tx -> {
      tx.updateSecurityDefinition(paused, loaded.version());
      return null;
    });
    String status = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(status).isEqualTo("PAUSED");

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.updateSecurityDefinition(paused, loaded.version());
      return null;
    }))
        .isInstanceOf(ConcurrentModificationException.class);
  }

  private static void assertSecurity(SecurityBalance balance,
                                     long available, long frozen, long version) {
    assertThat(balance.availableQuantity()).isEqualTo(available);
    assertThat(balance.frozenQuantity()).isEqualTo(frozen);
    assertThat(balance.version()).isEqualTo(version);
  }
}
