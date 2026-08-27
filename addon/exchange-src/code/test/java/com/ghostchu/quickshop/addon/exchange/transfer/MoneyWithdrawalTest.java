package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.CurrencyBalance;
import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyWithdrawalTest {
  @Test
  void consumesReservationAfterSuccessfulPayout() throws Exception {
    AtomicInteger payouts = new AtomicInteger();
    try (Context context = funded(economy((player, currency, amount) -> {
      payouts.incrementAndGet();
      return ExternalResult.SUCCESS;
    }))) {
      UUID request = UUID.randomUUID();

      TransferRecord result = context.money().withdraw(
          request, context.player(), "USD", new BigDecimal("40.00")).join();
      TransferRecord duplicate = context.money().withdraw(
          request, context.player(), "USD", new BigDecimal("40.0")).join();

      assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
      assertThat(duplicate.transferId()).isEqualTo(result.transferId());
      assertThat(payouts).hasValue(1);
      assertThat(context.balance().available()).isEqualByComparingTo("60.00");
      assertThat(context.balance().frozen()).isZero();
      assertThat(new ReconciliationService(context.repository()).run().balanced()).isTrue();
    }
  }

  @Test
  void releasesReservationOnExplicitFailure() throws Exception {
    try (Context context = funded(
        economy((player, currency, amount) -> ExternalResult.FAILURE))) {
      TransferRecord result = context.money().withdraw(
          UUID.randomUUID(), context.player(), "USD", new BigDecimal("40.00")).join();

      assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(context.balance().available()).isEqualByComparingTo("100.00");
      assertThat(context.balance().frozen()).isZero();
    }
  }

  @Test
  void keepsReservationWhenPayoutIsUnknownOrThrows() throws Exception {
    try (Context unknown = funded(
             economy((player, currency, amount) -> ExternalResult.UNKNOWN));
         Context thrown = funded(economy((player, currency, amount) -> {
           throw new IllegalStateException("provider unavailable");
         }))) {
      TransferRecord unknownResult = unknown.money().withdraw(
          UUID.randomUUID(), unknown.player(), "USD", new BigDecimal("40.00")).join();
      TransferRecord thrownResult = thrown.money().withdraw(
          UUID.randomUUID(), thrown.player(), "USD", new BigDecimal("40.00")).join();

      assertThat(unknownResult.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(thrownResult.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(unknown.balance().available()).isEqualByComparingTo("60.00");
      assertThat(unknown.balance().frozen()).isEqualByComparingTo("40.00");
      assertThat(thrown.balance().available()).isEqualByComparingTo("60.00");
      assertThat(thrown.balance().frozen()).isEqualByComparingTo("40.00");
    }
  }

  private static EconomyGateway economy(PayoutBehavior payout) {
    return new EconomyGateway() {
      @Override
      public ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount) {
        return payout.apply(playerId, currencyId, amount);
      }
    };
  }

  private static Context funded(EconomyGateway economy) throws Exception {
    Path file = Files.createTempFile("quickshop-exchange-money-withdrawal-", ".sqlite");
    file.toFile().deleteOnExit();
    ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
    TableNames tables = new TableNames("withdrawal_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    UUID player = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-27T00:00:00Z");
    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(player, "USD", new BigDecimal("100.00"));
      TransferRecord funding = TransferRecord.prepared(
          UUID.randomUUID(), UUID.randomUUID(), player, TransferType.MONEY_DEPOSIT,
          "USD", new BigDecimal("100.00"), now);
      tx.appendJournal(TransferJournals.moneyDeposit(funding, now));
      return null;
    });
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser();
    MoneyTransferService money = new MoneyTransferService(
        repository, repository, economy, serialiser,
        Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
    return new Context(player, repository, serialiser, money);
  }

  @FunctionalInterface
  private interface PayoutBehavior {
    ExternalResult apply(UUID playerId, String currencyId, BigDecimal amount);
  }

  private record Context(UUID player, JdbcExchangeRepository repository,
                         PlayerOperationSerialiser serialiser,
                         MoneyTransferService money) implements AutoCloseable {
    private CurrencyBalance balance() throws Exception {
      return repository.inTransaction(tx -> tx.currency(player, "USD"));
    }

    @Override
    public void close() {
      serialiser.close();
    }
  }
}
