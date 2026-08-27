package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MySqlRepositoryIT {
  @Container
  private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
      .withCommand("--log-bin-trust-function-creators=1");

  @Test
  void concurrentDuplicateRequestCreatesOneOrderAndOneResult() throws Exception {
    ExchangeServiceFixture fixture = mysqlFixture("duplicate_", TestFixtures.rules());
    UUID buyer = fixture.accountWithCurrency("1000.00");
    UUID requestId = UUID.randomUUID();
    OrderRequest request = limitOrder(
        requestId, buyer, fixture.rules().marketId(), OrderSide.BUY, "100.00");
    List<PersistentOrderService> services = IntStream.range(0, 32)
        .mapToObj(ignored -> fixture.independentMysqlService())
        .toList();

    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      List<Future<OrderReceipt>> attempts = IntStream.range(0, 32)
          .mapToObj(index -> executor.submit(() -> services.get(index).place(request)))
          .toList();
      for (Future<OrderReceipt> attempt : attempts) {
        assertThat(attempt.get(20, TimeUnit.SECONDS).requestId()).isEqualTo(requestId);
      }
    }

    assertThat(fixture.orderCountForRequest(requestId)).isEqualTo(1);
    assertThat(fixture.requestResultCount(requestId)).isEqualTo(1);
  }

  @Test
  void opposingCrossMarketTradesCompleteWithoutDeadlock() throws Exception {
    MarketRules firstRules = TestFixtures.rules();
    MarketRules secondRules = new MarketRules(
        "emerald-usd", firstRules.currencyId(), firstRules.basePrice(), firstRules.minPrice(),
        firstRules.maxPrice(), firstRules.tickSize(), firstRules.minQuantity(),
        firstRules.maxQuantity(), firstRules.priceScale(), firstRules.makerFeeRate(),
        firstRules.takerFeeRate());
    ConnectionProvider connections = connections();
    TableNames tables = new TableNames("locks_");
    ExchangeServiceFixture first = ExchangeServiceFixture.mysql(connections, tables, firstRules);
    ExchangeServiceFixture second = ExchangeServiceFixture.mysql(connections, tables, secondRules);
    UUID accountA = first.accountWithCurrency("1000.00");
    UUID accountB = first.accountWithCurrency("1000.00");
    second.creditItems(accountA, 1);
    first.creditItems(accountB, 1);
    first.service().place(limitOrder(
        UUID.randomUUID(), accountB, firstRules.marketId(), OrderSide.SELL, "100.00"));
    second.service().place(limitOrder(
        UUID.randomUUID(), accountA, secondRules.marketId(), OrderSide.SELL, "100.00"));
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<OrderReceipt> firstTrade = executor.submit(() -> {
        start.await();
        return first.service().place(limitOrder(
            UUID.randomUUID(), accountA, firstRules.marketId(), OrderSide.BUY, "100.00"));
      });
      Future<OrderReceipt> secondTrade = executor.submit(() -> {
        start.await();
        return second.service().place(limitOrder(
            UUID.randomUUID(), accountB, secondRules.marketId(), OrderSide.BUY, "100.00"));
      });
      start.countDown();
      assertThat(firstTrade.get(20, TimeUnit.SECONDS).trades()).hasSize(1);
      assertThat(secondTrade.get(20, TimeUnit.SECONDS).trades()).hasSize(1);
    }

    assertThat(first.tradeCount()).isEqualTo(2);
    assertThat(first.journalInvariantViolations()).isEmpty();
  }

  private ExchangeServiceFixture mysqlFixture(String prefix, MarketRules rules) throws Exception {
    return ExchangeServiceFixture.mysql(connections(), new TableNames(prefix), rules);
  }

  private ConnectionProvider connections() {
    return () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
  }

  private static OrderRequest limitOrder(
      UUID requestId, UUID accountId, String marketId, OrderSide side, String price) {
    return new OrderRequest(requestId, accountId, marketId, side,
        "LIMIT", new BigDecimal(price), null, 1);
  }
}
