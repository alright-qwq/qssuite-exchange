package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.security.SecurityService;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSecurityCommandTest {
  @Test
  void createsAndIssuesStockThroughAdminRouter() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");

    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});
    assertThat(actor.message).isEqualTo("request-accepted");

    UUID owner = UUID.randomUUID();
    fixture.router.execute(actor, new String[] {"stock", "issue", "alpha", owner.toString(),
        "100", "initial allocation"});
    assertThat(actor.message).isEqualTo("request-accepted");

    Long issued = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").issuedSupply());
    assertThat(issued).isEqualTo(100);
  }

  @Test
  void deniesStockCommandsWithoutDedicatedPermission() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.market");

    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void transfersStockBetweenAccountsThroughAdminRouter() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");
    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    fixture.router.execute(actor, new String[] {"stock", "issue", "alpha", first.toString(),
        "100", "initial allocation"});

    fixture.router.execute(actor, new String[] {"stock", "transfer", "alpha",
        first.toString(), second.toString(), "40", "correct allocation"});

    assertThat(actor.message).isEqualTo("request-accepted");
    Long firstAvailable = fixture.repository.inTransaction(
        tx -> tx.securityBalance(first, "alpha").availableQuantity());
    Long secondAvailable = fixture.repository.inTransaction(
        tx -> tx.securityBalance(second, "alpha").availableQuantity());
    assertThat(firstAvailable).isEqualTo(60);
    assertThat(secondAvailable).isEqualTo(40);
  }

  @Test
  void pausesResumesAndClosesStock() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");
    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});

    fixture.router.execute(actor, new String[] {"stock", "pause", "alpha", "temporary halt"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String paused = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(paused).isEqualTo("PAUSED");
    MarketStatus pausedMarket = fixture.repository.inTransaction(
        tx -> tx.marketState("alpha").status());
    assertThat(pausedMarket).isEqualTo(MarketStatus.PAUSED);

    fixture.router.execute(actor, new String[] {"stock", "resume", "alpha", "resume trading"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String open = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(open).isEqualTo("OPEN");
    MarketStatus openMarket = fixture.repository.inTransaction(
        tx -> tx.marketState("alpha").status());
    assertThat(openMarket).isEqualTo(MarketStatus.OPEN);

    UUID recovery = UUID.randomUUID();
    fixture.router.execute(actor, new String[] {"stock", "close", "alpha", recovery.toString(),
        "close the stock"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String closed = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(closed).isEqualTo("CLOSED");
    MarketStatus closedMarket = fixture.repository.inTransaction(
        tx -> tx.marketState("alpha").status());
    assertThat(closedMarket).isEqualTo(MarketStatus.CLOSED);
  }

  @Test
  void routesPlayerStocksAndStockDetailPages() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.use");

    router.execute(actor, new String[] {"stocks"});
    assertThat(actor.page).isEqualTo("markets");

    router.execute(actor, new String[] {"stock", "alpha"});
    assertThat(actor.page).isEqualTo("market-detail");
  }

  @Test
  void resolvesStockSymbolInAdminCommands() throws Exception {
    Fixture fixture = new Fixture(symbol -> "alpha".equalsIgnoreCase(symbol) ? "alpha" : null);
    Actor actor = new Actor("quickshop.exchange.admin.stock");
    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});

    fixture.router.execute(actor, new String[] {"stock", "pause", "ALPHA", "temporary halt"});

    assertThat(actor.message).isEqualTo("request-accepted");
    String paused = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(paused).isEqualTo("PAUSED");
  }

  @Test
  void resolvesUppercaseSymbolToCanonicalMarketIdWithoutConfiguredResolver() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");
    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});
    UUID owner = UUID.randomUUID();

    fixture.router.execute(actor, new String[] {"stock", "issue", "ALPHA", owner.toString(),
        "100", "initial allocation"});

    assertThat(actor.message).isEqualTo("request-accepted");
    Long issued = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").issuedSupply());
    assertThat(issued).isEqualTo(100);
  }

  private static final class Fixture {
    private final com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider connections;
    private final com.ghostchu.quickshop.addon.exchange.persistence.TableNames tables;
    private final com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository repository;
    private final AdminCommandRouter router;

    private Fixture() throws Exception {
      this(raw -> null);
    }

    private Fixture(java.util.function.Function<String, String> symbolToMarketId) throws Exception {
      java.nio.file.Path path = java.nio.file.Files.createTempFile("qs-command-stock-", ".db");
      path.toFile().deleteOnExit();
      connections = com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase.at(path);
      tables = new com.ghostchu.quickshop.addon.exchange.persistence.TableNames("qs_");
      new com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner(
          connections, com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect.SQLITE,
          tables).migrate();
      repository = new com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository(
          connections, com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect.SQLITE, tables);
      try (java.sql.Connection connection = connections.open();
           java.sql.Statement statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO " + tables.markets()
            + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
            + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
            + " VALUES ('alpha','default','','','{}','{}','{}',1,1,0)");
        statement.executeUpdate("INSERT INTO " + tables.marketState()
            + " (market_id,status,priority_sequence,match_sequence,reference_price,last_price,"
            + "halted_until,discovery_quantity,circuit_breaker_level,version)"
            + " VALUES ('alpha','OPEN',0,0,'10.00',NULL,NULL,0,0,0)");
      }
      router = new AdminCommandRouter(
          new AdminExchangeService(Map.of(), repository, null, null, new SecurityService(repository)),
          UUID::randomUUID,
          work -> {
            work.run();
            return true;
          }, Runnable::run, symbolToMarketId);
    }
  }

  private static final class Actor implements CommandActor {
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private String page;

    private Actor(String... permissions) {
      this.permissions.addAll(Set.of(permissions));
    }

    @Override public UUID accountId() { return accountId; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void message(String key, Object... arguments) { message = key; }
    @Override public void openMenu(String menuName, int page) { this.page = menuName; }
  }
}
