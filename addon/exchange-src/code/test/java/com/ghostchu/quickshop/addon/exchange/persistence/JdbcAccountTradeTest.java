package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAccountTradeTest {
  @Test
  void accountTradesResolvesTakerAccountAndAttributesFees() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(2);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 2));

    List<ExchangeRepository.AccountTradeRow> buyerTrades =
        fixture.repository().accountTrades(buyer, 12, 0);
    List<ExchangeRepository.AccountTradeRow> sellerTrades =
        fixture.repository().accountTrades(seller, 12, 0);

    assertThat(buyerTrades).hasSize(1);
    assertThat(sellerTrades).hasSize(1);
    ExchangeRepository.AccountTradeRow buyerRow = buyerTrades.getFirst();
    ExchangeRepository.AccountTradeRow sellerRow = sellerTrades.getFirst();
    // The buyer placed second, so the buyer is the taker in this match.
    assertThat(buyerRow.takerAccountId()).isEqualTo(buyer);
    assertThat(sellerRow.takerAccountId()).isEqualTo(buyer);
    assertThat(buyerRow.feeFor(buyer)).isEqualByComparingTo(buyerRow.trade().takerFee());
    assertThat(sellerRow.feeFor(seller)).isEqualByComparingTo(sellerRow.trade().makerFee());
    // The two sides see the same trade and the fees split maker vs taker.
    assertThat(buyerRow.trade().tradeId()).isEqualTo(sellerRow.trade().tradeId());
    assertThat(buyerRow.feeFor(buyer).add(sellerRow.feeFor(seller)))
        .isEqualByComparingTo(buyerRow.trade().makerFee().add(buyerRow.trade().takerFee()));
    // A third party is not charged either fee.
    assertThat(buyerRow.feeFor(UUID.randomUUID())).isNull();
  }
}
