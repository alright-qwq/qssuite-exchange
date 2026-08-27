package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMarketDataRepositoryTest {
  @Test
  void upsertsAndLoadsCandlesInBucketOrder(@TempDir Path temp) throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("market-data.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    Instant first = Instant.parse("2026-07-26T00:00:00Z");
    Instant second = first.plusSeconds(60);

    repository.upsertCandle(new Candle("diamond-usd", second, new BigDecimal("110.00"),
        new BigDecimal("110.00"), new BigDecimal("110.00"), new BigDecimal("110.00"), 3,
        new BigDecimal("330.00")));
    repository.upsertCandle(new Candle("diamond-usd", first, new BigDecimal("100.00"),
        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("100.00"), 2,
        new BigDecimal("200.00")));
    repository.upsertCandle(new Candle("diamond-usd", first, new BigDecimal("100.00"),
        new BigDecimal("110.00"), new BigDecimal("100.00"), new BigDecimal("110.00"), 5,
        new BigDecimal("530.00")));

    assertThat(repository.loadCandles("diamond-usd", first, second.plusSeconds(60)))
        .extracting(Candle::bucketStart, Candle::close, Candle::volume, Candle::notional)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(first, new BigDecimal("110.00"), 5L,
                new BigDecimal("530.00")),
            org.assertj.core.groups.Tuple.tuple(second, new BigDecimal("110.00"), 3L,
                new BigDecimal("330.00")));
  }
}
