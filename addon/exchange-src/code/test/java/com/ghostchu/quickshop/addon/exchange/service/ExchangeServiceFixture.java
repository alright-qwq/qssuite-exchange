package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExchangeServiceFixture {
  private final ConnectionProvider connections;
  private final TableNames tables;
  private final JdbcExchangeRepository repository;
  private final PersistentOrderService service;
  private final MarketRules rules;

  private ExchangeServiceFixture(ConnectionProvider connections, TableNames tables,
                                 JdbcExchangeRepository repository,
                                 PersistentOrderService service, MarketRules rules) {
    this.connections = connections;
    this.tables = tables;
    this.repository = repository;
    this.service = service;
    this.rules = rules;
  }

  public static ExchangeServiceFixture sqlite() throws Exception {
    return sqlite(RecoveryHandler.NO_OP);
  }

  static ExchangeServiceFixture sqlite(RecoveryHandler recovery) throws Exception {
    return sqlite(TestFixtures.rules(), recovery);
  }

  static ExchangeServiceFixture sqliteWithFees(String makerFee, String takerFee) throws Exception {
    MarketRules defaults = TestFixtures.rules();
    MarketRules rules = new MarketRules(defaults.marketId(), defaults.currencyId(),
        defaults.basePrice(), defaults.minPrice(), defaults.maxPrice(), defaults.tickSize(),
        defaults.minQuantity(), defaults.maxQuantity(), defaults.priceScale(),
        new BigDecimal(makerFee), new BigDecimal(takerFee));
    return sqlite(rules, RecoveryHandler.NO_OP);
  }

  private static ExchangeServiceFixture sqlite(MarketRules rules, RecoveryHandler recovery)
      throws Exception {
    Path database = Files.createTempFile("quickshop-exchange-service-", ".sqlite");
    database.toFile().deleteOnExit();
    ConnectionProvider connections = new SqliteConnectionProvider(
        () -> DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    seedMarket(connections, tables, rules);
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        recovery);
    return new ExchangeServiceFixture(connections, tables, repository, service, rules);
  }

  static ExchangeServiceFixture mysql(
      ConnectionProvider connections, TableNames tables, MarketRules rules) throws Exception {
    new MigrationRunner(connections, SqlDialect.MYSQL, tables).migrate();
    seedMarket(connections, tables, rules);
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.MYSQL, tables);
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
    return new ExchangeServiceFixture(connections, tables, repository, service, rules);
  }

  public PersistentOrderService service() {
    return service;
  }

  PersistentOrderService serviceWithAccountLimits(
      com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits limits) {
    return new PersistentOrderService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, limits);
  }

  PersistentOrderService independentMysqlService() {
    return new PersistentOrderService(
        new JdbcExchangeRepository(connections, SqlDialect.MYSQL, tables), rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
  }

  PersistentOrderService restartedService() {
    return new PersistentOrderService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
  }

  PersistentOrderService isolatedRestartedService() {
    Object isolatedKey = new Object();
    ExchangeRepository isolated = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        return repository.inTransaction(work);
      }

      @Override
      public Object coordinationKey() {
        return isolatedKey;
      }
    };
    return new PersistentOrderService(isolated, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
  }

  OrderBookRecoveryService recovery() {
    return new OrderBookRecoveryService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults());
  }

  PersistentOrderService service(
      SettlementObserver observer, RecoveryHandler recovery) {
    return new PersistentOrderService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        recovery, observer);
  }

  PersistentOrderService serviceWithRollbackSuppression(
      SettlementObserver observer, SQLException rollbackFailure) {
    ExchangeRepository rollbackReporting = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        try {
          return repository.inTransaction(work);
        } catch (RuntimeException failure) {
          failure.addSuppressed(rollbackFailure);
          throw failure;
        }
      }

      @Override
      public Object coordinationKey() {
        return repository.coordinationKey();
      }

      @Override
      public java.util.Optional<StoredRequestResult> findRequestResult(
          UUID accountId, UUID requestId) throws SQLException {
        return repository.findRequestResult(accountId, requestId);
      }
    };
    return new PersistentOrderService(rollbackReporting, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, observer);
  }

  public JdbcExchangeRepository repository() {
    return repository;
  }

  public MarketRules rules() {
    return rules;
  }

  PersistentOrderService serviceWithTransactionEntry(Runnable onEntry) {
    ExchangeRepository observed = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        onEntry.run();
        return repository.inTransaction(work);
      }

      @Override
      public Object coordinationKey() {
        return repository.coordinationKey();
      }

      @Override
      public java.util.Optional<StoredRequestResult> findRequestResult(
          UUID accountId, UUID requestId) throws SQLException {
        return repository.findRequestResult(accountId, requestId);
      }
    };
    return new PersistentOrderService(observed, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
  }

  PersistentOrderService serviceWithReportedCommitFailure(RecoveryHandler recovery) {
    AtomicBoolean failOnce = new AtomicBoolean(true);
    ExchangeRepository uncertainCommit = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        T result = repository.inTransaction(work);
        if (failOnce.compareAndSet(true, false)) {
          throw new SQLException("reported failure after commit");
        }
        return result;
      }

      @Override
      public Object coordinationKey() {
        return repository.coordinationKey();
      }
    };
    return new PersistentOrderService(uncertainCommit, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(), recovery);
  }

  public PersistentOrderService serviceWithMarketData(
      com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService marketData) {
    return new PersistentOrderService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP, marketData);
  }

  public UUID accountWithItems(long quantity) throws SQLException {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableItems(account, rules.marketId(), quantity);
      return null;
    });
    return account;
  }

  void creditItems(UUID account, long quantity) throws SQLException {
    repository.inTransaction(tx -> {
      tx.creditAvailableItems(account, rules.marketId(), quantity);
      return null;
    });
  }

  public UUID accountWithCurrency(String amount) throws SQLException {
    UUID account = UUID.randomUUID();
    creditCurrency(account, amount);
    return account;
  }

  void creditCurrency(UUID account, String amount) throws SQLException {
    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, rules.currencyId(),
          new BigDecimal(amount));
      return null;
    });
  }

  public long tradeCount() throws SQLException {
    return rowCount(tables.trades());
  }

  public String orderStatus(UUID orderId) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT status FROM " + tables.orders() + " WHERE order_id=?")) {
      query.setString(1, orderId.toString());
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("order missing: " + orderId);
        }
        return result.getString("status");
      }
    }
  }

  MarketRegistry marketRegistry() {
    return new MarketRegistry(Map.of(rules.marketId(), marketDefinition(
        rules.tickSize().toPlainString(), rules.makerFeeRate().toPlainString(),
        rules.takerFeeRate().toPlainString())), repository);
  }

  MarketDefinition marketDefinition(String tickSize, String makerFee, String takerFee) {
    return marketDefinition(tickSize, makerFee, takerFee, rules.priceScale());
  }

  MarketDefinition marketDefinition(
      String tickSize, String makerFee, String takerFee, int currencyScale) {
    return new MarketDefinition(rules.marketId(), "Diamond / USD", true,
        new MarketDefinition.ItemDefinition(
            FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        new MarketDefinition.StructuralRules(rules.currencyId(), rules.basePrice(),
            rules.minPrice(), rules.maxPrice(), new BigDecimal(tickSize), rules.priceScale(),
            currencyScale, rules.minQuantity(), rules.maxQuantity(), 100),
        new MarketDefinition.RiskRules(new BigDecimal(makerFee), new BigDecimal(takerFee),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
            new BigDecimal("10000000.00"), 100, 5, 60), false);
  }

  void archiveFeeVersion(long feeVersion) throws SQLException {
    repository.archiveFeeVersion(rules.marketId(), feeVersion);
  }

  BigDecimal lastTradeMakerFee() throws SQLException {
    return lastTradeValue("maker_fee");
  }

  BigDecimal lastTradeTakerFee() throws SQLException {
    return lastTradeValue("taker_fee");
  }

  long latestOrderFeeVersion() throws SQLException {
    return latestOrderVersion("fee_version");
  }

  long latestOrderConfigVersion() throws SQLException {
    return latestOrderVersion("config_version");
  }

  private long latestOrderVersion(String column) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT " + column + " FROM " + tables.orders()
                 + " ORDER BY created_at DESC, priority_sequence DESC LIMIT 1");
         ResultSet result = query.executeQuery()) {
      if (!result.next()) {
        throw new SQLException("order missing");
      }
      return result.getLong(1);
    }
  }

  long orderCount() throws SQLException {
    return rowCount(tables.orders());
  }

  long orderCountForRequest(UUID requestId) throws SQLException {
    return rowCountFor(tables.orders(), "request_id", requestId);
  }

  long requestResultCount(UUID requestId) throws SQLException {
    return rowCountFor(tables.requestResults(), "request_id", requestId);
  }

  void insertUnjournaledTrade() throws SQLException {
    long sequence = marketMatchSequence() + 1;
    repository.inTransaction(tx -> {
      tx.insertTrade(new Trade(
          UUID.randomUUID(), rules.marketId(), UUID.randomUUID(), UUID.randomUUID(),
          UUID.randomUUID(), UUID.randomUUID(), rules.basePrice(), 1,
          BigDecimal.ZERO, BigDecimal.ZERO, sequence, Instant.now()));
      return null;
    });
  }

  private long rowCount(String table) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + table);
         ResultSet result = query.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private BigDecimal lastTradeValue(String column) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT " + column + " FROM " + tables.trades()
                 + " ORDER BY executed_at DESC, match_sequence DESC LIMIT 1");
         ResultSet result = query.executeQuery()) {
      if (!result.next()) {
        throw new SQLException("trade missing");
      }
      return new BigDecimal(result.getString(1));
    }
  }

  private long rowCountFor(String table, String column, UUID value) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
      query.setString(1, value.toString());
      try (ResultSet result = query.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  boolean ledgerIsBalanced() throws SQLException {
    Map<String, BigDecimal> totals = new HashMap<>();
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT asset_id,amount FROM " + tables.entries());
         ResultSet result = query.executeQuery()) {
      while (result.next()) {
        totals.merge(result.getString("asset_id"),
            new BigDecimal(result.getString("amount")), BigDecimal::add);
      }
    }
    return totals.values().stream().allMatch(total -> total.compareTo(BigDecimal.ZERO) == 0);
  }

  BigDecimal feeAccountBalance() throws SQLException {
    return repository.inTransaction(tx -> tx.currency(
        PersistentOrderService.FEE_ACCOUNT_ID, rules.currencyId()).available());
  }

  public BigDecimal availableCurrency(UUID account) throws SQLException {
    return repository.inTransaction(
        tx -> tx.currency(account, rules.currencyId()).available());
  }

  public BigDecimal frozenCurrency(UUID account) throws SQLException {
    return repository.inTransaction(
        tx -> tx.currency(account, rules.currencyId()).frozen());
  }

  public long availableItems(UUID account) throws SQLException {
    return repository.inTransaction(tx -> tx.inventory(account, rules.marketId()).availableQuantity());
  }

  public long frozenItems(UUID account) throws SQLException {
    return repository.inTransaction(tx -> tx.inventory(account, rules.marketId()).frozenQuantity());
  }

  boolean hasCurrencyBalance(UUID account) throws SQLException {
    return hasBalanceRow(tables.accounts(), "currency_id", rules.currencyId(), account);
  }

  boolean hasInventoryBalance(UUID account) throws SQLException {
    return hasBalanceRow(tables.inventory(), "market_id", rules.marketId(), account);
  }

  private boolean hasBalanceRow(String table, String assetColumn, String assetId, UUID account)
      throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + table + " WHERE account_id=? AND " + assetColumn + "=?")) {
      query.setString(1, account.toString());
      query.setString(2, assetId);
      try (ResultSet result = query.executeQuery()) {
        result.next();
        return result.getLong(1) == 1;
      }
    }
  }

  void setMarketStatus(String status) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState() + " SET status=? WHERE market_id=?")) {
      update.setString(1, status);
      update.setString(2, rules.marketId());
      update.executeUpdate();
    }
  }

  void setMarketReferencePrice(String referencePrice) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState() + " SET reference_price=? WHERE market_id=?")) {
      update.setString(1, referencePrice);
      update.setString(2, rules.marketId());
      update.executeUpdate();
    }
  }

  long marketPrioritySequence() throws SQLException {
    return Long.parseLong(marketValue("priority_sequence"));
  }

  long marketMatchSequence() throws SQLException {
    return Long.parseLong(marketValue("match_sequence"));
  }

  long marketVersion() throws SQLException {
    return Long.parseLong(marketValue("version"));
  }

  void resumeMarket() throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState()
                 + " SET status='OPEN',halted_until=NULL,version=version+1 WHERE market_id=?")) {
      update.setString(1, rules.marketId());
      update.executeUpdate();
    }
  }

  long highAlertCount() throws SQLException {
    return highAlertCount("CIRCUIT_BREAKER_LEVEL_2");
  }

  public long reconciliationAlertCount() throws SQLException {
    return highAlertCount("RECONCILIATION_DIFFERENCE");
  }

  private long highAlertCount(String alertType) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + tables.auditAlerts()
                 + " WHERE severity='HIGH' AND alert_type=?")) {
      query.setString(1, alertType);
      try (ResultSet result = query.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  void storeRequestResult(UUID account, UUID request, String operation, String payload)
      throws SQLException {
    repository.inTransaction(tx -> {
      tx.putRequestResult(new StoredRequestResult(account, request, operation, payload));
      return null;
    });
  }

  void failTradeInserts() throws SQLException {
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER fail_exchange_trade_insert BEFORE INSERT ON "
          + tables.trades() + " BEGIN SELECT RAISE(ABORT,'forced trade insert failure'); END");
    }
  }

  public String marketStatus() throws SQLException {
    return marketValue("status");
  }

  BigDecimal marketReferencePrice() throws SQLException {
    return new BigDecimal(marketValue("reference_price"));
  }

  BigDecimal marketLastPrice() throws SQLException {
    return new BigDecimal(marketValue("last_price"));
  }

  Long marketHaltedUntil() throws SQLException {
    String value = marketValue("halted_until");
    return value == null ? null : Long.valueOf(value);
  }

  String marketDiscoveryQuantity() throws SQLException {
    return marketValue("discovery_quantity");
  }

  String marketCircuitBreakerLevel() throws SQLException {
    return marketValue("circuit_breaker_level");
  }

  void clearMarketRiskMetadata() throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState()
                 + " SET discovery_quantity=NULL,circuit_breaker_level=NULL WHERE market_id=?")) {
      update.setString(1, rules.marketId());
      update.executeUpdate();
    }
  }

  void setMarketPrioritySequence(long sequence) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState()
                 + " SET priority_sequence=? WHERE market_id=?")) {
      update.setLong(1, sequence);
      update.setString(2, rules.marketId());
      update.executeUpdate();
    }
  }

  void setMarketSequences(long prioritySequence, long matchSequence) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState()
                 + " SET priority_sequence=?,match_sequence=? WHERE market_id=?")) {
      update.setLong(1, prioritySequence);
      update.setLong(2, matchSequence);
      update.setString(3, rules.marketId());
      update.executeUpdate();
    }
  }

  Set<String> journalAccountKinds() throws SQLException {
    Set<String> kinds = new HashSet<>();
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT account_code FROM " + tables.entries());
         ResultSet result = query.executeQuery()) {
      while (result.next()) {
        String code = result.getString(1);
        if (code.startsWith("liability:currency:")) {
          kinds.add(kinds.contains("buyer-currency") ? "seller-currency" : "buyer-currency");
        } else if (code.startsWith("liability:fee:")) {
          kinds.add("fee-currency");
        } else if (code.startsWith("custody:currency:")) {
          kinds.add("currency-custody");
        } else if (code.startsWith("liability:item:")) {
          kinds.add(kinds.contains("seller-item") ? "buyer-item" : "seller-item");
        } else if (code.startsWith("custody:item:")) {
          kinds.add("item-custody");
        }
      }
    }
    return Set.copyOf(kinds);
  }

  DatabaseState databaseState() throws SQLException {
    LinkedHashMap<String, List<List<String>>> tablesState = new LinkedHashMap<>();
    for (String table : List.of(
        tables.accounts(), tables.inventory(), tables.orders(), tables.trades(),
        tables.journals(), tables.entries(), tables.requestResults(), tables.auditAlerts())) {
      tablesState.put(table, tableRows(table));
    }
    return new DatabaseState(Map.copyOf(tablesState), List.of(
        marketValue("priority_sequence"), marketValue("match_sequence"),
        marketValue("reference_price"), snapshotValue(marketValue("last_price")),
        snapshotValue(marketValue("halted_until")), marketValue("discovery_quantity"),
        marketValue("circuit_breaker_level")));
  }

  List<String> journalInvariantViolations() throws SQLException {
    ArrayList<String> violations = new ArrayList<>();
    LinkedHashMap<String, TradeAudit> trades = new LinkedHashMap<>();
    LinkedHashMap<String, JournalAudit> journals = new LinkedHashMap<>();
    try (Connection connection = connections.open()) {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT trade_id,buyer_account_id,seller_account_id FROM " + tables.trades());
           ResultSet result = query.executeQuery()) {
        while (result.next()) {
          trades.put(result.getString("trade_id"), new TradeAudit(
              result.getString("buyer_account_id"), result.getString("seller_account_id")));
        }
      }
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT journal_id,journal_type,reference_id FROM " + tables.journals());
           ResultSet result = query.executeQuery()) {
        while (result.next()) {
          String journalId = result.getString("journal_id");
          String type = result.getString("journal_type");
          if (!type.startsWith("TRADE_")) {
            continue;
          }
          String tradeId = result.getString("reference_id");
          TradeAudit trade = trades.get(tradeId);
          if (trade == null) {
            violations.add(journalId + ": orphan trade journal");
            continue;
          }
          trade.addJournal(type, journalId, tradeId, violations);
          journals.put(journalId, new JournalAudit(type, trade.buyer, trade.seller));
        }
      }
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT journal_id,account_code,asset_id,amount FROM " + tables.entries());
           ResultSet result = query.executeQuery()) {
        while (result.next()) {
          JournalAudit journal = journals.get(result.getString("journal_id"));
          if (journal != null) {
            journal.add(result.getString("account_code"), result.getString("asset_id"),
                new BigDecimal(result.getString("amount")));
          }
        }
      }
    }
    trades.forEach((id, trade) -> trade.validate(id, violations));
    journals.forEach((id, journal) -> journal.validate(id, rules, violations));
    return List.copyOf(violations);
  }

  private List<List<String>> tableRows(String table) throws SQLException {
    ArrayList<List<String>> rows = new ArrayList<>();
    try (Connection connection = connections.open();
         Statement query = connection.createStatement();
         ResultSet result = query.executeQuery("SELECT * FROM " + table)) {
      ResultSetMetaData metadata = result.getMetaData();
      while (result.next()) {
        ArrayList<String> row = new ArrayList<>(metadata.getColumnCount());
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
          row.add(snapshotValue(result.getString(column)));
        }
        rows.add(List.copyOf(row));
      }
    }
    rows.sort(java.util.Comparator.comparing(Object::toString));
    return List.copyOf(rows);
  }

  private static String snapshotValue(String value) {
    return value == null ? "<SQL NULL>" : value;
  }

  private String marketValue(String column) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT " + column + " FROM " + tables.marketState() + " WHERE market_id=?")) {
      query.setString(1, rules.marketId());
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("market state missing");
        }
        return result.getString(1);
      }
    }
  }

  record DatabaseState(Map<String, List<List<String>>> tables, List<String> marketValues) {}

  private static final class TradeAudit {
    private static final Set<String> REQUIRED_JOURNALS =
        Set.of("TRADE_CURRENCY", "TRADE_ITEM");

    private final String buyer;
    private final String seller;
    private final Map<String, String> journals = new LinkedHashMap<>();

    private TradeAudit(String buyer, String seller) {
      this.buyer = buyer;
      this.seller = seller;
    }

    private void addJournal(
        String type, String journalId, String tradeId, List<String> violations) {
      String duplicate = journals.putIfAbsent(type, journalId);
      if (duplicate != null) {
        violations.add(tradeId + ": duplicate " + type + " journals");
      }
    }

    private void validate(String tradeId, List<String> violations) {
      if (!journals.keySet().equals(REQUIRED_JOURNALS)) {
        violations.add(tradeId + ": missing trade journals");
      }
    }
  }

  private static final class JournalAudit {
    private final String type;
    private final String buyer;
    private final String seller;
    private final List<JournalEntryAudit> entries = new ArrayList<>();

    private JournalAudit(String type, String buyer, String seller) {
      this.type = type;
      this.buyer = buyer;
      this.seller = seller;
    }

    private void add(String account, String asset, BigDecimal amount) {
      entries.add(new JournalEntryAudit(account, asset, amount));
    }

    private void validate(String journalId, MarketRules rules, List<String> violations) {
      boolean currency = type.equals("TRADE_CURRENCY");
      if (!currency && !type.equals("TRADE_ITEM")) {
        violations.add(journalId + ": unsupported journal type " + type);
        return;
      }
      String asset = currency ? rules.currencyId() : rules.marketId();
      Set<String> expectedAccounts = currency
          ? Set.of("liability:currency:" + buyer, "liability:currency:" + seller,
              "liability:fee:" + PersistentOrderService.FEE_ACCOUNT_ID,
              "custody:currency:" + rules.currencyId())
          : Set.of("liability:item:" + seller, "liability:item:" + buyer,
              "custody:item:" + rules.marketId());
      Set<String> actualAccounts = entries.stream().map(JournalEntryAudit::account)
          .collect(java.util.stream.Collectors.toSet());
      boolean assetsMatch = entries.stream().allMatch(entry -> entry.asset().equals(asset));
      BigDecimal total = entries.stream().map(JournalEntryAudit::amount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (entries.size() != expectedAccounts.size() || !expectedAccounts.equals(actualAccounts)
          || !assetsMatch || total.signum() != 0) {
        violations.add(journalId + ": roles/assets/conservation mismatch");
      }
    }
  }

  private record JournalEntryAudit(String account, String asset, BigDecimal amount) {}

  private static void seedMarket(ConnectionProvider connections, TableNames tables,
                                 MarketRules rules) throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try (PreparedStatement market = connection.prepareStatement(
          "INSERT INTO " + tables.markets()
              + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
              + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)");
           PreparedStatement state = connection.prepareStatement(
               "INSERT INTO " + tables.marketState()
                   + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                   + "last_price,halted_until,discovery_quantity,circuit_breaker_level,version)"
                   + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
        market.setString(1, rules.marketId());
        market.setString(2, rules.currencyId());
        market.setString(3, "diamond");
        market.setString(4, "{}");
        market.setString(5, "{}");
        market.setString(6, "{\"makerFeeRate\":\"" + rules.makerFeeRate().toPlainString()
            + "\",\"takerFeeRate\":\"" + rules.takerFeeRate().toPlainString()
            + "\",\"currencyScale\":" + rules.priceScale() + "}");
        market.setString(7, "{}");
        market.setLong(8, 1);
        market.setLong(9, 1);
        market.setLong(10, Instant.now().toEpochMilli());
        market.executeUpdate();

        state.setString(1, rules.marketId());
        state.setString(2, "OPEN");
        state.setLong(3, 0);
        state.setLong(4, 0);
        state.setString(5, rules.basePrice().toPlainString());
        state.setNull(6, java.sql.Types.DECIMAL);
        state.setNull(7, java.sql.Types.BIGINT);
        state.setLong(8, 0);
        state.setInt(9, 0);
        state.setLong(10, 0);
        state.executeUpdate();
        connection.commit();
      } catch (SQLException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
}
