package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditAlert;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.OrderActivity;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.TradeActivity;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.math.BigDecimal;

public interface ExchangeRepository {
  <T> T inTransaction(TransactionWork<T> work) throws SQLException;

  /** Identity shared by repository decorators that coordinate access to the same database. */
  default Object coordinationKey() {
    return this;
  }

  /**
   * Reads a durable request receipt without taking a market settlement transaction.
   * Repository decorators that cannot provide a separate read connection may use the
   * transactional fallback.
   */
  default Optional<StoredRequestResult> findRequestResult(UUID accountId, UUID requestId)
      throws SQLException {
    return inTransaction(transaction -> transaction.requestResult(accountId, requestId));
  }

  default ReconciliationReport reconcile() throws SQLException {
    throw new UnsupportedOperationException("reconciliation is not supported by this repository");
  }

  default void upsertCandle(Candle candle) throws SQLException {
    throw new UnsupportedOperationException("candle persistence is not supported by this repository");
  }

  default List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    throw new UnsupportedOperationException("candle persistence is not supported by this repository");
  }

  /** Deletes persisted candles older than the cutoff for the given market. */
  default void deleteCandlesBefore(String marketId, Instant cutoff) throws SQLException {
    throw new UnsupportedOperationException("candle persistence is not supported by this repository");
  }

  default List<AuditRecord> auditRecords(Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    throw new UnsupportedOperationException("audit records are not supported by this repository");
  }

  /** Persists a detector-emitted alert outside any settlement transaction. */
  default void insertAuditAlert(AuditAlert alert) throws SQLException {
    throw new UnsupportedOperationException("audit alerts are not supported by this repository");
  }

  /** Reads the most recent alerts, newest first, regardless of acknowledgement state. */
  default List<AuditAlert> recentAlerts(int limit) throws SQLException {
    throw new UnsupportedOperationException("audit alerts are not supported by this repository");
  }

  /** Reads the most recent alerts that have not been acknowledged, newest first. */
  default List<AuditAlert> openAlerts(int limit) throws SQLException {
    throw new UnsupportedOperationException("audit alerts are not supported by this repository");
  }

  /** Marks one alert acknowledged at the given instant; no-op when the alert is unknown. */
  default void acknowledgeAlert(UUID alertId, Instant acknowledgedAt) throws SQLException {
    throw new UnsupportedOperationException("audit alerts are not supported by this repository");
  }

  /**
   * Reads trades executed at or after {@code sinceInclusive} across all markets, including both
   * parties, for suspicious-pattern detection. The read is intentionally immutable.
   */
  default List<TradeActivity> tradesForDetection(Instant sinceInclusive) throws SQLException {
    throw new UnsupportedOperationException("trading activity reads are not supported by this repository");
  }

  /** Reads order placements and cancellations at or after {@code sinceInclusive} across all markets. */
  default List<OrderActivity> orderActivities(Instant sinceInclusive) throws SQLException {
    throw new UnsupportedOperationException("order activity reads are not supported by this repository");
  }

  /** Reads a bounded page of a player's currently cancellable orders. */
  default List<ExchangeTransaction.PersistedOrder> accountOpenOrders(
      UUID accountId, int limit, int offset) throws SQLException {
    throw new UnsupportedOperationException("account order reads are not supported by this repository");
  }

  /** Reads one currently cancellable order by account and order id, if still open. */
  default Optional<ExchangeTransaction.PersistedOrder> openOrder(UUID accountId, UUID orderId)
      throws SQLException {
    throw new UnsupportedOperationException("account order reads are not supported by this repository");
  }

  /**
   * Reads a bounded page of a player's trades, newest first, with the taker account id so
   * views can show the exact fee that this account paid (maker fee for resting orders,
   * taker fee for aggressive orders).
   */
  default List<AccountTradeRow> accountTrades(UUID accountId, int limit, int offset)
      throws SQLException {
    throw new UnsupportedOperationException("account trade reads are not supported by this repository");
  }

  /** One account trade plus the taker's account id resolved from the order book. */
  record AccountTradeRow(Trade trade, UUID takerAccountId) {
    public AccountTradeRow {
      if (trade == null || takerAccountId == null) {
        throw new IllegalArgumentException("account trade row requires trade and taker");
      }
    }

    /** Fee charged to the given account for this trade. */
    public java.math.BigDecimal feeFor(UUID accountId) {
      if (accountId == null) return null;
      if (accountId.equals(takerAccountId)) {
        return trade.takerFee();
      }
      if (accountId.equals(trade.buyerAccountId()) || accountId.equals(trade.sellerAccountId())) {
        return trade.makerFee();
      }
      return null;
    }
  }

  default List<AccountAssetBalance> accountAssets(UUID accountId) throws SQLException {
    throw new UnsupportedOperationException("account asset reads are not supported by this repository");
  }

  /**
   * Reads the most recent trades for a market, newest first, including the taker side so
   * market detail pages can show the aggressive direction.
   */
  default List<MarketTradeRow> marketTrades(String marketId, int limit) throws SQLException {
    throw new UnsupportedOperationException("market trade reads are not supported by this repository");
  }

  /** Reads a bounded page of recent market trades, newest first. */
  default List<MarketTradeRow> marketTradesPage(String marketId, int limit, int offset)
      throws SQLException {
    if (offset == 0) {
      return marketTrades(marketId, limit);
    }
    throw new UnsupportedOperationException("market trade paging is not supported by this repository");
  }

  /** 24h market trade summary used by the market detail page. */
  default MarketTradeSummary marketTradeSummary(String marketId, Instant sinceInclusive)
      throws SQLException {
    throw new UnsupportedOperationException("market trade reads are not supported by this repository");
  }

  record MarketTradeSummary(int tradeCount, int buyCount, int sellCount, long volume) {
    public MarketTradeSummary {
      if (tradeCount < 0 || buyCount < 0 || sellCount < 0 || volume < 0) {
        throw new IllegalArgumentException("trade summary must be non-negative");
      }
    }
  }

  /** Lightweight market-detail read model for one recent trade. */
  record MarketTradeRow(long matchSequence, BigDecimal price, long quantity, OrderSide takerSide,
                        Instant executedAt) {
    public MarketTradeRow {
      if (matchSequence < 0 || price == null || price.signum() <= 0 || quantity <= 0
          || takerSide == null
          || executedAt == null) {
        throw new IllegalArgumentException("invalid market trade row");
      }
    }
  }

  default List<TransferRecord> accountTransfers(UUID accountId, int limit, int offset)
      throws SQLException {
    throw new UnsupportedOperationException("account transfer reads are not supported by this repository");
  }

  default List<AccountLedgerEntry> accountLedgerEntries(
      UUID accountId, int limit, int offset) throws SQLException {
    throw new UnsupportedOperationException("account ledger reads are not supported by this repository");
  }

  /**
   * Reads every registered security definition keyed by market id. Read-only and lock-free so
   * market-list views can load all status and issued-supply values in one snapshot instead of
   * opening one transaction per security.
   */
  default Map<String, SecurityDefinitionState> securityDefinitionStates() throws SQLException {
    throw new UnsupportedOperationException(
        "security definition batch reads are not supported by this repository");
  }

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
