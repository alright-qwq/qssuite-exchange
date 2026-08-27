package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

public final class OrderBook {
  private final NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> bids =
      new TreeMap<>(Comparator.reverseOrder());
  private final NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> asks = new TreeMap<>();
  private final Map<UUID, BigDecimal> priceByOrder = new HashMap<>();
  private final Map<UUID, OrderSide> sideByOrder = new HashMap<>();
  private String marketId;

  public void add(Order order) {
    requireActiveLimitOrder(order);
    if (!acceptsMarket(order.marketId())) {
      throw new IllegalArgumentException("order market does not match book");
    }
    if (priceByOrder.containsKey(order.orderId())) {
      throw new IllegalArgumentException("resting order requires a unique id");
    }
    levels(order.side()).computeIfAbsent(order.limitPrice(), ignored -> new LinkedHashMap<>())
        .put(order.orderId(), order);
    priceByOrder.put(order.orderId(), order.limitPrice());
    sideByOrder.put(order.orderId(), order.side());
    if (marketId == null) {
      marketId = order.marketId();
    }
  }

  public Optional<Order> best(OrderSide side) {
    NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> levels = levels(side);
    if (levels.isEmpty()) {
      return Optional.empty();
    }
    return levels.firstEntry().getValue().values().stream().findFirst();
  }

  public Optional<Order> bestExecutable(OrderSide side, Predicate<BigDecimal> executablePrice) {
    for (Map.Entry<BigDecimal, LinkedHashMap<UUID, Order>> level : levels(side).entrySet()) {
      if (!executablePrice.test(level.getKey())) {
        continue;
      }
      Optional<Order> first = level.getValue().values().stream().findFirst();
      if (first.isPresent()) {
        return first;
      }
    }
    return Optional.empty();
  }

  public Iterable<Order> executableOrders(OrderSide side, Predicate<BigDecimal> executablePrice) {
    if (executablePrice == null) {
      throw new IllegalArgumentException("executable price predicate is required");
    }
    NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> selectedLevels = levels(side);
    return () -> new Iterator<>() {
      private final Iterator<Map.Entry<BigDecimal, LinkedHashMap<UUID, Order>>> levelIterator =
          selectedLevels.entrySet().iterator();
      private Iterator<Order> orderIterator = List.<Order>of().iterator();

      @Override
      public boolean hasNext() {
        while (!orderIterator.hasNext() && levelIterator.hasNext()) {
          Map.Entry<BigDecimal, LinkedHashMap<UUID, Order>> level = levelIterator.next();
          if (executablePrice.test(level.getKey())) {
            orderIterator = level.getValue().values().iterator();
          }
        }
        return orderIterator.hasNext();
      }

      @Override
      public Order next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return orderIterator.next();
      }
    };
  }

  public Optional<Order> cancel(UUID orderId) {
    BigDecimal price = priceByOrder.remove(orderId);
    OrderSide side = sideByOrder.remove(orderId);
    if (price == null || side == null) {
      return Optional.empty();
    }
    LinkedHashMap<UUID, Order> level = levels(side).get(price);
    Order removed = level.remove(orderId);
    if (level.isEmpty()) {
      levels(side).remove(price);
    }
    return Optional.ofNullable(removed);
  }

  public void replaceRemaining(Order order) {
    replacementLevel(order).replace(order.orderId(), order);
  }

  public void validateReplacement(Order order) {
    replacementLevel(order);
  }

  private LinkedHashMap<UUID, Order> replacementLevel(Order order) {
    requireActiveLimitOrder(order);
    BigDecimal price = priceByOrder.get(order.orderId());
    OrderSide side = sideByOrder.get(order.orderId());
    if (price == null || side == null) {
      throw new IllegalArgumentException("order is not resting");
    }
    if (side != order.side() || price.compareTo(order.limitPrice()) != 0) {
      throw new IllegalArgumentException("replacement must retain order side and price");
    }
    LinkedHashMap<UUID, Order> level = levels(side).get(price);
    if (level == null || !level.containsKey(order.orderId())) {
      throw new IllegalStateException("resting order index is inconsistent");
    }
    Order current = level.get(order.orderId());
    if (!retainsImmutableFields(current, order)
        || order.remainingQuantity() >= current.remainingQuantity()
        || order.updatedAt().isBefore(current.updatedAt())) {
      throw new IllegalArgumentException("replacement may only reduce remaining quantity");
    }
    return level;
  }

  public int openOrderCount() {
    return priceByOrder.size();
  }

  public boolean contains(UUID orderId) {
    return orderId != null && priceByOrder.containsKey(orderId);
  }

  public boolean acceptsMarket(String candidateMarketId) {
    return candidateMarketId != null && !candidateMarketId.isBlank()
        && (marketId == null || marketId.equals(candidateMarketId));
  }

  public List<Order> orders(OrderSide side) {
    ArrayList<Order> snapshot = new ArrayList<>();
    for (LinkedHashMap<UUID, Order> level : levels(side).values()) {
      snapshot.addAll(level.values());
    }
    return List.copyOf(snapshot);
  }

  public List<Order> snapshot() {
    ArrayList<Order> result = new ArrayList<>();
    bids.values().forEach(level -> result.addAll(level.values()));
    asks.values().forEach(level -> result.addAll(level.values()));
    return List.copyOf(result);
  }

  private static void requireActiveLimitOrder(Order order) {
    if (order == null || order.type() != OrderType.LIMIT || order.limitPrice() == null
        || (order.status() != OrderStatus.OPEN && order.status() != OrderStatus.PARTIALLY_FILLED)) {
      throw new IllegalArgumentException("resting order must be an active limit order");
    }
  }

  private NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> levels(OrderSide side) {
    if (side == null) {
      throw new IllegalArgumentException("order side is required");
    }
    return side == OrderSide.BUY ? bids : asks;
  }

  private static boolean retainsImmutableFields(Order current, Order replacement) {
    return current.orderId().equals(replacement.orderId())
        && current.requestId().equals(replacement.requestId())
        && current.marketId().equals(replacement.marketId())
        && current.accountId().equals(replacement.accountId())
        && current.side() == replacement.side()
        && current.type() == replacement.type()
        && current.timeInForce() == replacement.timeInForce()
        && Objects.equals(current.limitPrice(), replacement.limitPrice())
        && Objects.equals(current.slippageBoundary(), replacement.slippageBoundary())
        && current.originalQuantity() == replacement.originalQuantity()
        && current.prioritySequence() == replacement.prioritySequence()
        && current.configVersion() == replacement.configVersion()
        && current.feeVersion() == replacement.feeVersion()
        && current.createdAt().equals(replacement.createdAt());
  }
}
