package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.OrderDraft;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.TransferDraft;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.TransferKind;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ExchangeCommandRouter {
  private final Supplier<UUID> requestIds;
  private final AdminCommandRouter administration;
  private final RolloutPolicy rollout;
  private final Function<String, String> symbolToMarketId;
  private final java.util.function.Supplier<java.util.List<String>> symbolCandidates;

  public ExchangeCommandRouter(Supplier<UUID> requestIds) {
    this(requestIds, null, RolloutPolicy.DISABLED, Function.identity(), List::of);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration) {
    this(requestIds, administration, RolloutPolicy.DISABLED, Function.identity(), List::of);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration,
                               RolloutPolicy rollout) {
    this(requestIds, administration, rollout, Function.identity(), List::of);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration,
                               RolloutPolicy rollout, Function<String, String> symbolToMarketId) {
    this(requestIds, administration, rollout, symbolToMarketId, List::of);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration,
                               RolloutPolicy rollout, Function<String, String> symbolToMarketId,
                               java.util.function.Supplier<java.util.List<String>> symbolCandidates) {
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.administration = administration;
    this.rollout = Objects.requireNonNull(rollout, "rollout");
    this.symbolToMarketId = Objects.requireNonNull(symbolToMarketId, "symbolToMarketId");
    this.symbolCandidates = Objects.requireNonNull(symbolCandidates, "symbolCandidates");
  }

  public void execute(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null) {
      invalid(actor);
      return;
    }
    try {
      executeGuarded(actor, args);
    } catch (Throwable failure) {
      // A single bad command or a transient runtime fault must never take down the command
      // pipeline: report the failure to the player and keep the plugin usable. Throwable also
      // captures linkage/classloading errors so a shaded-library conflict cannot escape into the
      // platform command executor.
      LOGGER.log(java.util.logging.Level.SEVERE,
          "Exchange command failed for account " + actor.accountId(), failure);
      actor.commandFailed();
    }
  }

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("QuickShop-Exchange.Command");

  private void executeGuarded(CommandActor actor, String[] args) {
    if (args.length > 0 && "admin".equalsIgnoreCase(args[0])) {
      if (args.length == 1) {
        if (!hasAnyAdminPermission(actor)) {
          actor.message("permission-denied");
          return;
        }
        actor.openMenu(ExchangeMenuRequest.page("admin"));
        return;
      }
      if (administration == null) {
        actor.message("admin-command-invalid");
      } else {
        administration.execute(actor, java.util.Arrays.copyOfRange(args, 1, args.length));
      }
      return;
    }
    if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
      if (!actor.hasPermission("quickshop.exchange.admin.reload")) {
        actor.message("permission-denied");
        return;
      }
      actor.reloadRequested();
      return;
    }
    if (!rollout.allows(actor.accountId())) {
      actor.message("rollout-not-allowed");
      return;
    }
    if (args.length == 0 || "open".equalsIgnoreCase(args[0])) {
      if (args.length > 1 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      actor.openMenu(ExchangeMenuRequest.page("markets"));
      return;
    }
    if ("help".equalsIgnoreCase(args[0])) {
      if (args.length != 1) {
        invalid(actor);
        return;
      }
      if (!allowed(actor, "quickshop.exchange.use")) {
        return;
      }
      actor.message("command-help");
      return;
    }
    if ("market".equalsIgnoreCase(args[0])) {
      if (args.length != 2 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      try {
        actor.openMenu(ExchangeMenuRequest.market(args[1]));
      } catch (IllegalArgumentException invalid) {
        invalid(actor);
      }
      return;
    }
    if ("order".equalsIgnoreCase(args[0])) {
      routeOrder(actor, args);
      return;
    }
    if ("cancel".equalsIgnoreCase(args[0])) {
      routeCancel(actor, args);
      return;
    }
    if ("deposit".equalsIgnoreCase(args[0]) || "withdraw".equalsIgnoreCase(args[0])) {
      routeTransfer(actor, args);
      return;
    }
    if ("orders".equalsIgnoreCase(args[0]) || "assets".equalsIgnoreCase(args[0])
        || "history".equalsIgnoreCase(args[0]) || "stocks".equalsIgnoreCase(args[0])) {
      if (args.length != 1 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      actor.openMenu(ExchangeMenuRequest.page(
          "stocks".equalsIgnoreCase(args[0]) ? "markets" : args[0]));
      return;
    }
    if ("stock".equalsIgnoreCase(args[0])) {
      if (args.length != 2 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      String marketId = symbolToMarketId.apply(args[1]);
      if (marketId == null || marketId.isBlank()) {
        invalid(actor);
        return;
      }
      actor.message("request-ready", requestIds.get());
      actor.openMenu(ExchangeMenuRequest.market(marketId));
      return;
    }
    invalid(actor);
  }

  private void routeOrder(CommandActor actor, String[] args) {
    if (args.length < 5 || args.length > 6) {
      invalid(actor);
      return;
    }
    if (!allowed(actor, "quickshop.exchange.use")) {
      return;
    }
    OrderType type;
    OrderSide side;
    try {
      type = parseType(args[1]);
      side = parseSide(args[2]);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
      return;
    }
    String permission = type == OrderType.MARKET
        ? "quickshop.exchange.order.market" : "quickshop.exchange.order.limit";
    if (!allowed(actor, permission)) {
      return;
    }
    if (args.length != 6) {
      invalid(actor);
      return;
    }
    try {
      BigDecimal price = type == OrderType.LIMIT ? positiveDecimal(args[4], "price") : null;
      long quantity = positiveLong(args[type == OrderType.LIMIT ? 5 : 4], "quantity");
      BigDecimal boundary = null;
      if (type == OrderType.MARKET) {
        boundary = positiveDecimal(args[5], "slippage boundary");
      }
      ExchangeMenuRequest request = ExchangeMenuRequest.order(new OrderDraft(
          requestIds.get(), actor.accountId(), args[3], side, type, price, boundary, quantity));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private void routeCancel(CommandActor actor, String[] args) {
    if (args.length != 2) {
      invalid(actor);
      return;
    }
    if (!allowed(actor, "quickshop.exchange.use")) {
      return;
    }
    if (!allowed(actor, "quickshop.exchange.order.cancel")) {
      return;
    }
    try {
      ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
          requestIds.get(), actor.accountId(), UUID.fromString(args[1]));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private void routeTransfer(CommandActor actor, String[] args) {
    if (args.length != 4) {
      invalid(actor);
      return;
    }
    if (!allowed(actor, "quickshop.exchange.use")) {
      return;
    }
    String verb = args[0].toLowerCase(java.util.Locale.ROOT);
    if (!allowed(actor, "quickshop.exchange." + verb)) {
      return;
    }
    try {
      boolean money = "money".equalsIgnoreCase(args[1]);
      TransferKind kind;
      BigDecimal amount = null;
      long quantity = 0;
      String assetId = args[2];
      String marketId = money ? null : args[2];
      if (money) {
        amount = positiveDecimal(args[3], "amount");
        kind = "deposit".equals(verb) ? TransferKind.MONEY_DEPOSIT
            : TransferKind.MONEY_WITHDRAWAL;
        assetId = args[2];
      } else {
        quantity = positiveLong(args[3], "quantity");
        kind = "deposit".equals(verb) ? TransferKind.ITEM_DEPOSIT
            : TransferKind.ITEM_WITHDRAWAL;
        assetId = marketId;
      }
      ExchangeMenuRequest request = ExchangeMenuRequest.transfer(new TransferDraft(
          requestIds.get(), actor.accountId(), kind, assetId, amount, quantity, marketId));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private static OrderType parseType(String raw) {
    return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
      case "limit" -> OrderType.LIMIT;
      case "market" -> OrderType.MARKET;
      default -> throw new IllegalArgumentException("unknown order type");
    };
  }

  private static OrderSide parseSide(String raw) {
    return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
      case "buy" -> OrderSide.BUY;
      case "sell" -> OrderSide.SELL;
      default -> throw new IllegalArgumentException("unknown order side");
    };
  }

  private static BigDecimal positiveDecimal(String raw, String name) {
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid " + name, invalid);
    }
  }

  private static long positiveLong(String raw, String name) {
    try {
      long value = Long.parseLong(raw);
      if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid " + name, invalid);
    }
  }

  private static boolean allowed(CommandActor actor, String permission) {
    if (actor.hasPermission(permission)) return true;
    actor.message("permission-denied");
    return false;
  }

  private static boolean hasAnyAdminPermission(CommandActor actor) {
    return actor.hasPermission("quickshop.exchange.admin.market")
        || actor.hasPermission("quickshop.exchange.admin.orders")
        || actor.hasPermission("quickshop.exchange.admin.recovery")
        || actor.hasPermission("quickshop.exchange.admin.audit")
        || actor.hasPermission("quickshop.exchange.admin.stock");
  }

  private static void invalid(CommandActor actor) {
    actor.message("command-invalid");
  }

  /** Returns only known subcommands and argument choices. */
  public List<String> tabComplete(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    try {
      if (args == null || args.length == 0) {
        return List.of("open", "market", "order", "cancel", "deposit", "withdraw", "orders",
            "assets", "history", "stocks", "stock", "admin", "reload", "help");
      }
      if (args.length == 1) {
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return List.of("open", "market", "order", "cancel", "deposit", "withdraw", "orders",
            "assets", "history", "stocks", "stock", "admin", "reload", "help").stream()
            .filter(value -> value.startsWith(prefix)).toList();
      }
      return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
        case "order" -> args.length == 2 ? List.of("limit", "market")
            : args.length == 3 ? List.of("buy", "sell") : List.of();
        case "stock" -> args.length == 2 ? prefixMatches(symbolCandidates.get(), args[1]) : List.of();
        case "deposit", "withdraw" -> args.length == 2 ? List.of("money", "item") : List.of();
        case "admin" -> args.length == 2 ? List.of("market", "order", "transfer", "audit", "stock")
            : args.length == 3 && "audit".equalsIgnoreCase(args[1])
                ? List.of("status", "ack", "reconcile", "export")
            : args.length == 4 && "audit".equalsIgnoreCase(args[1])
                && "ack".equalsIgnoreCase(args[2]) ? List.of("<alertId>")
            : args.length == 3 && "stock".equalsIgnoreCase(args[1])
                ? List.of("create", "issue", "transfer", "pause", "resume", "close")
            : args.length >= 4 && "stock".equalsIgnoreCase(args[1])
                && !"create".equalsIgnoreCase(args[2])
                ? prefixMatches(symbolCandidates.get(), args[3])
            : args.length == 3 && "transfer".equalsIgnoreCase(args[1]) ? List.of("review")
            : args.length == 4 && "transfer".equalsIgnoreCase(args[1])
                && "review".equalsIgnoreCase(args[2]) ? List.of("list", "show", "resolve")
            : List.of();
        default -> List.of();
      };
    } catch (Throwable failure) {
      // Tab completion must never take down the command pipeline; a failing candidate source
      // simply yields no suggestions this time.
      LOGGER.log(java.util.logging.Level.WARNING,
          "Exchange tab completion failed for account " + actor.accountId(), failure);
      return List.of();
    }
  }

  private static List<String> prefixMatches(List<String> values, String prefix) {
    String normalized = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
    return values.stream()
        .filter(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith(normalized))
        .toList();
  }
}
