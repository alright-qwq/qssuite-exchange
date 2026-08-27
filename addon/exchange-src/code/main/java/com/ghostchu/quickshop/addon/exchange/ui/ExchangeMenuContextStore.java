package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Keeps typed command state attached to a player while a TNML viewer is open. */
public final class ExchangeMenuContextStore implements AutoCloseable {
  private final Map<UUID, Context> requests = new ConcurrentHashMap<>();
  private final Consumer<UUID> marketViewExit;

  public ExchangeMenuContextStore() {
    this(ignored -> {});
  }

  public ExchangeMenuContextStore(Consumer<UUID> marketViewExit) {
    this.marketViewExit = Objects.requireNonNull(marketViewExit, "marketViewExit");
  }

  public void put(UUID playerId, ExchangeMenuRequest request) {
    UUID requiredPlayerId = Objects.requireNonNull(playerId, "playerId");
    ExchangeMenuRequest requiredRequest = Objects.requireNonNull(request, "request");
    Context previous = requests.put(requiredPlayerId, new Context(requiredRequest));
    if (previous != null && isLiveMarketView(previous.request()) && !isLiveMarketView(requiredRequest)) {
      marketViewExit.accept(requiredPlayerId);
    }
  }

  public Optional<ExchangeMenuRequest> get(UUID playerId) {
    Context context = requests.get(Objects.requireNonNull(playerId, "playerId"));
    return context == null ? Optional.empty() : Optional.of(context.request());
  }

  public boolean isCurrent(UUID playerId, ExchangeMenuRequest request) {
    Context context = requests.get(Objects.requireNonNull(playerId, "playerId"));
    return context != null && context.request() == Objects.requireNonNull(request, "request");
  }

  /** Atomically permits the current confirmation request to submit only once. */
  public boolean claim(UUID playerId, ExchangeMenuRequest request) {
    Context context = requests.get(Objects.requireNonNull(playerId, "playerId"));
    return context != null && context.request() == Objects.requireNonNull(request, "request")
        && context.claim();
  }

  public Optional<ExchangeMenuRequest> remove(UUID playerId) {
    Context context = requests.remove(Objects.requireNonNull(playerId, "playerId"));
    return context == null ? Optional.empty() : Optional.of(context.request());
  }

  public java.util.Set<UUID> playerIds() {
    return java.util.Set.copyOf(requests.keySet());
  }

  @Override
  public void close() {
    requests.clear();
  }

  private static final class Context {
    private final ExchangeMenuRequest request;
    private final java.util.concurrent.atomic.AtomicBoolean claimed =
        new java.util.concurrent.atomic.AtomicBoolean();

    private Context(ExchangeMenuRequest request) {
      this.request = request;
    }

    private ExchangeMenuRequest request() {
      return request;
    }

    private boolean claim() {
      return claimed.compareAndSet(false, true);
    }
  }

  private static boolean isLiveMarketView(ExchangeMenuRequest request) {
    return "markets".equals(request.menuName()) || "market-detail".equals(request.menuName());
  }
}
