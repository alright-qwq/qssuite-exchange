package com.ghostchu.quickshop.addon.exchange.security;

import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityAuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityServiceTest {
  @TempDir
  Path temp;

  private ConnectionProvider connections;
  private TableNames tables;
  private ExchangeRepository repository;
  private SecurityService service;
  private final String marketId = "concept_alpha";

  @BeforeEach
  void createRepository() throws Exception {
    connections = SqliteTestDatabase.at(temp.resolve("security-service.db"));
    tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    service = new SecurityService(repository);
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.markets()
          + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
          + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
          + " VALUES ('" + marketId + "','default','','','{}','{}','{}',1,1,0)");
      statement.executeUpdate("INSERT INTO " + tables.marketState()
          + " (market_id,status,priority_sequence,match_sequence,reference_price,last_price,"
          + "halted_until,discovery_quantity,circuit_breaker_level,version)"
          + " VALUES ('" + marketId + "','OPEN',0,0,'10.00',NULL,NULL,0,0,0)");
    }
  }

  @Test
  void createPersistsOpenDefinitionAndReplaysDuplicateRequest() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID request = UUID.randomUUID();

    SecurityMutationResult created = service.create(actor, request, marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 1);

    assertThat(created.replayed()).isFalse();
    assertThat(created.status()).isEqualTo("OPEN");
    SecurityDefinitionState definition =
        repository.inTransaction(tx -> tx.securityDefinition(marketId));
    assertThat(definition.symbol()).isEqualTo("ALPHA");
    assertThat(definition.issuedSupply()).isZero();
    Optional<SecurityAuditRecord> storedAudit =
        repository.inTransaction(tx -> tx.securityAudit(request.toString()));
    assertThat(storedAudit).isPresent();

    SecurityMutationResult replayed = service.create(actor, request, marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 1);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.status()).isEqualTo("OPEN");
  }

  @Test
  void createRejectsInvalidSymbolFormat() throws Exception {
    UUID actor = UUID.randomUUID();

    assertThatThrownBy(() -> service.create(actor, UUID.randomUUID(), marketId, "alpha",
        "Alpha", "lowercase symbol", "default", new BigDecimal("10.00"), 1000, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uppercase");
    assertThatThrownBy(() -> service.create(actor, UUID.randomUUID(), marketId, "ALPHA!",
        "Alpha", "punctuation symbol", "default", new BigDecimal("10.00"), 1000, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uppercase");
    assertThatThrownBy(() -> service.create(actor, UUID.randomUUID(), marketId,
        "A".repeat(17), "Alpha", "too long symbol", "default", new BigDecimal("10.00"), 1000, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("16 characters");

    Optional<SecurityDefinitionState> existing =
        repository.inTransaction(tx -> tx.existingSecurityDefinition(marketId));
    assertThat(existing).isEmpty();
  }

  @Test
  void issueCreditsTargetAndIsIdempotentByRequest() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    createOpen(actor, 1000, 1);

    service.issue(actor, request, marketId, owner, 100, "initial grant");

    SecurityBalance balance =
        repository.inTransaction(tx -> tx.securityBalance(owner, marketId));
    assertThat(balance.availableQuantity()).isEqualTo(100);
    long issued = repository.inTransaction(tx -> tx.securityDefinition(marketId).issuedSupply());
    assertThat(issued).isEqualTo(100);
    List<SecurityLedgerEntry> ledger =
        repository.inTransaction(tx -> tx.securityLedger(marketId, owner));
    assertThat(ledger).hasSize(1);

    SecurityMutationResult replayed =
        service.issue(actor, request, marketId, owner, 100, "initial grant");
    assertThat(replayed.replayed()).isTrue();
    SecurityBalance after =
        repository.inTransaction(tx -> tx.securityBalance(owner, marketId));
    assertThat(after.availableQuantity()).isEqualTo(100);
  }

  @Test
  void issueRejectsOverIssuanceAndBadUnit() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    createOpen(actor, 100, 10);

    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, owner, 15, "invalid unit"))
        .hasMessageContaining("multiple of minimum unit");
    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, owner, 200, "over issuance"))
        .hasMessageContaining("insufficient unissued supply");
    long issued = repository.inTransaction(tx -> tx.securityDefinition(marketId).issuedSupply());
    assertThat(issued).isZero();
  }

  @Test
  void transferMovesAvailableStockAndIsIdempotentByRequest() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    createOpen(actor, 1000, 1);
    service.issue(actor, UUID.randomUUID(), marketId, first, 100, "first allocation");

    UUID transferredRequest = UUID.randomUUID();
    SecurityMutationResult transferred = service.transfer(
        actor, transferredRequest, marketId, first, second, 40, "correct allocation");

    assertThat(transferred.action()).isEqualTo("STOCK_TRANSFER");
    SecurityBalance firstAfter =
        repository.inTransaction(tx -> tx.securityBalance(first, marketId));
    SecurityBalance secondAfter =
        repository.inTransaction(tx -> tx.securityBalance(second, marketId));
    assertThat(firstAfter.availableQuantity()).isEqualTo(60);
    assertThat(secondAfter.availableQuantity()).isEqualTo(40);

    SecurityMutationResult replayAgain = service.transfer(
        actor, transferredRequest, marketId, first, second, 40, "correct allocation");
    assertThat(replayAgain.replayed()).isTrue();
    SecurityBalance afterReplay =
        repository.inTransaction(tx -> tx.securityBalance(second, marketId));
    assertThat(afterReplay.availableQuantity()).isEqualTo(40);
  }

  @Test
  void transferRejectsInsufficientSourceAndBadUnit() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    createOpen(actor, 1000, 10);
    service.issue(actor, UUID.randomUUID(), marketId, first, 100, "first allocation");

    assertThatThrownBy(() -> service.transfer(
        actor, UUID.randomUUID(), marketId, first, second, 15, "invalid transfer unit"))
        .hasMessageContaining("multiple of minimum unit");
    assertThatThrownBy(() -> service.transfer(
        actor, UUID.randomUUID(), marketId, first, second, 150, "over transfer"))
        .hasMessageContaining("insufficient available balance");
    assertThatThrownBy(() -> service.transfer(
        actor, UUID.randomUUID(), marketId, first, first, 10, "same account transfer"))
        .hasMessageContaining("must differ");
    SecurityBalance firstAfter =
        repository.inTransaction(tx -> tx.securityBalance(first, marketId));
    assertThat(firstAfter.availableQuantity()).isEqualTo(100);
  }

  @Test
  void pauseAndResumeEnforceStateTransitions() throws Exception {
    UUID actor = UUID.randomUUID();
    createOpen(actor, 100, 1);

    service.pause(actor, UUID.randomUUID(), marketId, "temporary halt");
    String paused = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(paused).isEqualTo("PAUSED");
    MarketStatus pausedState =
        repository.inTransaction(tx -> tx.marketState(marketId).status());
    assertThat(pausedState).isEqualTo(MarketStatus.PAUSED);
    assertThatThrownBy(() -> service.pause(
        actor, UUID.randomUUID(), marketId, "second pause"))
        .isInstanceOf(IllegalStateException.class);

    service.resume(actor, UUID.randomUUID(), marketId, "resume trading");
    String open = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(open).isEqualTo("OPEN");
    MarketStatus resumedState =
        repository.inTransaction(tx -> tx.marketState(marketId).status());
    assertThat(resumedState).isEqualTo(MarketStatus.OPEN);
    assertThatThrownBy(() -> service.resume(
        actor, UUID.randomUUID(), marketId, "resume again"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closeRejectsMarketWithOpenOrders() throws Exception {
    UUID actor = UUID.randomUUID();
    createOpen(actor, 100, 1);
    insertOpenOrder(actor);

    assertThatThrownBy(() -> service.close(
        actor, UUID.randomUUID(), marketId, UUID.randomUUID(), "close the stock"))
        .hasMessageContaining("no open orders");
    String status = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(status).isEqualTo("OPEN");
  }

  @Test
  void closeRecoversAllOutstandingBalancesAndBlocksFurtherIssuance() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID recovery = UUID.randomUUID();
    createOpen(actor, 1000, 1);
    service.issue(actor, UUID.randomUUID(), marketId, first, 100, "first allocation");
    service.issue(actor, UUID.randomUUID(), marketId, second, 50, "second allocation");
    repository.inTransaction(tx -> {
      tx.freezeSecurity(first, marketId, 30);
      return null;
    });

    SecurityMutationResult closed = service.close(
        actor, UUID.randomUUID(), marketId, recovery, "close the stock");

    assertThat(closed.status()).isEqualTo("CLOSED");
    SecurityBalance firstBalance =
        repository.inTransaction(tx -> tx.securityBalance(first, marketId));
    SecurityBalance secondBalance =
        repository.inTransaction(tx -> tx.securityBalance(second, marketId));
    SecurityBalance recoveryBalance =
        repository.inTransaction(tx -> tx.securityBalance(recovery, marketId));
    assertThat(firstBalance.availableQuantity()).isZero();
    assertThat(firstBalance.frozenQuantity()).isZero();
    assertThat(secondBalance.availableQuantity()).isZero();
    assertThat(secondBalance.frozenQuantity()).isZero();
    assertThat(recoveryBalance.availableQuantity()).isEqualTo(150);
    String status = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(status).isEqualTo("CLOSED");
    MarketStatus closedState =
        repository.inTransaction(tx -> tx.marketState(marketId).status());
    assertThat(closedState).isEqualTo(MarketStatus.CLOSED);

    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, first, 1, "issue after close"))
        .isInstanceOf(IllegalStateException.class);
  }

  private void createOpen(UUID actor, long totalSupply, long minimumUnit) throws Exception {
    service.create(actor, UUID.randomUUID(), marketId, "ALPHA", "Alpha", "Concept stock",
        "default", new BigDecimal("10.00"), totalSupply, minimumUnit);
  }

  private void insertOpenOrder(UUID owner) throws Exception {
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.orders()
          + " (order_id,request_id,market_id,account_id,side,order_type,time_in_force,"
          + "limit_price,original_quantity,remaining_quantity,status,priority_sequence,config_version,"
          + "fee_version,reserved_currency,reserved_quantity,created_at,updated_at,version)"
          + " VALUES ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "','" + marketId
          + "','" + owner
          + "','SELL','LIMIT','GTC','10.00',10,10,'OPEN',1,1,1,'0.00',10,1,1,0)");
    }
  }
}
