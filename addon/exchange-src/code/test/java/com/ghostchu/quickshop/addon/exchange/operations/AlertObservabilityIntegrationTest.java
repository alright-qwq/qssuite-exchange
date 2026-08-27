package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Real matching -> market data -> metrics -> detector -> repository -> admin view. */
class AlertObservabilityIntegrationTest {
  @Test
  void matchedReciprocalTradesFlowIntoAuditStatus() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    var service = fixture.serviceWithMarketData(marketData);
    ExchangeMetrics metrics = new ExchangeMetrics();
    marketData.addAuditConsumer(event -> metrics.recordMatchingLatency(
        event.marketId(), Duration.ZERO));
    UUID alice = fixture.accountWithItems(2);
    fixture.repository().inTransaction(tx -> {
      tx.creditAvailableCurrency(alice, fixture.rules().currencyId(), new BigDecimal("1000.00"));
      return null;
    });
    UUID bob = fixture.accountWithCurrency("1000.00");
    var aliceSell = service.place(new OrderRequest(UUID.randomUUID(), alice,
        fixture.rules().marketId(), OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    service.place(new OrderRequest(UUID.randomUUID(), bob, fixture.rules().marketId(),
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));
    service.cancel(alice, UUID.randomUUID(), aliceSell.orderId());
    service.place(new OrderRequest(UUID.randomUUID(), bob, fixture.rules().marketId(),
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    service.place(new OrderRequest(UUID.randomUUID(), alice, fixture.rules().marketId(),
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));
    assertThat(fixture.tradeCount()).isEqualTo(2);

    Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
    var detector = new SuspiciousTradingDetector(Clock.fixed(now, ZoneOffset.UTC));
    var scan = detector.scan(fixture.repository().tradesForDetection(now.minusSeconds(300)),
        List.of());
    assertThat(scan.alerts()).singleElement();
    for (var alert : scan.alerts()) {
      fixture.repository().insertAuditAlert(new AuditAlert(UUID.randomUUID(), alert.marketId(),
          alert.accountId(), alert.type(), alert.severity(), alert.evidence(), alert.at(), null));
    }

    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), service), fixture.repository(), null, null,
        null, null, metrics);
    AdminExchangeService.AuditStatus status = admin.auditStatus();

    assertThat(status.metrics().markets()).containsKey(fixture.rules().marketId());
    assertThat(status.recentAlerts()).singleElement().satisfies(alert ->
        assertThat(alert.type()).isEqualTo("HIGH_FREQUENCY_RECIPROCAL_TRADING"));
  }
}
