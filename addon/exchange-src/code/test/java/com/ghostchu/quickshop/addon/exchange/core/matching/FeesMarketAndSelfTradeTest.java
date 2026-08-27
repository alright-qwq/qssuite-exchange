package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeesMarketAndSelfTradeTest {
  @Test
  void roundsFeeUpToCurrencyScaleAndCancelsIocRemainder() {
    FeeCalculator fees = new FeeCalculator(2);
    assertThat(fees.fee(new BigDecimal("1.01"), new BigDecimal("0.001")))
        .isEqualByComparingTo("0.01");

    OrderBook book = new OrderBook();
    AtomicLong sequence = new AtomicLong();
    MatchingEngine engine = engine(book, sequence);
    engine.submit(limit(OrderSide.SELL, "100.00", 2, UUID.randomUUID(), 1));
    Order market = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal("105.00"),
        5, 5, OrderStatus.OPEN, 2, 1, 1, Instant.EPOCH, Instant.EPOCH);

    MatchResult result = engine.submit(market);

    assertThat(result.trades()).hasSize(1);
    assertThat(result.trades().getFirst().makerFee()).isEqualByComparingTo("0.20");
    assertThat(result.trades().getFirst().takerFee()).isEqualByComparingTo("0.40");
    assertThat(result.finalOrder().remainingQuantity()).isEqualTo(3);
    assertThat(result.finalOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(result.rested()).isFalse();
    assertThat(book.openOrderCount()).isZero();
  }

  @Test
  void rejectsIncomingSideWhenAccountsMatch() {
    UUID owner = UUID.randomUUID();
    MatchingEngine engine = engine(new OrderBook(), new AtomicLong());
    engine.submit(limit(OrderSide.SELL, "100.00", 2, owner, 1));

    MatchResult result = engine.submit(limit(OrderSide.BUY, "100.00", 2, owner, 2));

    assertThat(result.selfTradeRejected()).isTrue();
    assertThat(result.trades()).isEmpty();
  }

  @Test
  void rejectsInvalidFeeAndReservationInputs() {
    assertThatThrownBy(() -> new FeeCalculator(-1)).isInstanceOf(IllegalArgumentException.class);
    FeeCalculator fees = new FeeCalculator(2);
    assertThatThrownBy(() -> fees.fee(null, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fees.fee(BigDecimal.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fees.fee(new BigDecimal("-1"), BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fees.fee(BigDecimal.ZERO, new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Reservation(null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Reservation(BigDecimal.ZERO, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reservesWorstCaseAssetsForLimitBuyAndSell() {
    ReservationCalculator reservations = new ReservationCalculator(new FeeCalculator(2));
    assertThat(reservations.reserve(limit(OrderSide.SELL, "100.00", 3, UUID.randomUUID(), 1),
        TestFixtures.rules())).isEqualTo(new Reservation(BigDecimal.ZERO, 3));
    assertThat(reservations.reserve(limit(OrderSide.BUY, "100.00", 2, UUID.randomUUID(), 2),
        TestFixtures.rules())).isEqualTo(new Reservation(new BigDecimal("200.40"), 0));
  }

  @Test
  void reservesMaximumPerFillFeeForLimitBuyThatMayRest() {
    MarketRules makerCostsMore = new MarketRules("diamond-usd", "USD", new BigDecimal("1.01"),
        new BigDecimal("0.01"), new BigDecimal("100.00"), new BigDecimal("0.01"),
        1, 10_000, 2, new BigDecimal("0.003"), new BigDecimal("0.001"));
    ReservationCalculator reservations = new ReservationCalculator(new FeeCalculator(2));

    Reservation reserved = reservations.reserve(
        limit(OrderSide.BUY, "1.01", 2, UUID.randomUUID(), 1), makerCostsMore);

    assertThat(reserved).isEqualTo(new Reservation(new BigDecimal("2.04"), 0));
  }

  @Test
  void reservesOnlyExecutableMarketBuyDepthAtMakerPrices() {
    OrderBook book = new OrderBook();
    book.add(limit(OrderSide.SELL, "100.00", 1, UUID.randomUUID(), 1));
    book.add(limit(OrderSide.SELL, "101.00", 2, UUID.randomUUID(), 2));
    book.add(limit(OrderSide.SELL, "103.00", 3, UUID.randomUUID(), 3));
    ReservationCalculator reservations = new ReservationCalculator(new FeeCalculator(2));
    Order marketBuy = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal("102.00"),
        5, 5, OrderStatus.OPEN, 4, 1, 1, Instant.EPOCH, Instant.EPOCH);

    assertThat(reservations.reserve(marketBuy, TestFixtures.rules(), book, price -> true))
        .isEqualTo(new Reservation(new BigDecimal("302.61"), 0));
  }

  @Test
  void reservesMarketBuyFeesRoundedForEachExecutableFill() {
    OrderBook book = new OrderBook();
    book.add(limit(OrderSide.SELL, "1.01", 1, UUID.randomUUID(), 1));
    book.add(limit(OrderSide.SELL, "1.01", 1, UUID.randomUUID(), 2));
    ReservationCalculator reservations = new ReservationCalculator(new FeeCalculator(2));
    Order marketBuy = market(OrderSide.BUY, "1.01", 2, 3);

    assertThat(reservations.reserve(marketBuy, TestFixtures.rules(), book, price -> true))
        .isEqualTo(new Reservation(new BigDecimal("2.04"), 0));
  }

  @Test
  void marketBuyReservationSkipsProtectedDepthLikeMatching() {
    OrderBook book = new OrderBook();
    book.add(limit(OrderSide.SELL, "70.00", 5, UUID.randomUUID(), 1));
    book.add(limit(OrderSide.SELL, "90.00", 1, UUID.randomUUID(), 2));
    ReservationCalculator reservations = new ReservationCalculator(new FeeCalculator(2));
    Order marketBuy = market(OrderSide.BUY, "100.00", 1, 3);

    Reservation reserved = reservations.reserve(marketBuy, TestFixtures.rules(), book,
        price -> price.compareTo(new BigDecimal("80.00")) >= 0);

    assertThat(reserved).isEqualTo(new Reservation(new BigDecimal("90.18"), 0));
  }

  @Test
  void rejectsMarketBuyWithAnEmptyAskBook() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book, new AtomicLong());
    Order marketBuy = market(OrderSide.BUY, "99.00", 1, 1);

    assertThatThrownBy(() -> engine.submit(marketBuy)).isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isZero();
  }

  @Test
  void cancelsMarketBuyWhenNonemptyAskBookIsOutsideSlippageBoundary() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book, new AtomicLong());
    Order restingAsk = limit(OrderSide.SELL, "101.00", 1, UUID.randomUUID(), 2);
    engine.submit(restingAsk);
    MatchResult result = engine.submit(market(OrderSide.BUY, "99.00", 1, 1));

    assertThat(result.trades()).isEmpty();
    assertThat(result.finalOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(result.rested()).isFalse();
    assertThat(book.orders(OrderSide.SELL)).containsExactly(restingAsk);
  }

  @Test
  void cancelsMarketSellWhenNoBidIsExecutable() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book, new AtomicLong());
    Order restingBid = limit(OrderSide.BUY, "100.00", 1, UUID.randomUUID(), 2);
    engine.submit(restingBid);
    MatchResult result = engine.submit(market(OrderSide.SELL, "101.00", 1, 3));

    assertThat(result.trades()).isEmpty();
    assertThat(result.finalOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(result.rested()).isFalse();
    assertThat(book.orders(OrderSide.BUY)).containsExactly(restingBid);
  }

  @Test
  void rejectsOrderWhoseMarketDoesNotMatchRulesWithoutMutatingBook() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = engine(book, new AtomicLong());
    Order goldOrder = new Order(UUID.randomUUID(), UUID.randomUUID(), "gold-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal("100.00"), null,
        1, 1, OrderStatus.OPEN, 1, 1, 1, Instant.EPOCH, Instant.EPOCH);

    assertThatThrownBy(() -> engine.submit(goldOrder)).isInstanceOf(IllegalArgumentException.class);
    assertThat(book.openOrderCount()).isZero();
  }

  private static MatchingEngine engine(OrderBook book, AtomicLong sequence) {
    return new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        sequence::incrementAndGet, () -> Instant.EPOCH, UUID::randomUUID);
  }

  private static Order limit(OrderSide side, String price, long quantity, UUID account, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", account,
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static Order market(OrderSide side, String boundary, long quantity, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        side, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal(boundary),
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
