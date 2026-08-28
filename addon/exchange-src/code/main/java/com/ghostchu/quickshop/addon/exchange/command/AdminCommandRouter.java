package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.operations.ReviewDecision;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Parses privileged exchange commands and delegates all mutations to audited services. */
public final class AdminCommandRouter {
  private final AdminExchangeService administration;
  private final Supplier<UUID> requestIds;
  private final WriteExecutor writes;
  private final Executor reads;
  private final Function<String, String> symbolToMarketId;

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds) {
    this(administration, requestIds, work -> {
      work.run();
      return true;
    });
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes) {
    this(administration, requestIds, writes, Runnable::run, Function.identity());
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes, Executor reads) {
    this(administration, requestIds, writes, reads, Function.identity());
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes, Executor reads,
                            Function<String, String> symbolToMarketId) {
    this.administration = Objects.requireNonNull(administration, "administration");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.symbolToMarketId = Objects.requireNonNull(symbolToMarketId, "symbolToMarketId");
  }

  public void execute(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null || args.length < 2) {
      actor.message("admin-command-invalid");
      return;
    }
    try {
      if ("audit".equalsIgnoreCase(args[0])) {
        audit(actor, args);
        return;
      }
      if ("transfer".equalsIgnoreCase(args[0])) {
        transferReview(actor, args);
        return;
      }
      if ("stock".equalsIgnoreCase(args[0])) {
        stock(actor, args);
        return;
      }
      if (args.length < 4) {
        actor.message("admin-command-invalid");
        return;
      }
      if ("order".equalsIgnoreCase(args[0]) && "cancel".equalsIgnoreCase(args[1])) {
        cancelOrder(actor, args);
        return;
      }
      if ("market".equalsIgnoreCase(args[0])
          && ("pause".equalsIgnoreCase(args[1]) || "resume".equalsIgnoreCase(args[1]))) {
        changeMarketStatus(actor, args);
        return;
      }
      actor.message("admin-command-invalid");
    } catch (Throwable failure) {
      // Catch Throwable (not just RuntimeException) so linkage/classloading errors cannot escape
      // the admin command pipeline either; report and keep the plugin usable.
      LOGGER.log(java.util.logging.Level.SEVERE,
          "Exchange admin command failed for account " + actor.accountId(), failure);
      actor.message("admin-command-failed");
    }
  }

  private void stock(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.stock")) {
      actor.message("permission-denied");
      return;
    }
    if (args.length < 2) {
      actor.message("admin-command-invalid");
      return;
    }
    try {
      String action = args[1].toLowerCase(java.util.Locale.ROOT);
      if (args.length >= 7 && "create".equals(action)) {
        String symbol = args[2];
        String name = args[3];
        String currency = args[4];
        java.math.BigDecimal basePrice = new java.math.BigDecimal(args[5]);
        long totalSupply = Long.parseLong(args[6]);
        long minimumUnit = args.length >= 8 ? Long.parseLong(args[7]) : 1;
        String description = args.length >= 9
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 8, args.length)) : name;
        executeWrite(actor, () -> administration.securityCreate(
            actor.accountId(), requestIds.get(), symbol.toLowerCase(java.util.Locale.ROOT),
            symbol, name, description, currency, basePrice, totalSupply, minimumUnit));
        return;
      }
      if (args.length >= 6 && "issue".equals(action)) {
        String marketId = resolveMarket(args[2]);
        if (marketId == null) throw new IllegalArgumentException("unknown market or symbol");
        UUID target = UUID.fromString(args[3]);
        long quantity = Long.parseLong(args[4]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        executeWrite(actor, () -> administration.securityIssue(
            actor.accountId(), requestIds.get(), marketId, target, quantity, reason));
        return;
      }
      if (args.length >= 7 && "transfer".equals(action)) {
        String marketId = resolveMarket(args[2]);
        if (marketId == null) throw new IllegalArgumentException("unknown market or symbol");
        UUID from = UUID.fromString(args[3]);
        UUID to = UUID.fromString(args[4]);
        long quantity = Long.parseLong(args[5]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 6, args.length));
        executeWrite(actor, () -> administration.securityTransfer(
            actor.accountId(), requestIds.get(), marketId, from, to, quantity, reason));
        return;
      }
      if (args.length >= 4 && ("pause".equals(action) || "resume".equals(action))) {
        String marketId = resolveMarket(args[2]);
        if (marketId == null) throw new IllegalArgumentException("unknown market or symbol");
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        executeWrite(actor, () -> {
          if ("pause".equals(action)) {
            administration.securityPause(actor.accountId(), requestIds.get(), marketId, reason);
          } else {
            administration.securityResume(actor.accountId(), requestIds.get(), marketId, reason);
          }
        });
        return;
      }
      if (args.length >= 5 && "close".equals(action)) {
        String marketId = resolveMarket(args[2]);
        if (marketId == null) throw new IllegalArgumentException("unknown market or symbol");
        UUID recovery = UUID.fromString(args[3]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
        executeWrite(actor, () -> administration.securityClose(
            actor.accountId(), requestIds.get(), marketId, recovery, reason));
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (IllegalStateException attachFailure) {
      if (attachFailure.getMessage() != null
          && attachFailure.getMessage().startsWith("created-but-not-attached:")) {
        String marketId = attachFailure.getMessage()
            .substring("created-but-not-attached:".length())
            .split(";", 2)[0];
        actor.message("admin-stock-created-not-attached", marketId);
        return;
      }
      actor.message("admin-command-failed");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private String resolveMarket(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String resolved = symbolToMarketId.apply(raw);
    if (resolved != null && !resolved.isBlank()) {
      return resolved;
    }
    // A configured security symbol may not resolve when its market is not in the runtime
    // registry (e.g. created via `/qse admin stock create`); fall back to the canonical
    // lowercase market id derived from the symbol.
    if (raw.matches("[A-Z][A-Z0-9_]{0,15}")) {
      return raw.toLowerCase(java.util.Locale.ROOT);
    }
    return raw;
  }

  private void audit(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.audit")) {
      actor.message("permission-denied");
      return;
    }
    try {
      if (args.length == 2 && "reconcile".equalsIgnoreCase(args[1])) {
        executeReconciliation(actor);
        return;
      }
      if (args.length == 2 && "status".equalsIgnoreCase(args[1])) {
        reads.execute(() -> {
          try {
            AdminExchangeService.AuditStatus status = administration.auditStatus();
            String summary = auditStatusSummary(status);
            actor.executeAtOwner(() -> actor.message("admin-audit-status", summary));
          } catch (Exception failure) {
            actor.executeAtOwner(() -> actor.message("admin-command-failed"));
          }
        });
        return;
      }
      if (args.length == 3 && "ack".equalsIgnoreCase(args[1])) {
        UUID alertId = UUID.fromString(args[2]);
        executeWrite(actor, () -> administration.acknowledgeAlert(actor.accountId(), alertId),
            "admin-audit-acknowledged");
        return;
      }
      if (args.length == 4 && "export".equalsIgnoreCase(args[1])) {
        java.time.Instant from = parseInstant(args[2]);
        java.time.Instant to = parseInstant(args[3]);
        reads.execute(() -> {
          try {
            java.nio.file.Path exported = administration.exportAudit(from, to);
            actor.executeAtOwner(() -> actor.message(
                "admin-audit-exported", exported.getFileName().toString()));
          } catch (Exception failure) {
            actor.executeAtOwner(() -> actor.message("admin-command-failed"));
          }
        });
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      LOGGER.log(java.util.logging.Level.SEVERE,
          "Exchange admin command failed for account " + actor.accountId(), failure);
      actor.message("admin-command-failed");
    }
  }

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("QuickShop-Exchange.AdminCommand");

  private static String auditStatusSummary(AdminExchangeService.AuditStatus status) {
    java.util.Map<String, com.ghostchu.quickshop.addon.exchange.operations.MetricSnapshot.MarketMetrics>
        markets = status.metrics().markets();
    String metrics = markets.isEmpty() ? "no markets"
        : markets.entrySet().stream()
            .map(entry -> {
              var latency = entry.getValue().matchingLatency();
              return entry.getKey() + " queue=" + entry.getValue().queueLength()
                  + " p50=" + latency.p50Millis() + "ms p95=" + latency.p95Millis() + "ms";
            })
            .collect(java.util.stream.Collectors.joining(", "));
    String alerts = status.recentAlerts().isEmpty() ? "none"
        : status.recentAlerts().stream()
            .map(alert -> "[" + alert.alertId() + "] " + alert.severity() + " "
                + alert.type() + "@" + alert.marketId()
                + " " + alert.createdAt() + " evidence={" + alert.payload() + "}")
            .collect(java.util.stream.Collectors.joining(" | "));
    long openAlerts = status.recentAlerts().stream()
        .filter(alert -> alert.acknowledgedAt() == null).count();
    String pending = "pending-reviews=" + status.pendingTransferReviews().size();
    String open = "open-alerts=" + openAlerts;
    if (openAlerts > 0) {
      open = "§c⚠ " + open + " §r";
    }
    return "markets=" + metrics + "\nalerts=" + alerts + "\n" + pending + "\n" + open;
  }

  private void executeReconciliation(CommandActor actor) {
    try {
      java.util.concurrent.atomic.AtomicReference<
          com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport> report =
          new java.util.concurrent.atomic.AtomicReference<>();
      UUID requestId = requestIds.get();
      boolean completed = writes.execute(
          () -> report.set(administration.reconcile(actor.accountId(), requestId)));
      if (!completed || report.get() == null) {
        actor.message("admin-command-failed");
        return;
      }
      actor.message(report.get().balanced()
          ? "admin-reconciliation-balanced" : "admin-reconciliation-difference");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void transferReview(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.recovery")) {
      actor.message("permission-denied");
      return;
    }
    if (args.length < 3 || !"review".equalsIgnoreCase(args[1])) {
      actor.message("admin-command-invalid");
      return;
    }
    try {
      if (args.length == 3 && "list".equalsIgnoreCase(args[2])) {
        reads.execute(() -> {
          try {
            String summary = administration.pendingTransferReviews().stream()
                .map(AdminCommandRouter::transferSummary)
                .collect(java.util.stream.Collectors.joining("\n"));
            actor.executeAtOwner(() -> actor.message("admin-transfer-review-list", summary));
          } catch (Exception failure) {
            actor.executeAtOwner(() -> actor.message("admin-command-failed"));
          }
        });
        return;
      }
      if (args.length == 4 && "show".equalsIgnoreCase(args[2])) {
        UUID transferId = UUID.fromString(args[3]);
        reads.execute(() -> {
          try {
            String summary = transferSummary(administration.transferReview(transferId));
            actor.executeAtOwner(() -> actor.message("admin-transfer-review-detail", summary));
          } catch (Exception failure) {
            actor.executeAtOwner(() -> actor.message("admin-command-failed"));
          }
        });
        return;
      }
      if (args.length == 4 && "cleanup".equalsIgnoreCase(args[2])) {
        UUID transferId = UUID.fromString(args[3]);
        executeWrite(actor, () -> administration.cleanupItemMarkers(
            actor.accountId(), requestIds.get(), transferId));
        return;
      }
      if (args.length >= 6 && "resolve".equalsIgnoreCase(args[2])) {
        UUID transferId = UUID.fromString(args[3]);
        ReviewDecision decision = switch (args[4].toLowerCase(java.util.Locale.ROOT)) {
          case "success" -> ReviewDecision.CONFIRM_EXTERNAL_SUCCESS;
          case "failure" -> ReviewDecision.CONFIRM_EXTERNAL_FAILURE;
          default -> throw new IllegalArgumentException("unknown review decision");
        };
        String evidence = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        executeWrite(actor, () -> administration.resolveReview(
            actor.accountId(), requestIds.get(), transferId, decision, evidence));
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private static String transferSummary(TransferRecord transfer) {
    return transfer.transferId() + " " + transfer.type() + " " + transfer.status()
        + " account=" + transfer.accountId() + " asset=" + transfer.assetId()
        + " amount=" + transfer.amount().toPlainString()
        + " reason=" + Objects.toString(transfer.failureReason(), "-");
  }

  private static java.time.Instant parseInstant(String value) {
    try {
      return java.time.Instant.ofEpochSecond(Long.parseLong(value));
    } catch (NumberFormatException notEpoch) {
      return java.time.Instant.parse(value);
    }
  }

  private void cancelOrder(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.orders")) {
      actor.message("permission-denied");
      return;
    }
    UUID orderId;
    try {
      orderId = UUID.fromString(args[2]);
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
      return;
    }
    String reason = reason(args);
    executeWrite(actor, () ->
        administration.forceCancel(actor.accountId(), requestIds.get(), orderId, reason));
  }

  private void changeMarketStatus(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.market")) {
      actor.message("permission-denied");
      return;
    }
    String operation = args[1].toLowerCase(java.util.Locale.ROOT);
    String marketId = args[2];
    String reason = reason(args);
    executeWrite(actor, () -> {
      UUID requestId = requestIds.get();
      if ("pause".equals(operation)) {
        administration.pauseMarket(actor.accountId(), requestId, marketId, reason);
      } else {
        administration.resumeMarket(actor.accountId(), requestId, marketId, reason);
      }
    });
  }

  private void executeWrite(CommandActor actor, CheckedWork work) {
    executeWrite(actor, work, null);
  }

  private void executeWrite(CommandActor actor, CheckedWork work, String successKey) {
    try {
      boolean completed = writes.execute(work);
      actor.message(completed
          ? (successKey == null ? "request-accepted" : successKey)
          : "admin-command-failed");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private static String reason(String[] args) {
    return String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
  }

  @FunctionalInterface
  public interface WriteExecutor {
    boolean execute(CheckedWork work) throws Exception;
  }

  @FunctionalInterface
  public interface CheckedWork {
    void run() throws Exception;
  }
}
