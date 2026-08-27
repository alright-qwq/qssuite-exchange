package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandleSeriesTest {
  private static Candle candle(String bucket, String open, String high, String low,
                               String close, long volume) {
    return new Candle("diamond-usd", Instant.parse(bucket), new BigDecimal(open),
        new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume,
        new BigDecimal(close).multiply(BigDecimal.valueOf(volume)));
  }

  @Test
  void aggregatesAdjacentMinutesIntoOneBucket() {
    List<Candle> minutes = List.of(
        candle("2026-08-27T00:00:00Z", "100", "105", "99", "104", 3),
        candle("2026-08-27T00:01:00Z", "104", "106", "101", "102", 4),
        candle("2026-08-27T00:02:00Z", "102", "108", "100", "107", 2));

    List<Candle> aggregated = CandleSeries.aggregate(minutes, Duration.ofMinutes(3));

    assertThat(aggregated).hasSize(1);
    Candle bucket = aggregated.getFirst();
    assertThat(bucket.open()).isEqualByComparingTo("100");
    assertThat(bucket.high()).isEqualByComparingTo("108");
    assertThat(bucket.low()).isEqualByComparingTo("99");
    assertThat(bucket.close()).isEqualByComparingTo("107");
    assertThat(bucket.volume()).isEqualTo(9);
  }

  @Test
  void splitsAcrossBucketBoundariesAndPreservesChronology() {
    List<Candle> minutes = List.of(
        candle("2026-08-27T00:00:00Z", "100", "105", "99", "104", 3),
        candle("2026-08-27T00:01:00Z", "104", "106", "101", "102", 4),
        candle("2026-08-27T00:02:00Z", "102", "108", "100", "107", 2),
        candle("2026-08-27T00:03:00Z", "107", "110", "105", "108", 5));

    List<Candle> aggregated = CandleSeries.aggregate(minutes, Duration.ofMinutes(3));

    assertThat(aggregated).hasSize(2);
    assertThat(aggregated.get(0).bucketStart()).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"));
    assertThat(aggregated.get(1).bucketStart()).isEqualTo(Instant.parse("2026-08-27T00:03:00Z"));
    assertThat(aggregated.get(1).volume()).isEqualTo(5);
  }

  @Test
  void rejectsSubMinuteBuckets() {
    assertThatThrownBy(() -> CandleSeries.aggregate(List.of(), Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
