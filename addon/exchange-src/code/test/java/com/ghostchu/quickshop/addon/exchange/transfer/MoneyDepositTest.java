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

class MoneyDepositTest {
  @Test
  void debitsExternallyOnceThenCreditsInternalAccount() throws Exception {
    AtomicInteger debits = new AtomicInteger();
    EconomyGateway economy = economy((player, currency, amount) -> {
      debits.incrementAndGet();
      return ExternalResult.SUCCESS;
    });
    try (Context context = context(economy)) {
      UUID request = UUID.randomUUID();

      TransferRecord first = context.money().deposit(
          request, context.player(), "USD", new BigDecimal("25.00")).join();
      TransferRecord duplicate = context.money().deposit(
          request, context.player(), "USD", new BigDecimal("25.0")).join();

      assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
      assertThat(duplicate.transferId()).isEqualTo(first.transferId());
      assertThat(debits).hasValue(1);
      assertThat(context.availableCurrency()).isEqualByComparingTo("25.00");
      assertThat(new ReconciliationService(context.repository()).run().balanced()).isTrue();
    }
  }

  @Test
  void explicitFailureDoesNotCreditInternalAccount() throws Exception {
    try (Context context = context(economy((player, currency, amount) -> ExternalResult.FAILURE))) {
      TransferRecord result = context.money().deposit(
          UUID.randomUUID(), context.player(), "USD", new BigDecimal("25.00")).join();

      assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(context.availableCurrency()).isZero();
    }
  }

  @Test
  void unknownOrThrownDebitRequiresReviewAndDoesNotCredit() throws Exception {
    try (Context unknown = context(
             economy((player, currency, amount) -> ExternalResult.UNKNOWN));
         Context thrown = context(economy((player, currency, amount) -> {
           throw new IllegalStateException("provider unavailable");
         }))) {
      TransferRecord unknownResult = unknown.money().deposit(
          UUID.randomUUID(), unknown.player(), "USD", new BigDecimal("25.00")).join();
      TransferRecord thrownResult = thrown.money().deposit(
          UUID.randomUUID(), thrown.player(), "USD", new BigDecimal("25.00")).join();

      assertThat(unknownResult.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(thrownResult.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(unknown.availableCurrency()).isZero();
      assertThat(thrown.availableCurrency()).isZero();
    }
  }

  private static EconomyGateway economy(WithdrawBehavior withdraw) {
    return new EconomyGateway() {
      @Override
      public ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount) {
        return withdraw.apply(playerId, currencyId, amount);
      }

      @Override
      public ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static Context context(EconomyGateway economy) throws Exception {
    Path file = Files.createTempFile("quickshop-exchange-money-deposit-", ".sqlite");
    file.toFile().deleteOnExit();
    ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
    TableNames tables = new TableNames("money_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser();
    MoneyTransferService money = new MoneyTransferService(
        repository, repository, economy, serialiser,
        Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC), UUID::randomUUID);
    return new Context(UUID.randomUUID(), repository, serialiser, money);
  }

  @FunctionalInterface
  private interface WithdrawBehavior {
    ExternalResult apply(UUID playerId, String currencyId, BigDecimal amount);
  }

  private record Context(UUID player, JdbcExchangeRepository repository,
                         PlayerOperationSerialiser serialiser,
                         MoneyTransferService money) implements AutoCloseable {
    private BigDecimal availableCurrency() throws Exception {
      CurrencyBalance balance = repository.inTransaction(tx -> tx.currency(player, "USD"));
      return balance.available();
    }

    @Override
    public void close() {
      serialiser.close();
    }
  }
}
